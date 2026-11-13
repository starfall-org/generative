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
import org.starfall.generative.shared.model.ToolPayload
import org.starfall.generative.shared.model.ToolPayloadCache
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

private typealias StoredPayload = Pair<ToolPayload, ToolPayloadKey>

class ConversationMigrationException(
    val conversationId: String,
    val issueCodes: List<String>
) : IllegalStateException("Conversation $conversationId needs recovery before it can be opened.")

class ConversationRepository @JvmOverloads constructor(
    private val db: AppDatabase,
    journalDirectory: File? = null,
    private val toolFiles: ToolFiles? = null
) {
    // Kotlin data-class snapshots are the process-local fallback for failed writes.
    // SQLite transactions provide atomic durable storage; no new JSON journal is written.
    private data class SaveState(val committed: Conversation?, val pending: Conversation? = null)
    private val saveStates = object : LinkedHashMap<String, SaveState>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SaveState>?): Boolean =
            size > 4 && eldest?.value?.pending == null
    }
    private val legacyJournal = journalDirectory?.let(::LegacyConversationSaveJournal)
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

    /** Loads one conversation; a failed in-process write remains readable in RAM. */
    suspend fun loadById(id: String): Conversation? = withContext(Dispatchers.IO) {
        writeLock.withLock {
            saveStates[id]?.pending ?: db.withTransaction {
                dao.getConversationById(id)?.let { entityToModel(it) }
            }?.also { saveStates[id] = SaveState(committed = it) }
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
                    val previous = saveStates[persisted.id]?.committed
                    try {
                        commitConversation(persisted, previous)
                        saveStates[persisted.id] = SaveState(committed = persisted)
                    } catch (error: Exception) {
                        saveStates[persisted.id] = SaveState(committed = previous, pending = persisted)
                        throw error
                    }
                }
            }
            DebugLog.finishOperation(logId, "Conversation saved")
        } catch (error: Exception) {
            DebugLog.finishOperation(logId, "Conversation save failed", failed = true, error = error)
            throw error
        }
    }

    /**
     * One-time upgrade recovery for pending JSON records from previous app releases.
     * A broken legacy record must never block reading the existing SQLite history.
     */
    suspend fun recoverPendingSaves() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            legacyJournal?.pending()?.forEach { file ->
                try {
                    val pending = legacyJournal.read(file)
                    if (pending != null && !pending.isTemporary) {
                        val stored = dao.getConversationById(pending.id)
                        // A newer successful write must not be overwritten by an old legacy record.
                        if (stored == null || stored.updatedAt <= pending.updatedAt) {
                            commitConversation(pending)
                            saveStates[pending.id] = SaveState(committed = pending)
                        }
                    }
                    file.delete()
                } catch (error: Exception) {
                    DebugLog.error(DebugLogCategory.SYSTEM, "Conversation save", "Legacy save recovery failed", error)
                }
            }
        }
    }

    private suspend fun commitConversation(conversation: Conversation, previous: Conversation? = null) {
        if (conversation.isTemporary) return
        // A payload that exists only in a row this commit may delete or overwrite is read back first.
        pinMovedPayloads(conversation, previous)
        val stored = mutableListOf<StoredPayload>()
        db.withTransaction {
            val parent = modelToEntity(conversation)
            if (previous == null || modelToEntity(previous) != parent) upsertConversation(parent)
            persistChildren(conversation, previous, stored)
        }
        // Only now is the text durable, so only now may a payload release its strong copy.
        stored.forEach { (payload, key) -> payload.attachSource(RoomToolPayloadSource(db, key)) }
        // Persist output ownership separately from chat history. It must survive chat deletion.
        toolFiles?.let { it.markGeneratedMedia(conversation.generatedFileNames(it)) }
        publishSaved(conversation)
    }

    suspend fun renameConversation(id: String, title: String) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                db.withTransaction {
                    // REPLACE deletes the parent first and cascades into its history.
                    dao.renameConversation(id, title, now)
                }
                saveStates[id]?.let { state ->
                    saveStates[id] = SaveState(
                        committed = state.committed?.copy(title = title, updatedAt = now),
                        pending = state.pending?.copy(title = title, updatedAt = now)
                    )
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
                legacyJournal?.remove(id)
                saveStates.remove(id)
                db.withTransaction { dao.deleteById(id) }
                ToolPayloadCache.invalidate { it is ToolPayloadKey && it.conversationId == id }
                publishList { it.filterNot { item -> item.id == id } }
            }
        }
    }

    suspend fun deleteConversations(ids: List<String>) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                ids.forEach { legacyJournal?.remove(it); saveStates.remove(it) }
                db.withTransaction { ids.chunked(500).forEach { dao.deleteByIds(it) } }
                val removed = ids.toSet()
                ToolPayloadCache.invalidate { it is ToolPayloadKey && it.conversationId in removed }
                publishList { it.filterNot { item -> item.id in removed } }
            }
        }
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                legacyJournal?.clear()
                saveStates.clear()
                db.withTransaction { dao.deleteAll() }
                // Restored data may reuse these ids with different payloads.
                ToolPayloadCache.invalidate { it is ToolPayloadKey }
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
            val versions = dao.getMessageVersionsForLoad(entity.id, message.id).map { version ->
                val payloadSizes = dao.getToolPayloadSizes(entity.id, message.id, version.versionIndex)
                    .associateBy { it.activityIndex }
                val activities = dao.getToolActivities(entity.id, message.id, version.versionIndex).map { activity ->
                    val sizes = checkNotNull(payloadSizes[activity.activityIndex]) {
                        "Missing payload sizes for tool activity ${activity.activityId}"
                    }
                    ToolActivity(
                        id = activity.activityId,
                        name = activity.name,
                        wireName = activity.wireName,
                        status = activity.status,
                        summary = activity.summary,
                        files = dao.getToolActivityFiles(entity.id, message.id, version.versionIndex, activity.activityIndex).map { it.reference },
                        // Payloads stay in storage until something reads them; only short ones are kept.
                        arguments = storedPayload(version.versionIndex, activity, ToolPayloadField.ARGUMENTS,
                            activity.arguments, sizes.argumentsSize),
                        response = storedPayload(version.versionIndex, activity, ToolPayloadField.RESPONSE,
                            activity.response, sizes.responseSize),
                        contentOffset = activity.contentOffset,
                        reasoningOffset = activity.reasoningOffset,
                        inlineMedia = activity.inlineMedia,
                        responsePreviewLimit = activity.responsePreviewLimit,
                        actionGroup = activity.actionGroup
                    )
                }
                MessageVersion(
                    content = readToolText(version.content) { start, size ->
                        dao.getVersionContentChunk(entity.id, message.id, version.versionIndex, start, size)
                    },
                    timestamp = version.timestamp,
                    files = dao.getMessageVersionFiles(entity.id, message.id, version.versionIndex).map { it.reference },
                    toolActivity = activities,
                    reasoningContent = version.reasoningContent?.let { text ->
                        readToolText(text) { start, size ->
                            dao.getVersionReasoningChunk(entity.id, message.id, version.versionIndex, start, size)
                        }
                    },
                    reasoningSignature = version.reasoningSignature?.let { text ->
                        readToolText(text) { start, size ->
                            dao.getVersionSignatureChunk(entity.id, message.id, version.versionIndex, start, size)
                        }
                    },
                    processingFinishedAt = version.processingFinishedAt,
                    providerId = version.providerId,
                    modelId = version.modelId,
                    modelDisplayName = version.modelDisplayName,
                    generationInterrupted = version.generationInterrupted,
                    generationError = version.generationError?.let { text ->
                        readToolText(text) { start, size ->
                            dao.getVersionErrorChunk(entity.id, message.id, version.versionIndex, start, size)
                        }
                    },
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
            summary = dao.getSummaryForLoad(entity.id)?.let { summary ->
                summary.copy(content = readToolText(summary.content) { start, size ->
                    dao.getSummaryContentChunk(entity.id, start, size)
                }).toModel()
            },
            reasoningEffort = entity.reasoningEffort,
            selectedInstructionIds = entity.selectedInstructionIds?.let(::decodeInstructionIds),
            remoteWorkspaces = runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), entity.remoteWorkspacesJson)
            }.getOrDefault(emptyMap())
        )
    }

    /** Reassemble TEXT directly from SQLite without ever materializing a huge CursorWindow row. */
    private suspend fun readToolText(prefix: String, chunk: suspend (Int, Int) -> String?): String {
        val chunkSize = 16_384 // SQLite substr() counts Unicode code points, not Kotlin UTF-16 units.
        if (prefix.codePointCount(0, prefix.length) < chunkSize) return prefix
        val result = StringBuilder(prefix)
        var start = chunkSize + 1
        while (true) {
            val part = chunk(start, chunkSize).orEmpty()
            if (part.isEmpty()) break
            result.append(part)
            val length = part.codePointCount(0, part.length)
            start += length
            if (length < chunkSize) break
        }
        return result.toString()
    }

    private suspend fun persistChildren(conversation: Conversation, previous: Conversation?, stored: MutableList<StoredPayload>) {
        val previousMessages = previous?.messages?.associateBy { it.id }.orEmpty()
        // With a committed Kotlin snapshot there is no need to re-read Room. A cold
        // import/save reads just the keys, never the large message or tool payloads.
        val oldIds = previous?.messages?.map { it.id } ?: dao.getMessageIds(conversation.id)
        val ids = conversation.messages.map { it.id }
        val oldIdSet = oldIds.toSet()
        if (ids.isEmpty()) {
            if (oldIds.isNotEmpty()) dao.deleteAllMessages(conversation.id)
        } else if (oldIds.any { it !in ids }) {
            dao.deleteMessagesExcept(conversation.id, ids)
        }

        val resequence = oldIds != ids
        if (resequence && oldIds.isNotEmpty()) dao.shiftMessageSequences(conversation.id, 1_000_000)

        conversation.messages.forEachIndexed { sequence, message ->
            val prior = previousMessages[message.id]
            val changed = prior == null || (prior !== message && prior != message)
            if (changed || resequence) {
                val entity = MessageEntity(conversation.id, message.id, sequence, message.role.persistedValue, message.activeVersionIndex)
                if (message.id in oldIdSet) dao.updateMessage(entity) else dao.insertMessage(entity)
            }
            if (changed) persistMessage(conversation.id, message, prior, stored)
        }

        val summary = conversation.summary?.takeIf { it.throughMessageId in ids }?.let {
            ConversationSummaryEntity(conversation.id, it.id, it.content, it.throughMessageId, it.role.name, it.createdAt)
        }
        val oldSummary = previous?.summary?.let {
            ConversationSummaryEntity(conversation.id, it.id, it.content, it.throughMessageId, it.role.name, it.createdAt)
        }
        if (previous == null || oldSummary != summary) {
            if (summary == null) {
                if (previous == null || oldSummary != null) dao.deleteSummary(conversation.id)
            } else if (dao.updateSummary(summary) == 0) {
                dao.insertSummaryIfAbsent(summary)
            }
        }
    }

    private suspend fun upsertConversation(entity: ConversationEntity) {
        if (dao.updateConversation(entity) == 0) dao.insertConversation(entity)
    }

    private suspend fun persistMessage(conversationId: String, message: StoredMessage, previous: StoredMessage?,
        stored: MutableList<StoredPayload>) {
        val oldIndexes = previous?.versions?.indices?.toList()
            ?: dao.getMessageVersionIndexes(conversationId, message.id)
        val indexes = message.versions.indices.toList()
        if (oldIndexes != indexes) {
            if (indexes.isEmpty()) dao.deleteMessageVersions(conversationId, message.id)
            else if (oldIndexes.any { it !in indexes }) dao.deleteMessageVersionsExcept(conversationId, message.id, indexes)
        }

        message.versions.forEachIndexed { versionIndex, version ->
            val old = previous?.versions?.getOrNull(versionIndex)
            if (old === version || (old != null && old == version)) return@forEachIndexed
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
            if (versionIndex in oldIndexes) dao.updateMessageVersion(entity)
            else dao.insertMessageVersion(entity)
            persistVersionFiles(conversationId, message.id, versionIndex, version.files, old?.files)
            persistActivities(conversationId, message.id, versionIndex, version.toolActivity, old?.toolActivity, stored)
        }
    }

    private suspend fun persistVersionFiles(conversationId: String, messageId: String, versionIndex: Int,
        references: List<String>, previous: List<String>?) {
        val old = previous ?: dao.getMessageVersionFiles(conversationId, messageId, versionIndex).map { it.reference }
        if (old == references) return
        if (references.isEmpty()) dao.deleteMessageVersionFiles(conversationId, messageId, versionIndex)
        else if (old.size > references.size) dao.deleteMessageVersionFilesExcept(conversationId, messageId, versionIndex, references.indices.toList())
        dao.insertMessageVersionFiles(references.mapIndexed { fileIndex, reference ->
            MessageVersionFileEntity(conversationId, messageId, versionIndex, fileIndex, reference)
        })
    }

    private suspend fun persistActivities(conversationId: String, messageId: String, versionIndex: Int,
        activities: List<ToolActivity>, previous: List<ToolActivity>?, stored: MutableList<StoredPayload>) {
        val oldIndexes = previous?.indices?.toList()
            ?: dao.getToolActivityIndexes(conversationId, messageId, versionIndex)
        val indexes = activities.indices.toList()
        if (oldIndexes != indexes) {
            if (indexes.isEmpty()) dao.deleteToolActivities(conversationId, messageId, versionIndex)
            else if (oldIndexes.any { it !in indexes }) dao.deleteToolActivitiesExcept(conversationId, messageId, versionIndex, indexes)
        }
        activities.forEachIndexed { activityIndex, activity ->
            val old = previous?.getOrNull(activityIndex)
            val keys = payloadKeys(conversationId, messageId, versionIndex, activityIndex, activity)
            if (old === activity || (old != null && old == activity)) {
                // Already stored: this instance only needs to learn where its payloads are.
                stored += keys.pending(activity)
                return@forEachIndexed
            }
            val rowExists = activityIndex in oldIndexes
            if (rowExists && keys.inPlace(activity)) {
                // The payloads are already in this row; skip rewriting (and reading) them.
                val updated = dao.updateToolActivityMetadata(conversationId, messageId, versionIndex, activityIndex,
                    activity.id, activity.name, activity.wireName, activity.status, activity.summary,
                    activity.contentOffset, activity.reasoningOffset, activity.inlineMedia,
                    activity.responsePreviewLimit, activity.actionGroup)
                check(updated == 1) { "Tool activity ${activity.id} is missing from storage" }
            } else {
                // pinMovedPayloads() has already made every payload that needs writing resident.
                val entity = ToolActivityEntity(
                    conversationId, messageId, versionIndex, activityIndex, activity.id, activity.name,
                    activity.wireName, activity.status, activity.summary,
                    activity.arguments.residentText(), activity.response.residentText(),
                    activity.contentOffset, activity.reasoningOffset, activity.inlineMedia, activity.responsePreviewLimit,
                    activity.actionGroup
                )
                if (rowExists) dao.updateToolActivity(entity) else dao.insertToolActivity(entity)
            }
            persistActivityFiles(conversationId, messageId, versionIndex, activityIndex, activity.files, old?.files)
            stored += keys.pending(activity)
        }
    }

    private inner class PayloadKeys(val arguments: ToolPayloadKey, val response: ToolPayloadKey) {
        fun inPlace(activity: ToolActivity): Boolean =
            activity.arguments.isStoredAt(arguments) && activity.response.isStoredAt(response)

        /** The payloads that will need to be told where they were stored once the commit succeeds. */
        fun pending(activity: ToolActivity): List<StoredPayload> = buildList {
            if (!activity.arguments.isEmpty) add(StoredPayload(activity.arguments, arguments))
            if (!activity.response.isEmpty) add(StoredPayload(activity.response, response))
        }
    }

    private fun ToolPayload.isStoredAt(key: ToolPayloadKey): Boolean = isEmpty || sourceKey == key

    private fun payloadKeys(conversationId: String, messageId: String, versionIndex: Int, activityIndex: Int,
        activity: ToolActivity) = PayloadKeys(
        ToolPayloadKey(conversationId, messageId, versionIndex, activityIndex, activity.id,
            ToolPayloadField.ARGUMENTS, activity.arguments.size),
        ToolPayloadKey(conversationId, messageId, versionIndex, activityIndex, activity.id,
            ToolPayloadField.RESPONSE, activity.response.size)
    )

    /**
     * Brings back into RAM every payload this commit has to write that is not already in its target
     * row. Such a payload can only be recovered from a row (an earlier position or version) that the
     * same commit may delete, so it must be read before anything is deleted or overwritten.
     */
    private suspend fun pinMovedPayloads(conversation: Conversation, previous: Conversation?) {
        val before = previous?.messages?.associateBy { it.id }.orEmpty()
        conversation.messages.forEach { message ->
            val prior = before[message.id]
            if (prior != null && (prior === message || prior == message)) return@forEach
            message.versions.forEachIndexed { versionIndex, version ->
                val oldVersion = prior?.versions?.getOrNull(versionIndex)
                if (oldVersion != null && (oldVersion === version || oldVersion == version)) return@forEachIndexed
                version.toolActivity.forEachIndexed { activityIndex, activity ->
                    val oldActivity = oldVersion?.toolActivity?.getOrNull(activityIndex)
                    if (oldActivity != null && (oldActivity === activity || oldActivity == activity)) return@forEachIndexed
                    val keys = payloadKeys(conversation.id, message.id, versionIndex, activityIndex, activity)
                    if (keys.inPlace(activity)) return@forEachIndexed
                    activity.arguments.pin()
                    activity.response.pin()
                }
            }
        }
    }

    /** A payload still in storage: only a short value is kept, a long one is read on demand. */
    private fun storedPayload(versionIndex: Int, row: ToolActivityEntity, field: ToolPayloadField,
        prefix: String, size: Int): ToolPayload {
        if (size == 0) return ToolPayload.EMPTY
        val source = RoomToolPayloadSource(db, ToolPayloadKey(row.conversationId, row.messageId, versionIndex,
            row.activityIndex, row.activityId, field, size))
        return if (size <= ToolPayload.SMALL_PAYLOAD_CHARS) ToolPayload.stored(prefix, source)
            else ToolPayload.stored(size, source)
    }

    private suspend fun persistActivityFiles(conversationId: String, messageId: String, versionIndex: Int,
        activityIndex: Int, references: List<String>, previous: List<String>?) {
        val old = previous ?: dao.getToolActivityFiles(conversationId, messageId, versionIndex, activityIndex).map { it.reference }
        if (old == references) return
        if (references.isEmpty()) dao.deleteToolActivityFiles(conversationId, messageId, versionIndex, activityIndex)
        else if (old.size > references.size) dao.deleteToolActivityFilesExcept(conversationId, messageId, versionIndex, activityIndex, references.indices.toList())
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
