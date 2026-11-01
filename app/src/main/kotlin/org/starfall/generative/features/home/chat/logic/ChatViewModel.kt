package org.starfall.generative.features.home.chat.logic


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.media.SpeechAudioPlayer
import org.starfall.generative.shared.media.SpeechSynthesisService
import org.starfall.generative.shared.media.TtsHelper
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.DEFAULT_TITLE_GENERATION_MIN_MESSAGES
import org.starfall.generative.shared.model.DEFAULT_TITLE_GENERATION_MIN_WORDS
import org.starfall.generative.shared.model.MIN_TITLE_GENERATION_MIN_MESSAGES
import org.starfall.generative.shared.model.MIN_TITLE_GENERATION_MIN_WORDS
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.SummaryRole
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.database.ConversationRepository
import org.starfall.generative.shared.storage.LocalWriteErrors
import org.starfall.generative.database.McpRepository
import org.starfall.generative.database.ModelRepository
import org.starfall.generative.database.SpeechRepository
import org.starfall.generative.shared.storage.preferences.AppPreferences
import org.starfall.generative.shared.storage.preferences.AppPreferencesRepository
import org.starfall.generative.shared.storage.preferences.ToolSettingsStore
import org.starfall.generative.features.home.chat.logic.service.AutomaticConversationSummaryCoordinator
import org.starfall.generative.features.home.chat.logic.service.ChatConversationCoordinator
import org.starfall.generative.features.home.chat.logic.service.ChatMessageActions
import org.starfall.generative.features.home.chat.logic.service.ChatMessageRetryActions
import org.starfall.generative.features.home.chat.logic.service.ChatModelContextCoordinator
import org.starfall.generative.features.home.chat.logic.service.ChatToolSettingsActions
import org.starfall.generative.features.home.chat.logic.service.ConversationTitleGenerator
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.features.home.chat.logic.input.DirectMediaRequest
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.features.home.chat.model.ContextWindowStatus
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState

internal fun shouldGenerateConversationTitle(
    userText: String,
    messageCount: Int,
    minimumWords: Int = DEFAULT_TITLE_GENERATION_MIN_WORDS,
    minimumMessages: Int = DEFAULT_TITLE_GENERATION_MIN_MESSAGES
): Boolean {
    val wordTrigger = minimumWords > MIN_TITLE_GENERATION_MIN_WORDS &&
        userText.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= minimumWords
    val messageTrigger = messageCount >= minimumMessages.coerceAtLeast(MIN_TITLE_GENERATION_MIN_MESSAGES)
    return wordTrigger || messageTrigger
}

internal fun Conversation.startedWithDirectMedia(): Boolean =
    messages.firstOrNull { it.role == ChatRole.MODEL }?.activeVersion?.toolActivity?.any {
        it.name == "generate_image" || it.name == "generate_video"
    } == true

private fun Conversation.asConversationListItem() = ConversationListItem(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    tokenCount = tokenCount,
    providerId = providerId,
    modelId = modelId,
    profileId = profileId,
    reasoningEffort = reasoningEffort,
    hasMessages = messages.isNotEmpty(),
    hasSummary = summary != null
)

/** Composes chat UI state; workflows are delegated to focused services under `logic/service/`. */
class ChatViewModel(
    private val conversationRepo: ConversationRepository,
    private val modelRepo: ModelRepository,
    private val mcpRepo: McpRepository,
    private val prefsRepo: AppPreferencesRepository,
    private val toolChat: ToolChatViewModel,
    private val toolStore: ToolSettingsStore,
    private val speechRepo: SpeechRepository,
    private val ttsHelper: TtsHelper,
    private val speechSynthesis: SpeechSynthesisService,
    private val speechAudioPlayer: SpeechAudioPlayer,
    private val toolFiles: ToolFiles,
    private val speechNotification: org.starfall.generative.shared.media.SpeechPlaybackNotification? = null,
) : ViewModel() {

    val toolSettings = toolStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ToolSettings())
    private val toolSettingsActions = ChatToolSettingsActions(viewModelScope, toolStore)
    fun setSystemTool(name: String, config: SystemToolConfig) = toolSettingsActions.setSystemTool(name, config)
    fun setSendFileEnabled(enabled: Boolean) = toolSettingsActions.setSendFileEnabled(enabled)
    fun setSearchToolsEnabled(enabled: Boolean) = toolSettingsActions.setSearchToolsEnabled(enabled)
    fun setAgentSkillsSearchEnabled(enabled: Boolean) = toolSettingsActions.setAgentSkillsSearchEnabled(enabled)
    fun setQuickMcp(id: String, enabled: Boolean) = toolSettingsActions.setQuickMcp(id, enabled)
    fun setWebSearchProvider(id: String?) = toolSettingsActions.setWebSearchProvider(id)
    fun setWebSearchEnabled(enabled: Boolean) = toolSettingsActions.setWebSearchEnabled(enabled)
    fun setMcpToolEnabled(serverId: String, toolName: String, enabled: Boolean) =
        toolSettingsActions.setMcpToolEnabled(serverId, toolName, enabled)
    fun setMcpToolParameter(
        serverId: String,
        toolName: String,
        parameterName: String,
        config: org.starfall.generative.shared.model.McpToolParameterConfig,
    ) = toolSettingsActions.setMcpToolParameter(serverId, toolName, parameterName, config)
    fun setMachinesEnabled(enabled: Boolean) = toolSettingsActions.setMachinesEnabled(enabled)
    fun setMachineEnabled(machineId: String, enabled: Boolean, knownMachineIds: List<String> = emptyList()) =
        toolSettingsActions.setMachineEnabled(machineId, enabled, knownMachineIds)
    fun selectRemoteMachine(machineId: String?, knownMachineIds: List<String>) =
        toolSettingsActions.selectMachine(machineId, knownMachineIds)

    fun setMachineToolEnabled(machineId: String, toolName: String, enabled: Boolean) =
        toolSettingsActions.setMachineToolEnabled(machineId, toolName, enabled)

    private fun toolEvents(
        provider: ModelProvider,
        model: String,
        messages: List<StoredMessage>,
        prompt: String,
        conversationId: String,
        createSkillRequested: Boolean
    ) =
        toolChat.generate(provider, model, messages, prompt, mcpServers.value, providers.value,
            settings = { toolSettings.value },
            maxToolRounds = { appPreferences.value.effectiveToolRoundLimit },
            takePendingMessage = { takePendingMessage(conversationId) },
            mcpAttachmentSettings = { appPreferences.value.mcpAttachments },
            createSkillRequested = createSkillRequested)

    private val deletingAllConversations = MutableStateFlow(false)
    private val deletingConversations = MutableStateFlow<Set<String>>(emptySet())
    private val renamingConversations = MutableStateFlow<Map<String, String>>(emptyMap())
    // New conversations have no database row until their first AI block finishes. Show their
    // metadata in the drawer from RAM, without persisting an unfinished response.
    private val _liveConversationPreviews = MutableStateFlow<Map<String, ConversationListItem>>(emptyMap())
    val conversations: StateFlow<List<ConversationListItem>> = combine(
        conversationRepo.conversationList.onEach { persisted ->
            val ids = persisted.mapTo(mutableSetOf()) { it.id }
            _liveConversationPreviews.update { previews -> previews - ids }
        },
        _liveConversationPreviews, deletingConversations, renamingConversations, deletingAllConversations
    ) { persisted, previews, deleted, renamed, deletingAll ->
        if (deletingAll) emptyList() else {
            val persistedIds = persisted.mapTo(mutableSetOf()) { it.id }
            (persisted + previews.values.filterNot { it.id in persistedIds })
                .filterNot { it.id in deleted }
                .map { item -> renamed[item.id]?.let { item.copy(title = it) } ?: item }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val providers: StateFlow<List<ModelProvider>> = modelRepo.allProviders
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mcpServers: StateFlow<List<McpInfo>> = mcpRepo.allServers
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val speechServices: StateFlow<List<SpeechService>> = speechRepo.allServices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val appPreferences: StateFlow<AppPreferences> = prefsRepo.appPreferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()
    private val _activeStreams = MutableStateFlow<Map<String, ActiveChatStreamState>>(emptyMap())
    val activeStreamForCurrentConversation: StateFlow<ActiveChatStreamState?> = combine(
        _currentConversation, _activeStreams
    ) { conversation, streams -> conversation?.id?.let(streams::get) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class GenerationUiState(
        val busy: Boolean = false,
        val error: String? = null,
        val waitingForTool: Boolean = false
    )

    /** Each conversation owns its stream so changing chats never changes another chat's controls. */
    private val generations = mutableMapOf<String, ChatGeneration>()
    private val generationObservers = mutableMapOf<String, Job>()
    private val generationLock = Any()
    private val _generationStates = MutableStateFlow<Map<String, GenerationUiState>>(emptyMap())

    private fun generationFor(conversationId: String): ChatGeneration = synchronized(generationLock) {
        generations.getOrPut(conversationId) {
            ChatGeneration(
                scope = viewModelScope,
                save = conversationRepo::saveConversation,
                publish = { updated ->
                    if (!updated.isTemporary && !deletingAllConversations.value &&
                        updated.id !in deletingConversations.value
                    ) {
                        _liveConversationPreviews.update { it + (updated.id to updated.asConversationListItem()) }
                    }
                    if (_currentConversation.value?.id == updated.id) {
                        _currentConversation.value = renamingConversations.value[updated.id]?.let {
                            updated.copy(title = it)
                        } ?: updated
                    }
                },
                publishStream = { id, stream ->
                    _activeStreams.update { current ->
                        if (stream == null || deletingAllConversations.value || id in deletingConversations.value) {
                            current - id
                        } else current + (id to stream)
                    }
                }
            ).also { generation ->
                generationObservers[conversationId] = viewModelScope.launch {
                    combine(generation.busy, generation.error, generation.waitingForTool) { busy, error, waitingForTool ->
                        GenerationUiState(busy, error, waitingForTool)
                    }.collect { state ->
                        _generationStates.update { it + (conversationId to state) }
                        if (!state.busy) {
                            _activeStreams.update { retainChatStreams(it, _currentConversation.value?.id) }
                        }
                    }
                }
            }
        }
    }

    private fun releaseTemporaryGeneration(conversationId: String) {
        synchronized(generationLock) {
            generationObservers.remove(conversationId)?.cancel()
            generations.remove(conversationId)
        }
        _generationStates.update { it - conversationId }
        _activeStreams.update { it - conversationId }
    }

    private fun existingGeneration(conversationId: String): ChatGeneration? =
        synchronized(generationLock) { generations[conversationId] }

    private fun generationForCurrent(): ChatGeneration? =
        _currentConversation.value?.id?.let(::existingGeneration)

    /** Busy state for the selected chat only; this controls its stop/send button. */
    val isGenerating: StateFlow<Boolean> = combine(_currentConversation, _generationStates) { conversation, states ->
        conversation?.id?.let { states[it]?.busy } == true
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isWaitingForTool: StateFlow<Boolean> = combine(_currentConversation, _generationStates) { conversation, states ->
        conversation?.id?.let { states[it]?.waitingForTool } == true
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Global state used only by the background-generation service. */
    val isAnyGenerating: StateFlow<Boolean> = _generationStates
        .map { states -> states.values.any { it.busy } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * A response is active before the first API event arrives as well as while content is being
     * streamed. This is the state used by the screen wakelock's automatic mode; tool calls are
     * deliberately excluded there.
     */
    val isAnyResponseActive: StateFlow<Boolean> = _generationStates
        .map { states -> states.values.any { it.busy && !it.waitingForTool } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val generatingConversationIds: StateFlow<Set<String>> = _generationStates
        .map { states -> states.filterValues { it.busy }.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Compatibility value for the current chat screen; the drawer uses the complete id set. */
    val generatingConversationId: StateFlow<String?> = combine(_currentConversation, isGenerating) { conversation, busy ->
        conversation?.id?.takeIf { busy }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val chatError: StateFlow<String?> = combine(_currentConversation, _generationStates) { conversation, states ->
        conversation?.id?.let { states[it]?.error }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val summaries = ConversationSummaryCoordinator(conversationRepo, toolChat, { _currentConversation.value }) { updated ->
        if (_currentConversation.value?.id == updated.id) {
            _currentConversation.value = renamingConversations.value[updated.id]?.let { updated.copy(title = it) } ?: updated
        }
    }
    val summaryProgress = summaries.progress
    private var summaryJob: Job? = null

    private val modelContext = ChatModelContextCoordinator(
        scope = viewModelScope,
        currentConversation = _currentConversation,
        appPreferences = appPreferences,
        providers = providers,
        toolSettings = toolSettings,
    )
    val contextWindowStatus: StateFlow<ContextWindowStatus?> get() = modelContext.contextWindowStatus

    private val automaticSummary = AutomaticConversationSummaryCoordinator(
        isGenerating = isGenerating,
        isGenerationReplacementActive = { replacingGeneration },
        isSummaryActive = { summaryJob?.isActive == true },
        pendingWrites = { pendingConversationWrites },
        isRestoreInProgress = { isRestoreInProgress },
        currentConversation = _currentConversation,
        appPreferences = appPreferences,
        providers = providers,
        modelContext = modelContext,
        startSummary = ::startConversationSummary,
    )
    private val _queuedMessages = MutableStateFlow<Map<String, List<StoredMessage>>>(emptyMap())
    private var replacingGeneration = false

    private val conversationCoordinator: ChatConversationCoordinator = ChatConversationCoordinator(
        scope = viewModelScope,
        conversationRepo = conversationRepo,
        prefsRepo = prefsRepo,
        toolFiles = toolFiles,
        currentConversation = _currentConversation,
        queuedMessagesByConversation = _queuedMessages,
        deletingConversations = deletingConversations,
        renamingConversations = renamingConversations,
        deletingAllConversations = deletingAllConversations,
        appPreferences = appPreferences,
        existingGeneration = ::existingGeneration,
        generationsFor = { ids -> synchronized(generationLock) {
            generations.filterKeys { it in ids }.values.toList()
        } },
        allGenerations = { synchronized(generationLock) { generations.values.toList() } },
        providerForConversation = modelContext::conversationModelProvider,
        disconnectRemoteMachines = toolChat::disconnectRemoteMachines,
        summaryConversationId = { summaryProgress.value?.conversationId },
        cancelSummary = { summaryJob?.cancelAndJoin() },
        cancelTitles = ::cancelAllTitleGenerations,
        cancelTitlesFor = ::cancelTitleGenerationsFor,
        releaseTemporaryGeneration = ::releaseTemporaryGeneration,
        maybeStartAutomaticSummary = { _currentConversation.value?.isTemporary != true && automaticSummary.maybeStart() },
        isGenerationReplacementActive = { replacingGeneration },
        isSummaryActive = { summaryJob?.isActive == true },
        sendMessage = { text, files, createSkill, skillName -> sendMessage(text, files, createSkill, skillName) },
    )

    val loadingConversationId: StateFlow<String?> = conversationCoordinator.loadingConversationId

    private val conversationTitleGenerator = ConversationTitleGenerator(
        scope = viewModelScope,
        conversationRepo = conversationRepo,
        toolChat = toolChat,
        currentConversation = _currentConversation,
        configuredTextModel = modelContext::configuredTextModel,
        existingGeneration = ::existingGeneration,
        generationFor = ::generationFor,
        isRestoreInProgress = { isRestoreInProgress },
        withRestoreGuard = { block -> conversationCoordinator.withRestoreGuard(onRejected = {}, block = block) },
        writeConversation = { block -> conversationCoordinator.writeConversation(serialize = true, block = block) },
    )

    private val messageActions = ChatMessageActions(
        viewModelScope = viewModelScope,
        conversationRepo = conversationRepo,
        prefsRepo = prefsRepo,
        toolChat = toolChat,
        _currentConversation = _currentConversation,
        providers = providers,
        toolSettings = toolSettings,
        appPreferences = appPreferences,
        isGenerating = isGenerating,
        enqueueMessage = conversationCoordinator::enqueueMessage,
        summaryJob = { summaryJob },
        pendingConversationWrites = { pendingConversationWrites },
        isRestoreInProgress = { isRestoreInProgress },
        guardRestoreBoolean = { block -> conversationCoordinator.withRestoreGuard(onRejected = { false }, block = block) },
        guardRestoreUnit = { block -> conversationCoordinator.withRestoreGuard(onRejected = {}, block = block) },
        isReplacingGeneration = { replacingGeneration },
        setReplacingGeneration = { replacingGeneration = it },
        generationFor = ::generationFor,
        existingGeneration = ::existingGeneration,
        generationForCurrent = ::generationForCurrent,
        selectedChatModel = modelContext::selectedChatModel,
        providerWithReasoning = modelContext::providerWithReasoning,
        conversationTitleGenerator = conversationTitleGenerator,
        toolEvents = ::toolEvents,
        writeConversation = { beforeLaunch, block ->
            conversationCoordinator.writeConversation(beforeLaunch = beforeLaunch, block = block)
        },
        saveEditedConversation = ::saveEditedConversation,
        drainQueuedMessages = ::drainQueuedMessages,
        maybeStartAutomaticSummary = { incoming ->
            _currentConversation.value?.isTemporary != true && automaticSummary.maybeStart(incoming)
        },
    )

    private val messageRetryActions = ChatMessageRetryActions(
        isGenerating = isGenerating,
        summaryJob = { summaryJob },
        pendingConversationWrites = { pendingConversationWrites },
        guardRestoreBoolean = { block -> conversationCoordinator.withRestoreGuard(onRejected = { false }, block = block) },
        guardRestoreUnit = { block -> conversationCoordinator.withRestoreGuard(onRejected = {}, block = block) },
        currentConversation = _currentConversation,
        appPreferences = appPreferences,
        selectedChatModel = modelContext::selectedChatModel,
        generatedVersion = messageActions::generatedVersion,
        composerMediaTarget = messageActions::composerMediaTarget,
        startMediaResponseAction = { conversation, responseId, target, rollback ->
            messageActions.startMediaResponse(conversation, responseId, target, rollback)
        },
        startUserMessageResponseAction = { conversation, responseId, provider, modelId, preferences, rollback, resume ->
            messageActions.startUserMessageResponse(
                conversation, responseId, provider, modelId, preferences, rollback, resume
            )
        },
    )

    val queuedMessages: StateFlow<List<StoredMessage>> get() = conversationCoordinator.queuedMessages
    private val pendingConversationWrites: Int get() = conversationCoordinator.pendingConversationWrites
    private val isRestoreInProgress: Boolean get() = conversationCoordinator.isRestoreInProgress

    private suspend fun cancelAllTitleGenerations() = conversationTitleGenerator.cancelAll()
    private suspend fun cancelTitleGenerationsFor(conversationIds: Set<String>) =
        conversationTitleGenerator.cancelFor(conversationIds)
    private fun restoreLastConversation() = conversationCoordinator.restoreLastConversation()
    private fun drainQueuedMessages() = conversationCoordinator.drainQueuedMessages()
    private fun takePendingMessage(conversationId: String): StoredMessage? =
        conversationCoordinator.takePendingMessage(conversationId)
    private fun writeConversation(
        serialize: Boolean = false,
        beforeLaunch: () -> Unit = {},
        block: suspend () -> Unit
    ): Boolean = conversationCoordinator.writeConversation(serialize, beforeLaunch, block)
    private fun saveEditedConversation(previous: Conversation, updated: Conversation): Boolean =
        conversationCoordinator.saveEditedConversation(previous, updated)

    fun editQueuedMessage(id: String, newContent: String, files: List<String>): Boolean =
        conversationCoordinator.editQueuedMessage(id, newContent, files)
    fun deleteQueuedMessage(id: String) = conversationCoordinator.deleteQueuedMessage(id)
    fun selectConversation(conversation: Conversation) {
        if (_currentConversation.value?.isTemporary == true) stopSpeaking()
        conversationCoordinator.selectConversation(conversation)
    }
    fun selectConversation(conversationId: String) {
        if (_currentConversation.value?.isTemporary == true) stopSpeaking()
        conversationCoordinator.selectConversation(conversationId)
    }
    fun startNewChat() {
        if (_currentConversation.value?.isTemporary == true) stopSpeaking()
        conversationCoordinator.startNewChat()
    }
    fun startTemporaryChat() {
        if (_currentConversation.value?.isTemporary == true) stopSpeaking()
        conversationCoordinator.startTemporaryChat()
    }
    fun deleteConversation(id: String) = deleteConversations(setOf(id))
    fun deleteConversations(ids: Set<String>, attachedFiles: Collection<String> = emptyList()) {
        _liveConversationPreviews.update { it - ids }
        _activeStreams.update { it - ids }
        conversationCoordinator.deleteConversations(ids, attachedFiles)
    }
    fun renameConversation(id: String, newTitle: String) = conversationCoordinator.renameConversation(id, newTitle)
    suspend fun suggestConversationTitle(id: String): Result<String> = conversationTitleGenerator.suggest(id)
    fun clearAllConversations(attachedFiles: Collection<String> = emptyList()) {
        _activeStreams.value = emptyMap()
        _liveConversationPreviews.value = emptyMap()
        conversationCoordinator.clearAllConversations(attachedFiles)
    }
    suspend fun importConversations(items: List<Conversation>) = conversationCoordinator.importConversations(items)
    suspend fun conversationsForExport(): List<Conversation> = conversationCoordinator.conversationsForExport()
    /** Drains pending writes and stops chat work after the backup has been validated. */
    suspend fun prepareForRestore() = conversationCoordinator.beginRestore()

    /** Finalizes the in-memory chat state and releases the write boundary. */
    suspend fun finishRestore(succeeded: Boolean) {
        try {
            if (succeeded) conversationCoordinator.resetAfterRestore()
            synchronized(generationLock) {
                generationObservers.values.forEach { it.cancel() }
                generationObservers.clear()
                generations.clear()
            }
            _generationStates.value = emptyMap()
            _liveConversationPreviews.value = emptyMap()
            _activeStreams.value = emptyMap()
        } finally {
            conversationCoordinator.endRestore()
        }
        if (succeeded) restoreLastConversation()
    }
    fun deleteAllUserData(attachedFiles: Collection<String> = emptyList()) =
        clearAllConversations(attachedFiles)


    init {
        viewModelScope.launch {
            currentConversation.map { it?.id }.distinctUntilChanged().collect { currentId ->
                // The mounted card retains its frozen snapshot. Once that viewport is gone,
                // stopped streams need no shared RAM entry; active generations remain independent.
                _activeStreams.update { streams -> retainChatStreams(streams, currentId) }
            }
        }
        viewModelScope.launch { modelContext.contextEstimate.collect { automaticSummary.maybeStart() } }
        viewModelScope.launch {
            isGenerating.collect { busy ->
                if (!busy && !replacingGeneration && !automaticSummary.maybeStart()) drainQueuedMessages()
            }
        }
        restoreLastConversation()
    }

    fun setInstructionEnabled(instructionId: String, enabled: Boolean) =
        conversationCoordinator.setInstructionEnabled(instructionId, enabled)
    fun setInstructionLibrary(library: InstructionLibrary) {
        if (isRestoreInProgress) return
        viewModelScope.launch(LocalWriteErrors.handler) {
            if (!isRestoreInProgress) prefsRepo.setInstructionLibrary(library)
        }
    }
    fun setAgentSkills(skills: List<AgentSkill>) {
        if (isRestoreInProgress) return
        viewModelScope.launch(LocalWriteErrors.handler) {
            if (!isRestoreInProgress) prefsRepo.setAgentSkills(skills)
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        if (isRestoreInProgress) return
        viewModelScope.launch(LocalWriteErrors.handler) {
            if (isRestoreInProgress) return@launch
            launch {
                if (!isRestoreInProgress) prefsRepo.setSelectedModel(providerId, modelId)
            }
            val provider = modelRepo.getProviderById(providerId)
            if (!isRestoreInProgress && provider?.config?.modelIds != null && modelId !in provider.config.modelIds) {
                modelRepo.saveProvider(provider.copy(config = provider.config.copy(
                    modelIds = provider.config.modelIds + modelId,
                    modelConfigs = provider.config.modelConfigs + (modelId to ModelConfiguration())
                )))
            }
            // Keep the open conversation's selection separate from the new-chat default.
            conversationCoordinator.withRestoreGuard(onRejected = {}) {
                val current = _currentConversation.value ?: return@withRestoreGuard
                if (!appPreferences.value.persistSelectedModel) return@withRestoreGuard
                val updated = current.copy(
                    providerId = providerId,
                    modelId = modelId,
                    updatedAt = System.currentTimeMillis()
                )
                if (existingGeneration(current.id)?.snapshot?.id == current.id) {
                    generationFor(current.id).updateConversation {
                        it.copy(providerId = providerId, modelId = modelId, updatedAt = updated.updatedAt)
                    }
                } else if (updated.messages.isNotEmpty()) {
                    writeConversation(beforeLaunch = { _currentConversation.value = updated }) {
                        conversationRepo.saveConversation(updated)
                    }
                } else {
                    _currentConversation.value = updated
                }
            }
        }
    }

    fun setPersistSelectedModel(enabled: Boolean) {
        if (isRestoreInProgress) return
        viewModelScope.launch(LocalWriteErrors.handler) {
            if (isRestoreInProgress) return@launch
            prefsRepo.setPersistSelectedModel(enabled)
            if (enabled && !isRestoreInProgress) {
                val current = _currentConversation.value ?: return@launch
                val provider = modelContext.conversationModelProvider(current) ?: return@launch
                prefsRepo.setSelectedModel(provider.id, current.modelId)
            }
        }
    }

    fun selectSpeechService(serviceId: String?) {
        if (isRestoreInProgress) return
        viewModelScope.launch(LocalWriteErrors.handler) {
            if (!isRestoreInProgress) prefsRepo.setSelectedSpeechServiceId(serviceId)
        }
    }

    private val speech = ChatSpeechCoordinator(viewModelScope, appPreferences, providers, speechServices,
        ttsHelper, speechSynthesis, speechAudioPlayer, speechNotification)
    val speakingMessageId = speech.speakingMessageId
    val activeTestSpeechServiceId = speech.activeTestServiceId
    fun speakText(text: String, messageId: String? = null) = speech.speak(text, messageId)
    fun testVoice(service: SpeechService, text: String) = speech.speakWithService(service, text, isTest = true)
    fun stopSpeaking() = speech.stop()

    fun setConversationReasoningEffort(effort: String?) {
        val current = _currentConversation.value ?: return
        val normalized = effort?.takeIf { it.isNotBlank() }
        val updatedAt = System.currentTimeMillis()
        val generation = existingGeneration(current.id)?.takeIf { it.snapshot?.id == current.id }
        if (generation != null) {
            generation.updateConversation { it.copy(reasoningEffort = normalized, updatedAt = updatedAt) }
        } else {
            val updated = current.copy(reasoningEffort = normalized, updatedAt = updatedAt)
            if (updated.messages.isNotEmpty()) {
                writeConversation(beforeLaunch = { _currentConversation.value = updated }) {
                    conversationRepo.saveConversation(updated)
                }
            } else {
                conversationCoordinator.withRestoreGuard(onRejected = {}) {
                    _currentConversation.value = updated
                }
            }
        }
    }

    fun setSummaryRole(role: SummaryRole) {
        val current = _currentConversation.value ?: return
        if (current.summary == null) return
        val updatedAt = System.currentTimeMillis()
        val generation = existingGeneration(current.id)?.takeIf { it.snapshot?.id == current.id }
        if (generation != null) {
            generation.updateConversation { conversation ->
                conversation.copy(summary = conversation.summary?.copy(role = role), updatedAt = updatedAt)
            }
        } else {
            val updated = current.copy(summary = current.summary.copy(role = role), updatedAt = updatedAt)
            writeConversation(beforeLaunch = { _currentConversation.value = updated }) {
                conversationRepo.saveConversation(updated)
            }
        }
    }

    fun deleteConversationSummary() {
        val current = _currentConversation.value ?: return
        if (current.summary == null) return
        val updatedAt = System.currentTimeMillis()
        val generation = existingGeneration(current.id)?.takeIf { it.snapshot?.id == current.id }
        if (generation != null) {
            generation.updateConversation { it.copy(summary = null, updatedAt = updatedAt) }
        } else {
            val updated = current.copy(summary = null, updatedAt = updatedAt)
            writeConversation(beforeLaunch = { _currentConversation.value = updated }) {
                conversationRepo.saveConversation(updated)
            }
        }
    }

    fun cancelConversationSummary() {
        val progress = summaryProgress.value
        val current = _currentConversation.value
        automaticSummary.recordCancellation(progress, current, appPreferences.value)
        summaryJob?.cancel()
    }

    fun startConversationSummary(request: ConversationSummaryRequest): Boolean =
        conversationCoordinator.withRestoreGuard(onRejected = { false }) {
            if (isGenerating.value || summaryJob?.isActive == true || pendingConversationWrites > 0) {
                return@withRestoreGuard false
            }
            val conversation = _currentConversation.value ?: return@withRestoreGuard false
            if (conversation.messages.isEmpty() || conversation.isTemporary) return@withRestoreGuard false
            val configured = modelContext.summaryTextModel() ?: return@withRestoreGuard false
            val (provider, config) = configured
            summaryJob = viewModelScope.launch {
                try {
                    summaries.summarize(conversation, provider, config, request)
                } finally {
                    summaryJob = null
                    drainQueuedMessages()
                }
            }
            true
        }


    fun cleanCache() {
        stopGeneration()
        stopSpeaking()
    }

    fun sendMessage(
        userText: String,
        fileAttachments: List<String> = emptyList(),
        createSkillRequested: Boolean = false,
        selectedSkillName: String? = null
    ): Boolean = messageActions.sendMessage(userText, fileAttachments, createSkillRequested, selectedSkillName)
    fun sendMedia(request: DirectMediaRequest): Boolean = messageActions.sendMedia(request)
    fun setComposerMedia(kind: ModelType?, providerId: String, modelId: String) =
        messageActions.setComposerMedia(kind, providerId, modelId)
    fun stopGeneration() = messageActions.stopGeneration()
    fun editMessage(messageId: String, newContent: String, files: List<String>): Boolean =
        messageActions.editMessage(messageId, newContent, files)
    fun deleteAssistantBlock(messageId: String, blockNumber: Int) =
        messageActions.deleteAssistantBlock(messageId, blockNumber)
    fun deleteMessage(messageId: String) = messageActions.deleteMessage(messageId)
    fun deleteMessageVersion(messageId: String) = messageActions.deleteMessageVersion(messageId)
    fun switchMessageVersion(messageId: String, versionIndex: Int) =
        messageActions.switchMessageVersion(messageId, versionIndex)
    fun resendUserMessage(messageId: String): Boolean = messageRetryActions.resendUserMessage(messageId)
    fun retryFromError(messageId: String) = messageRetryActions.retryFromError(messageId)
    fun regenerateMessage(messageId: String) = messageRetryActions.regenerateMessage(messageId)

    override fun onCleared() {
        super.onCleared()
        speech.shutdown()
    }

}

/** Keep live generations across navigation, and only the mounted conversation's frozen handoff. */
internal fun retainChatStreams(
    streams: Map<String, ActiveChatStreamState>,
    currentConversationId: String?
): Map<String, ActiveChatStreamState> = streams.filter { (id, stream) ->
    id == currentConversationId || !stream.stopSignal.isFrozen
}
