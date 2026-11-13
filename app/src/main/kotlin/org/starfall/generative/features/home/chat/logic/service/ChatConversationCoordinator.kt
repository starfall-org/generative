package org.starfall.generative.features.home.chat.logic.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.starfall.generative.features.home.chat.logic.ChatGeneration
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.subfeatures.media.logic.uploadedFileNames
import org.starfall.generative.shared.subfeatures.media.logic.generatedFileNames
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.shared.state.LocalWriteErrors
import org.starfall.generative.shared.model.preferences.AppPreferences
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.shared.model.preferences.InstructionSelectionMode
import org.starfall.generative.features.home.chat.logic.markdown.PersistentMarkdownAstCache

/** Coordinates conversation selection, history mutations, queued sends and persistence ordering. */
internal class ChatConversationCoordinator(
    private val scope: CoroutineScope,
    private val conversationRepo: ConversationRepository,
    private val prefsRepo: AppPreferencesRepository,
    private val toolFiles: ToolFiles,
    private val currentConversation: MutableStateFlow<Conversation?>,
    private val queuedMessagesByConversation: MutableStateFlow<Map<String, List<StoredMessage>>>,
    private val deletingConversations: MutableStateFlow<Set<String>>,
    private val renamingConversations: MutableStateFlow<Map<String, String>>,
    private val deletingAllConversations: MutableStateFlow<Boolean>,
    private val appPreferences: StateFlow<AppPreferences>,
    private val existingGeneration: (String) -> ChatGeneration?,
    private val generationsFor: (Set<String>) -> List<ChatGeneration>,
    private val allGenerations: () -> List<ChatGeneration>,
    private val providerForConversation: (Conversation) -> ModelProvider?,
    private val disconnectRemoteMachines: () -> Unit,
    private val summaryConversationId: () -> String?,
    private val cancelSummary: suspend () -> Unit,
    private val cancelTitles: suspend () -> Unit = {},
    private val cancelTitlesFor: suspend (Set<String>) -> Unit = {},
    private val releaseTemporaryGeneration: (String) -> Unit = {},
    private val maybeStartAutomaticSummary: () -> Boolean,
    private val isGenerationReplacementActive: () -> Boolean,
    private val isSummaryActive: () -> Boolean,
    private val sendMessage: (String, List<String>, Boolean, String?) -> Boolean,
) {
    private var conversationSelectionToken = 0L
    private val _loadingConversationId = MutableStateFlow<String?>(null)
    val loadingConversationId: StateFlow<String?> = _loadingConversationId
    @Volatile private var pendingWrites = 0
    @Volatile private var activeAdmissions = 0
    @Volatile private var restoreInProgress = false
    private val writeStateLock = Any()
    private val activeOperationsFlow = MutableStateFlow(0)
    private val serializedWrites = Mutex()

    val pendingConversationWrites: Int get() = pendingWrites
    val isRestoreInProgress: Boolean get() = restoreInProgress

    val queuedMessages: StateFlow<List<StoredMessage>> = combine(
        currentConversation,
        queuedMessagesByConversation
    ) { conversation, queued ->
        conversation?.id?.let { queued[it].orEmpty() }.orEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Atomically rejects synchronous chat mutations and generation starts once [beginRestore] has
     * begun, and keeps [beginRestore] waiting until an admitted start registers its background work.
     */
    inline fun <T> withRestoreGuard(onRejected: () -> T, block: () -> T): T {
        if (!tryAcquireAdmission()) return onRejected()
        return try {
            block()
        } finally {
            releaseAdmission()
        }
    }

    @PublishedApi
    internal fun tryAcquireAdmission(): Boolean = synchronized(writeStateLock) {
        if (restoreInProgress) {
            false
        } else {
            activeAdmissions++
            activeOperationsFlow.value = pendingWrites + activeAdmissions
            true
        }
    }

    @PublishedApi
    internal fun releaseAdmission() {
        synchronized(writeStateLock) {
            activeAdmissions--
            activeOperationsFlow.value = pendingWrites + activeAdmissions
        }
    }

    fun enqueueMessage(conversationId: String, message: StoredMessage) = withRestoreGuard(onRejected = {}) {
        queuedMessagesByConversation.update { queued ->
            queued + (conversationId to queued[conversationId].orEmpty() + message)
        }
    }

    /** Atomically hands one queued message to the active tool loop for a conversation. */
    fun takePendingMessage(conversationId: String): StoredMessage? {
        var next: StoredMessage? = null
        queuedMessagesByConversation.update { queuedByConversation ->
            val queued = queuedByConversation[conversationId].orEmpty()
            next = queued.firstOrNull()
            queuedByConversation + (conversationId to queued.drop(1))
        }
        return next
    }

    fun editQueuedMessage(id: String, newContent: String, files: List<String>): Boolean =
        withRestoreGuard(onRejected = { false }) {
            if ((newContent.isBlank() && files.isEmpty()) || queuedMessagesByConversation.value.values.none { queued ->
                    queued.any { it.id == id }
                }) return@withRestoreGuard false
            queuedMessagesByConversation.update { queuedByConversation ->
                queuedByConversation.mapValues { (_, queued) -> queued.map { message ->
                    if (message.id == id) {
                        message.copy(versions = listOf(MessageVersion(
                            content = newContent,
                            timestamp = System.currentTimeMillis().toString(),
                            files = files
                        )))
                    } else message
                } }
            }
            true
        }

    fun deleteQueuedMessage(id: String) = withRestoreGuard(onRejected = {}) {
        queuedMessagesByConversation.update { queuedByConversation ->
            queuedByConversation.mapValues { (_, queued) -> queued.filterNot { it.id == id } }
        }
    }

    fun drainQueuedMessages() {
        if (restoreInProgress) return
        val conversationId = currentConversation.value?.id ?: return
        if (existingGeneration(conversationId)?.busy?.value == true || isGenerationReplacementActive() ||
            isSummaryActive() || pendingWrites > 0) return
        if (maybeStartAutomaticSummary()) return
        var next: StoredMessage? = null
        queuedMessagesByConversation.update { queuedByConversation ->
            val queued = queuedByConversation[conversationId].orEmpty()
            next = queued.firstOrNull()
            queuedByConversation + (conversationId to queued.drop(1))
        }
        next?.let {
            sendMessage(it.content, it.files, it.activeVersion.createSkillRequested, it.activeVersion.selectedSkillName)
        }
    }

    fun writeConversation(
        serialize: Boolean = false,
        beforeLaunch: () -> Unit = {},
        block: suspend () -> Unit
    ): Boolean {
        val accepted = synchronized(writeStateLock) {
            if (restoreInProgress) {
                false
            } else {
                pendingWrites++
                activeOperationsFlow.value = pendingWrites + activeAdmissions
                true
            }
        }
        if (!accepted) return false

        try {
            beforeLaunch()
        } catch (error: Throwable) {
            completeWrite()
            throw error
        }

        scope.launch(LocalWriteErrors.handler) {
            try {
                if (serialize) serializedWrites.withLock { block() } else block()
            } finally {
                completeWrite()
                drainQueuedMessages()
            }
        }
        return true
    }

    private fun completeWrite() {
        synchronized(writeStateLock) {
            pendingWrites--
            activeOperationsFlow.value = pendingWrites + activeAdmissions
        }
    }

    /** Blocks new conversation actions/writes and waits for all admitted work to stop and drain. */
    suspend fun beginRestore() {
        synchronized(writeStateLock) {
            check(!restoreInProgress) { "A full restore is already in progress." }
            restoreInProgress = true
        }
        try {
            activeOperationsFlow.first { it == 0 }
            serializedWrites.withLock {
                conversationSelectionToken++
                _loadingConversationId.value = null
                cancelTitles()
                cancelSummary()
                allGenerations().forEach { it.stopAndJoin() }
            }
            activeOperationsFlow.first { it == 0 }
        } catch (error: Throwable) {
            endRestore()
            throw error
        }
    }

    /** Re-enables conversation writes after a restore has either committed or rolled back. */
    fun endRestore() {
        synchronized(writeStateLock) { restoreInProgress = false }
        drainQueuedMessages()
    }

    fun saveEditedConversation(previous: Conversation, updated: Conversation): Boolean {
        val accepted = writeConversation(beforeLaunch = { currentConversation.value = updated }) {
            try {
                conversationRepo.saveConversation(updated)
            } catch (error: Exception) {
                if (currentConversation.value == updated) {
                    val restored = conversationRepo.loadById(previous.id) ?: previous
                    currentConversation.value = restored
                    PersistentMarkdownAstCache.authorizeConversation(restored.id, restored.messages.map { it.id })
                }
                throw error
            }
        }
        if (!accepted && currentConversation.value == updated) {
            currentConversation.value = previous
            PersistentMarkdownAstCache.authorizeConversation(previous.id, previous.messages.map { it.id })
        }
        return accepted
    }

    fun restoreLastConversation() {
        if (restoreInProgress) return
        // A restore must be able to invalidate a history load that was already in flight.
        val requestToken = conversationSelectionToken
        scope.launch {
            val preferences = prefsRepo.appPreferencesFlow.first()
            if (restoreInProgress || !preferences.continueLastConversation || currentConversation.value != null) {
                return@launch
            }

            val storedId = preferences.chatRelations.lastActiveConversationId
            // An empty ID means the previous session was on a blank New Chat; null is a legacy install.
            if (storedId == "") return@launch
            val items = conversationRepo.conversationList.first()
            val lastConversation = if (storedId != null) {
                items.firstOrNull { it.id == storedId && it.hasMessages && !it.hasMigrationIssue }
            } else {
                items.asSequence().filter { it.hasMessages && !it.hasMigrationIssue }
                    .maxWithOrNull(compareBy<ConversationListItem> { it.updatedAt }.thenBy { it.createdAt })
            } ?: return@launch

            // A selection or restore boundary made while Room was loading always wins.
            if (!restoreInProgress && requestToken == conversationSelectionToken &&
                currentConversation.value == null && !deletingAllConversations.value &&
                lastConversation.id !in deletingConversations.value
            ) selectConversation(lastConversation.id)
        }
    }

    /** Compatibility overload for callers that already have the complete in-memory conversation. */
    fun selectConversation(conversation: Conversation) = withRestoreGuard(onRejected = {}) {
        if (deletingAllConversations.value || conversation.id in deletingConversations.value) return@withRestoreGuard
        conversationSelectionToken++
        _loadingConversationId.value = null
        applySelectedConversation(conversation)
    }

    fun selectConversation(conversationId: String) = withRestoreGuard(onRejected = {}) {
        if (deletingAllConversations.value || conversationId in deletingConversations.value) return@withRestoreGuard
        val request = ++conversationSelectionToken
        // Re-selecting the displayed chat has no history to fetch.
        if (currentConversation.value?.id == conversationId) {
            _loadingConversationId.value = null
            return@withRestoreGuard
        }
        // Active generation or a failed final save still owns the newest complete snapshot.
        val live = existingGeneration(conversationId)?.snapshot
        if (live != null) {
            _loadingConversationId.value = null
            applySelectedConversation(live)
            return@withRestoreGuard
        }
        // Publish before launching IO, even if the current chat is null or has no messages.
        _loadingConversationId.value = conversationId
        scope.launch {
            try {
                val conversation = conversationRepo.loadById(conversationId) ?: return@launch
                // A slow history read must not replace a newer selection or cross a restore boundary.
                withRestoreGuard(onRejected = {}) {
                    if (request != conversationSelectionToken || deletingAllConversations.value ||
                        conversationId in deletingConversations.value
                    ) return@withRestoreGuard
                    applySelectedConversation(conversation)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                org.starfall.generative.shared.subfeatures.logging.logic.DebugLog.error(
                    org.starfall.generative.shared.model.logging.DebugLogCategory.SYSTEM,
                    "Conversation load", "Failed to read conversation $conversationId", error
                )
                if (request == conversationSelectionToken) {
                    org.starfall.generative.shared.state.LocalChatReadErrors.report()
                }
            } finally {
                if (request == conversationSelectionToken) _loadingConversationId.value = null
            }
        }
    }

    private fun applySelectedConversation(conversation: Conversation) {
        discardTemporaryOnExit(conversation.id)
        val selected = existingGeneration(conversation.id)?.snapshot ?: conversation
        PersistentMarkdownAstCache.authorizeConversation(selected.id, selected.messages.map { it.id })
        if (selected.id != currentConversation.value?.id) disconnectRemoteMachines()
        currentConversation.value = selected
        scope.launch(LocalWriteErrors.handler) {
            if (restoreInProgress) return@launch
            prefsRepo.setLastActiveConversationId(selected.id)
            if (restoreInProgress || !appPreferences.value.persistSelectedModel) return@launch
            providerForConversation(selected)?.let { provider ->
                prefsRepo.setSelectedModel(provider.id, selected.modelId)
            }
        }
        drainQueuedMessages()
    }

    fun startNewChat() = startBlankChat(temporary = false)

    fun startTemporaryChat() = startBlankChat(temporary = true)

    /** Cancel a temporary response before navigating away; discard cache files after its jobs finish. */
    private fun discardTemporaryOnExit(nextId: String) {
        val old = currentConversation.value?.takeIf { it.isTemporary && it.id != nextId } ?: return
        existingGeneration(old.id)?.stop()
        queuedMessagesByConversation.update { it - old.id }
        scope.launch(LocalWriteErrors.handler) {
            try {
                cancelTitlesFor(setOf(old.id))
                if (summaryConversationId() == old.id) cancelSummary()
            } finally {
                // Cleanup must finish even if an active request is being cancelled.
                withContext(kotlinx.coroutines.NonCancellable) { disposeTemporarySession(old.id) }
            }
        }
    }

    private suspend fun disposeTemporarySession(id: String) {
        generationsFor(setOf(id)).forEach { it.stopAndJoin() }
        releaseTemporaryGeneration(id)
        toolFiles.closeTemporarySession(id)
        PersistentMarkdownAstCache.invalidateConversation(id)
    }

    private fun startBlankChat(temporary: Boolean) = withRestoreGuard(onRejected = {}) {
        // A selection read started earlier must not replace this blank draft when it completes.
        conversationSelectionToken++
        _loadingConversationId.value = null
        discardTemporaryOnExit("")
        disconnectRemoteMachines()
        val now = System.currentTimeMillis()
        val id = java.util.UUID.randomUUID().toString()
        if (temporary) toolFiles.openTemporarySession(id)
        currentConversation.value = Conversation(
            id = id,
            title = if (temporary) "Temporary Chat" else "New Chat",
            createdAt = now,
            updatedAt = now,
            isTemporary = temporary
        )
        scope.launch(LocalWriteErrors.handler) {
            if (!restoreInProgress) prefsRepo.setLastActiveConversationId(null)
        }
    }

    /** Toggle at the selected scope; folder/global changes must not overwrite per-chat snapshots. */
    fun setInstructionEnabled(instructionId: String, enabled: Boolean) = withRestoreGuard(onRejected = {}) {
        val current = currentConversation.value
        val preferences = appPreferences.value
        val library = preferences.instructionLibraryFor(current)
        if (library.instructions.none { it.id == instructionId }) return@withRestoreGuard
        val selected = if (enabled) library.selectedIds + instructionId else library.selectedIds - instructionId

        when {
            preferences.usesFolderInstructions(current?.id) -> {
                val folderKey = preferences.instructionFolderKey(current?.id)
                scope.launch(LocalWriteErrors.handler) {
                    if (!restoreInProgress) prefsRepo.setFolderInstructionSelection(folderKey, selected)
                }
            }
            preferences.instructionSelectionMode == InstructionSelectionMode.GLOBAL -> {
                scope.launch(LocalWriteErrors.handler) {
                    if (!restoreInProgress) prefsRepo.setInstructionLibrary(preferences.instructionLibrary.copy(selectedIds = selected))
                }
            }
            else -> {
                val updated = current?.copy(selectedInstructionIds = selected, updatedAt = System.currentTimeMillis())
                    ?: return@withRestoreGuard
                // A generating conversation owns a live snapshot which must be updated instead of a stale copy.
                val generation = existingGeneration(updated.id)?.takeIf { it.snapshot?.id == updated.id }
                if (generation != null) {
                    generation.updateConversation {
                        it.copy(selectedInstructionIds = selected, updatedAt = updated.updatedAt)
                    }
                } else if (updated.messages.isNotEmpty()) {
                    if (!writeConversation(beforeLaunch = { currentConversation.value = updated }) {
                            conversationRepo.saveConversation(updated)
                        }
                    ) return@withRestoreGuard
                } else {
                    currentConversation.value = updated
                }
                if (preferences.instructionSelectionMode == InstructionSelectionMode.PER_CHAT_REMEMBER_GLOBAL) {
                    scope.launch(LocalWriteErrors.handler) {
                        if (!restoreInProgress) prefsRepo.setInstructionLibrary(preferences.instructionLibrary.copy(selectedIds = selected))
                    }
                }
            }
        }
    }

    fun deleteConversation(id: String) = deleteConversations(setOf(id))

    /** Deletes history and only the uploaded files no remaining conversation or input still uses. */
    fun deleteConversations(ids: Set<String>, attachedFiles: Collection<String> = emptyList()) {
        val targets = ids - deletingConversations.value
        if (targets.isEmpty()) return
        var previous: Conversation? = null
        writeConversation(
            serialize = true,
            beforeLaunch = {
                targets.forEach(PersistentMarkdownAstCache::invalidateConversation)
                previous = currentConversation.value?.takeIf { it.id in targets }
                deletingConversations.update { it + targets }
                if (_loadingConversationId.value in targets) {
                    conversationSelectionToken++
                    _loadingConversationId.value = null
                }
                if (previous != null) {
                    currentConversation.value = null
                    queuedMessagesByConversation.update { it - targets }
                    scope.launch(LocalWriteErrors.handler) {
                        if (!restoreInProgress) prefsRepo.setLastActiveConversationId(null)
                    }
                }
            }
        ) {
            var deleted = false
            try {
                cancelTitlesFor(targets)
                if (summaryConversationId() in targets) cancelSummary()
                generationsFor(targets).forEach { it.stopAndJoin() }
                // Relational file references remain readable when a quarantined chat cannot be decoded.
                val uploads = uploadsForDeletion(previous)
                conversationRepo.deleteConversations(targets.toList())
                generationsFor(targets).forEach { it.discardFinalSnapshot() }
                deleted = true
                prefsRepo.updateSidebar { it.removeChats(targets) }
                deleteOrphanedUploads(uploads, attachedFiles, removedConversationIds = targets)
            } catch (error: Exception) {
                val previousConversation = previous
                if (!deleted && currentConversation.value == null && previousConversation != null) {
                    currentConversation.value = previousConversation
                    PersistentMarkdownAstCache.authorizeConversation(
                        previousConversation.id,
                        previousConversation.messages.map { it.id }
                    )
                }
                throw error
            } finally {
                deletingConversations.update { it - targets }
            }
        }
    }

    private suspend fun uploadsForDeletion(previous: Conversation?): Set<String> {
        // Record the provenance *before* deleting the database rows. A generated item may later
        // be attached as a user message in another chat, long after its original chat is gone.
        val generated = conversationRepo.generatedFileReferences().mapNotNull(toolFiles::nameOf).toSet() +
            previous?.takeUnless { it.isTemporary }?.generatedFileNames(toolFiles).orEmpty()
        toolFiles.markGeneratedMedia(generated)
        val uploaded = localChatUploadNames(toolFiles, conversationRepo.userFileReferences()) +
            previous?.takeUnless { it.isTemporary }?.uploadedFileNames(toolFiles).orEmpty()
        return uploaded - generated
    }

    private suspend fun deleteOrphanedUploads(
        candidates: Set<String>,
        attachedFiles: Collection<String>,
        removedConversationIds: Set<String>
    ) {
        if (candidates.isEmpty()) return
        val queued = queuedMessagesByConversation.value.filterKeys { it !in removedConversationIds }.values
            .flatten().flatMap { message -> message.versions.flatMap { it.files } }
        deleteUnreferencedChatUploads(
            conversationRepo, toolFiles, candidates,
            attachedFiles + queued + conversationRepo.userFileReferences()
        )
    }

    fun renameConversation(id: String, newTitle: String) {
        var previous: Conversation? = null
        writeConversation(
            serialize = true,
            beforeLaunch = {
                previous = currentConversation.value?.takeIf { it.id == id }
                renamingConversations.update { it + (id to newTitle) }
                previous?.let { currentConversation.value = it.copy(title = newTitle) }
            }
        ) {
            try {
                cancelTitlesFor(setOf(id))
                existingGeneration(id)?.stopAndJoin()
                conversationRepo.renameConversation(id, newTitle)
            } catch (error: Exception) {
                if (currentConversation.value?.id == id && currentConversation.value?.title == newTitle) {
                    previous?.let { currentConversation.value = currentConversation.value?.copy(title = it.title) }
                }
                throw error
            } finally {
                renamingConversations.update { if (it[id] == newTitle) it - id else it }
            }
        }
    }

    fun clearAllConversations(attachedFiles: Collection<String> = emptyList()): Boolean {
        if (deletingAllConversations.value) return false
        var previous: Conversation? = null
        return writeConversation(
            serialize = true,
            beforeLaunch = {
                PersistentMarkdownAstCache.invalidateAll()
                previous = currentConversation.value
                deletingAllConversations.value = true
                conversationSelectionToken++
                _loadingConversationId.value = null
                currentConversation.value = null
                queuedMessagesByConversation.value = emptyMap()
                scope.launch(LocalWriteErrors.handler) {
                    if (!restoreInProgress) prefsRepo.setLastActiveConversationId(null)
                }
            }
        ) {
            var deleted = false
            try {
                cancelTitles()
                cancelSummary()
                allGenerations().forEach { it.stopAndJoin() }
                previous?.takeIf { it.isTemporary }?.let { disposeTemporarySession(it.id) }
                val uploads = uploadsForDeletion(previous)
                conversationRepo.deleteAll()
                allGenerations().forEach { it.discardFinalSnapshot() }
                deleted = true
                prefsRepo.updateSidebar { SidebarOrganization() }
                deleteOrphanedUploads(uploads, attachedFiles, removedConversationIds = emptySet())
            } catch (error: Exception) {
                if (!deleted && currentConversation.value == null) {
                    currentConversation.value = previous
                    previous?.let { PersistentMarkdownAstCache.authorizeConversation(it.id, it.messages.map { message -> message.id }) }
                }
                throw error
            } finally {
                deletingAllConversations.value = false
            }
        }
    }

    suspend fun importConversations(items: List<Conversation>) {
        items.forEach { conversationRepo.saveConversation(it) }
    }

    suspend fun resetAfterRestore() {
        serializedWrites.withLock {
            // Invalidate any selection load that began before the restore boundary.
            conversationSelectionToken++
            _loadingConversationId.value = null
            cancelTitles()
            cancelSummary()
            allGenerations().forEach { it.stopAndJoin() }
            allGenerations().forEach { it.discardFinalSnapshot() }
            currentConversation.value?.takeIf { it.isTemporary }?.let { disposeTemporarySession(it.id) }
            currentConversation.value = null
            queuedMessagesByConversation.value = emptyMap()
            deletingConversations.value = emptySet()
            renamingConversations.value = emptyMap()
            deletingAllConversations.value = false
        }
    }

    /** Full-history reads happen only at the explicit export boundary, not in sidebar state. */
    suspend fun conversationsForExport(): List<Conversation> =
        conversationRepo.conversationList.first().mapNotNull { conversationRepo.loadById(it.id) }

    fun deleteAllUserData(attachedFiles: Collection<String> = emptyList()) {
        clearAllConversations(attachedFiles)
    }
}
