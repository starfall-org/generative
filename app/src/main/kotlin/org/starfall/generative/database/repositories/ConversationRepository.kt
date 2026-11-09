package org.starfall.generative.database.repositories

import androidx.room.withTransaction
import java.io.File
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLog
import org.starfall.generative.shared.model.logging.DebugLogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.subfeatures.media.logic.generatedFileNames
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.ConversationSummary
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.generation.ContextUsageSnapshot
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.shared.model.pruningEmptyResponses
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ConversationEntity
import org.starfall.generative.database.room.entities.ConversationSummaryEntity
import org.starfall.generative.database.room.entities.MessageEntity
import org.starfall.generative.database.room.entities.MessageVersionEntity
import org.starfall.generative.database.room.entities.MessageVersionFileEntity
import org.starfall.generative.database.room.entities.ToolActivityEntity
import org.starfall.generative.database.room.entities.ToolActivityFileEntity

class ConversationMigrationException(
    val conversationId: String,
    val issueCodes: List<String>
) : IllegalStateException("Conversation $conversationId needs recovery before it can be opened.")

class ConversationRepository @JvmOverloads constructor(
    private val db: AppDatabase,
    journalDirectory: File? = null,
    private val toolFiles: ToolFiles? = null
) {
    private val journal = journalDirectory?.let(::ConversationSaveJournal)
    private val dao = db.conversationDao()
    private val writeLock = Mutex()

    // Room is never observed. Bootstrap the lightweight history index exactly once,
    // and publish subsequent changes only when an application action changes it.
    // The same lock protects the first read against concurrent saves and restores.
    private val listScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listState = MutableStateFlow<List<ConversationListItem>?>(null)
    init {
        listScope.launch {
            writeLock.withLock {
                listState.value = dao.getConversationListItems()
            }
        }
    }
    val conversationList: Flow<List<ConversationListItem>> = listState.filterNotNull().distinctUntilChanged()

    /** Compatibility full-history flow. Only explicit history-list changes trigger a load. */
    val allConversations: Flow<List<Conversation>> = conversationList.map { entries ->
        entries.mapNotNull { loadById(it.id) }
    }.flowOn(Dispatchers.IO)

    private fun publishList(edit: (List<ConversationListItem>) -> List<ConversationListItem>) {
        // When the initial read is still pending, it will include this completed
        // write because the same writeLock guards both code paths.
        listState.update { current -> current?.let(edit) }
    }

    private fun publishSaved(conversation: Conversation) {
        val item = ConversationListItem(
            id = conversation.id,
            title = conversation.title,
            createdAt = conversation.createdAt,
            updatedAt = conversation.updatedAt,
            tokenCount = conversation.tokenCount,
            providerId = conversation.providerId,
            modelId = conversation.modelId,
            profileId = conversation.profileId,
            reasoningEffort = conversation.reasoningEffort,
            hasMessages = conversation.messages.isNotEmpty(),
            hasSummary = conversation.summary?.throughMessageId?.let { id ->
                conversation.messages.any { it.id == id }
            } ?: false,
            hasMigrationIssue = listState.value?.firstOrNull { it.id == conversation.id }?.hasMigrationIssue ?: false
        )
        publishList { existing ->
            (existing.filterNot { it.id == item.id } + item)
                .sortedWith(compareByDescending<ConversationListItem> { it.updatedAt }.thenBy { it.id })
        }
    }

    /** Full-history lookup retained for callers that predate [loadById]. */
    suspend fun getById(id: String): Conversation? = loadById(id)

    /** Loads exactly one conversation and its relational children. */
    suspend fun loadById(id: String): Conversation? = withContext(Dispatchers.IO) {
        writeLock.withLock {
            restorePendingSave(id)
            db.withTransaction {
                dao.getConversationById(id)?.let { entityToModel(it) }
            }
        }
    }

    suspend fun saveConversation(conversation: Conversation) {
        if (conversation.isTemporary) return
        val logId = DebugLog.beginOperation(DebugLogCategory.SYSTEM, "Conversation save",
            "Saving conversation ${conversation.id}",
            "messages=${conversation.messages.size}")
        try {
            withContext(Dispatchers.IO) {
                val persisted = conversation.pruningEmptyResponses()
                writeLock.withLock {
                    journal?.write(persisted)
                    commitConversation(persisted)
                    journal?.remove(persisted.id)
                }
            }
            DebugLog.finishOperation(logId, "Conversation saved")
        } catch (error: Exception) {
            DebugLog.finishOperation(logId, "Conversation save failed", failed = true, error = error)
            throw error
        }
    }

    /** Startup/event-driven recovery only. A failed record remains available for another attempt. */
    suspend fun recoverPendingSaves() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            journal?.pending()?.forEach { file ->
                try {
                    journal.read(file)?.let { commitConversation(it) }
                    file.delete()
                } catch (error: Exception) {
                    DebugLog.error(DebugLogCategory.SYSTEM, "Conversation save", "Pending save recovery failed", error)
                }
            }
        }
    }

    private suspend fun restorePendingSave(id: String) {
        val pending = journal?.read(id) ?: return
        commitConversation(pending)
        journal.remove(id)
    }

    private suspend fun commitConversation(conversation: Conversation) {
        if (conversation.isTemporary) return
        db.withTransaction {
            upsertConversation(modelToEntity(conversation))
            persistChildren(conversation)
        }
        // Persist output ownership separately from chat history. It must survive chat deletion.
        toolFiles?.let { it.markGeneratedMedia(conversation.generatedFileNames(it)) }
        publishSaved(conversation)
    }

    suspend fun renameConversation(id: String, title: String) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                restorePendingSave(id)
                db.withTransaction {
                    // REPLACE deletes the parent first and cascades into its history.
                    dao.renameConversation(id, title, now)
                }
                publishList { items ->
                    items.map { if (it.id == id) it.copy(title = title, updatedAt = now) else it }
                        .sortedWith(compareByDescending<ConversationListItem> { it.updatedAt }.thenBy { it.id })
                }
            }
        }
    }

    suspend fun deleteConversation(id: String) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                journal?.remove(id)
                db.withTransaction { dao.deleteById(id) }
                publishList { it.filterNot { item -> item.id == id } }
            }
        }
    }

    suspend fun deleteConversations(ids: List<String>) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                ids.forEach { journal?.remove(it) }
                db.withTransaction { ids.chunked(500).forEach { dao.deleteByIds(it) } }
                val removed = ids.toSet()
                publishList { it.filterNot { item -> item.id in removed } }
            }
        }
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                journal?.clear()
                db.withTransaction { dao.deleteAll() }
                publishList { emptyList() }
            }
        }
    }

    /** Replaces every conversation during a full application restore. */
    suspend fun replaceAll(conversations: List<Conversation>) {
        deleteAll()
        conversations.forEach { saveConversation(it) }
    }

    /** The subset of [names] that no stored conversation still refers to. */
    suspend fun unreferencedFiles(names: Collection<String>): Set<String> =
        names.filterNot { dao.mentionsFile(it) }.toSet()

    /** Returns raw references without decoding any conversation or message model. */
    suspend fun allFileReferences(): List<String> = withContext(Dispatchers.IO) {
        dao.getAllFileReferences()
    }

    /** Relational references remain available even when a conversation is quarantined. */
    suspend fun userFileReferences(): List<String> = withContext(Dispatchers.IO) {
        dao.getUserFileReferences()
    }

    suspend fun generatedFileReferences(): List<String> = withContext(Dispatchers.IO) {
        dao.getGeneratedFileReferences()
    }

    private suspend fun entityToModel(entity: ConversationEntity): Conversation {
        val issues = dao.getMigrationIssues(entity.id)
        if (issues.isNotEmpty()) throw ConversationMigrationException(entity.id, issues.map { it.errorCode })

        // Materialize nested data newest-first, then restore chronological order for chat/history logic.
        val messages = dao.getMessagesNewestFirst(entity.id).map { message ->
            val versions = dao.getMessageVersions(entity.id, message.id).map { version ->
                val activities = dao.getToolActivities(entity.id, message.id, version.versionIndex).map { activity ->
                    ToolActivity(
                        id = activity.activityId,
                        name = activity.name,
                        wireName = activity.wireName,
                        status = activity.status,
                        summary = activity.summary,
                        files = dao.getToolActivityFiles(entity.id, message.id, version.versionIndex, activity.activityIndex).map { it.reference },
                        arguments = activity.arguments,
                        response = activity.response,
                        contentOffset = activity.contentOffset,
                        reasoningOffset = activity.reasoningOffset,
                        inlineMedia = activity.inlineMedia,
                        responsePreviewLimit = activity.responsePreviewLimit,
                        actionGroup = activity.actionGroup
                    )
                }
                MessageVersion(
                    content = version.content,
                    timestamp = version.timestamp,
                    files = dao.getMessageVersionFiles(entity.id, message.id, version.versionIndex).map { it.reference },
                    toolActivity = activities,
                    reasoningContent = version.reasoningContent,
                    reasoningSignature = version.reasoningSignature,
                    processingFinishedAt = version.processingFinishedAt,
                    providerId = version.providerId,
                    modelId = version.modelId,
                    modelDisplayName = version.modelDisplayName,
                    generationInterrupted = version.generationInterrupted,
                    generationError = version.generationError,
                    contextUsage = version.toContextUsage(),
                    reasoningContentOffset = version.reasoningContentOffset,
                    filesContentOffset = version.filesContentOffset,
                    mediaContentOffsets = runCatching {
                        json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()),
                            version.mediaContentOffsetsJson)
                    }.getOrDefault(emptyMap()),
                    blockOrder = runCatching {
                        json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()),
                            version.blockOrderJson)
                    }.getOrDefault(emptyMap())
                )
            }
            StoredMessage(
                id = message.id,
                role = message.role.toChatRole(),
                versions = versions,
                activeVersionIndex = message.activeVersionIndex
            )
        }.asReversed()
        return Conversation(
            id = entity.id,
            title = entity.title,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            messages = messages,
            tokenCount = entity.tokenCount,
            providerId = entity.providerId,
            modelId = entity.modelId,
            profileId = entity.profileId,
            summary = dao.getSummary(entity.id)?.toModel(),
            reasoningEffort = entity.reasoningEffort,
            selectedInstructionIds = entity.selectedInstructionIds?.let(::decodeInstructionIds),
            remoteWorkspaces = runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), entity.remoteWorkspacesJson)
            }.getOrDefault(emptyMap())
        )
    }

    private suspend fun persistChildren(conversation: Conversation) {
        val existingMessages = dao.getMessages(conversation.id)
        val messageIds = conversation.messages.map { it.id }
        if (messageIds.isEmpty()) dao.deleteAllMessages(conversation.id)
        else dao.deleteMessagesExcept(conversation.id, messageIds)

        // Message sequence is unique. Shift only when the message set/order changed so a reorder
        // can be applied without deleting and rebuilding any message's descendants.
        val resequence = existingMessages.map { it.id } != messageIds
        if (resequence) dao.shiftMessageSequences(conversation.id, 1_000_000)
        val existingById = existingMessages.associateBy { it.id }

        conversation.messages.forEachIndexed { sequence, message ->
            val entity = MessageEntity(conversation.id, message.id, sequence, message.role.persistedValue, message.activeVersionIndex)
            val previous = existingById[message.id]
            // Every shifted row must be restored, even if its final sequence equals its OLD value.
            if (previous == null) dao.insertMessage(entity) else if (resequence || previous != entity) dao.updateMessage(entity)
            persistMessage(conversation.id, message)
        }

        val summary = conversation.summary?.takeIf { it.throughMessageId in messageIds }?.let {
            ConversationSummaryEntity(conversation.id, it.id, it.content, it.throughMessageId, it.role.name, it.createdAt)
        }
        if (summary == null) {
            dao.deleteSummary(conversation.id)
        } else {
            val previous = dao.getSummary(conversation.id)
            if (previous == null) dao.insertSummaryIfAbsent(summary)
            else if (previous != summary) dao.updateSummary(summary)
        }
    }

    private suspend fun upsertConversation(entity: ConversationEntity) {
        if (dao.updateConversation(entity) == 0) dao.insertConversation(entity)
    }

    private suspend fun persistMessage(conversationId: String, message: StoredMessage) {
        val existingVersions = dao.getMessageVersions(conversationId, message.id)
        val versionIndexes = message.versions.indices.toList()
        if (versionIndexes.isEmpty()) dao.deleteMessageVersions(conversationId, message.id)
        else dao.deleteMessageVersionsExcept(conversationId, message.id, versionIndexes)

        message.versions.forEachIndexed { versionIndex, version ->
            val contextUsage = version.contextUsage?.validated()
            val entity = MessageVersionEntity(
                conversationId = conversationId,
                messageId = message.id,
                versionIndex = versionIndex,
                content = version.content,
                timestamp = version.timestamp,
                reasoningContent = version.reasoningContent,
                reasoningSignature = version.reasoningSignature,
                processingFinishedAt = version.processingFinishedAt,
                providerId = version.providerId,
                modelId = version.modelId,
                modelDisplayName = version.modelDisplayName,
                generationInterrupted = version.generationInterrupted,
                generationError = version.generationError,
                usageInputTokens = contextUsage?.usage?.inputTokens,
                usageOutputTokens = contextUsage?.usage?.outputTokens,
                usageContextKey = contextUsage?.contextKey,
                usageMessagesFingerprint = contextUsage?.messagesFingerprint,
                reasoningContentOffset = version.reasoningContentOffset,
                filesContentOffset = version.filesContentOffset,
                mediaContentOffsetsJson = json.encodeToString(
                    MapSerializer(String.serializer(), Int.serializer()), version.mediaContentOffsets
                ),
                blockOrderJson = json.encodeToString(
                    MapSerializer(String.serializer(), Int.serializer()), version.blockOrder
                )
            )
            val previous = existingVersions.firstOrNull { it.versionIndex == versionIndex }
            if (previous == null) dao.insertMessageVersion(entity)
            else if (previous != entity) dao.updateMessageVersion(entity)
            persistVersionFiles(conversationId, message.id, versionIndex, version.files)
            persistActivities(conversationId, message.id, versionIndex, version.toolActivity)
        }
    }

    private suspend fun persistVersionFiles(conversationId: String, messageId: String, versionIndex: Int, references: List<String>) {
        val existing = dao.getMessageVersionFiles(conversationId, messageId, versionIndex)
        if (existing.map { it.reference } == references) return
        if (references.isEmpty()) dao.deleteMessageVersionFiles(conversationId, messageId, versionIndex)
        else dao.deleteMessageVersionFilesExcept(conversationId, messageId, versionIndex, references.indices.toList())
        dao.insertMessageVersionFiles(references.mapIndexed { fileIndex, reference ->
            MessageVersionFileEntity(conversationId, messageId, versionIndex, fileIndex, reference)
        })
    }

    private suspend fun persistActivities(conversationId: String, messageId: String, versionIndex: Int, activities: List<ToolActivity>) {
        val existing = dao.getToolActivities(conversationId, messageId, versionIndex)
        val activityIndexes = activities.indices.toList()
        if (activityIndexes.isEmpty()) dao.deleteToolActivities(conversationId, messageId, versionIndex)
        else dao.deleteToolActivitiesExcept(conversationId, messageId, versionIndex, activityIndexes)
        activities.forEachIndexed { activityIndex, activity ->
            val entity = ToolActivityEntity(
                conversationId, messageId, versionIndex, activityIndex, activity.id, activity.name,
                activity.wireName, activity.status, activity.summary, activity.arguments, activity.response,
                activity.contentOffset, activity.reasoningOffset, activity.inlineMedia, activity.responsePreviewLimit,
                activity.actionGroup
            )
            val previous = existing.firstOrNull { it.activityIndex == activityIndex }
            if (previous == null) dao.insertToolActivity(entity)
            else if (previous != entity) dao.updateToolActivity(entity)
            persistActivityFiles(conversationId, messageId, versionIndex, activityIndex, activity.files)
        }
    }

    private suspend fun persistActivityFiles(conversationId: String, messageId: String, versionIndex: Int, activityIndex: Int, references: List<String>) {
        val existing = dao.getToolActivityFiles(conversationId, messageId, versionIndex, activityIndex)
        if (existing.map { it.reference } == references) return
        if (references.isEmpty()) dao.deleteToolActivityFiles(conversationId, messageId, versionIndex, activityIndex)
        else dao.deleteToolActivityFilesExcept(conversationId, messageId, versionIndex, activityIndex, references.indices.toList())
        dao.insertToolActivityFiles(references.mapIndexed { fileIndex, reference ->
            ToolActivityFileEntity(conversationId, messageId, versionIndex, activityIndex, fileIndex, reference)
        })
    }

    private fun modelToEntity(conversation: Conversation) = ConversationEntity(
        id = conversation.id,
        title = conversation.title,
        createdAt = conversation.createdAt,
        updatedAt = conversation.updatedAt,
        tokenCount = conversation.tokenCount,
        providerId = conversation.providerId,
        modelId = conversation.modelId,
        profileId = conversation.profileId,
        reasoningEffort = conversation.reasoningEffort,
        selectedInstructionIds = conversation.selectedInstructionIds?.let(::encodeInstructionIds),
        remoteWorkspacesJson = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()), conversation.remoteWorkspaces)
    )

    private fun encodeInstructionIds(ids: Set<String>): String =
        json.encodeToString(SetSerializer(String.serializer()), ids)

    // A damaged value falls back to the library default instead of making the chat unreadable.
    private fun decodeInstructionIds(raw: String): Set<String>? =
        runCatching { json.decodeFromString(SetSerializer(String.serializer()), raw) }.getOrNull()

    private fun MessageVersionEntity.toContextUsage(): ContextUsageSnapshot? {
        val inputTokens = usageInputTokens ?: return null
        val contextKey = usageContextKey ?: return null
        val messagesFingerprint = usageMessagesFingerprint ?: return null
        return ContextUsageSnapshot(
            usage = TokenUsage(inputTokens, usageOutputTokens),
            contextKey = contextKey,
            messagesFingerprint = messagesFingerprint
        ).validated()
    }

    private fun ContextUsageSnapshot.validated(): ContextUsageSnapshot? = takeIf {
        usage.isValid && contextKey.isNotBlank() && messagesFingerprint.isNotBlank()
    }

    private fun ConversationSummaryEntity.toModel() = ConversationSummary(
        id = id,
        content = content,
        throughMessageId = throughMessageId,
        role = runCatching { org.starfall.generative.shared.model.SummaryRole.valueOf(role) }
            .getOrDefault(org.starfall.generative.shared.model.SummaryRole.SYSTEM),
        createdAt = createdAt
    )

    private fun String.toChatRole() = when (this) {
        "user" -> ChatRole.USER
        "model" -> ChatRole.MODEL
        "system" -> ChatRole.SYSTEM
        else -> throw IllegalStateException("Unknown persisted chat role: $this")
    }

    private val ChatRole.persistedValue: String
        get() = when (this) {
            ChatRole.USER -> "user"
            ChatRole.MODEL -> "model"
            ChatRole.SYSTEM -> "system"
        }
}
