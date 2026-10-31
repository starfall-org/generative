package org.starfall.generative.features.home.chat.logic.service

import org.starfall.generative.shared.model.resolveMediaProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import org.starfall.generative.features.home.chat.logic.ChatGeneration
import org.starfall.generative.features.home.chat.logic.withUpdatedMessageStructure
import org.starfall.generative.features.home.chat.logic.input.DirectMediaRequest
import org.starfall.generative.features.home.chat.logic.shouldGenerateConversationTitle
import org.starfall.generative.features.home.chat.logic.startedWithDirectMedia
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationSummary
import org.starfall.generative.shared.model.DEFAULT_TITLE_GENERATION_MIN_MESSAGES
import org.starfall.generative.shared.model.DEFAULT_TITLE_GENERATION_MIN_WORDS
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.features.home.chat.logic.effectiveContext
import org.starfall.generative.features.home.chat.logic.contextUsageKey
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.networking.obj
import org.starfall.generative.database.ConversationRepository
import org.starfall.generative.shared.storage.LocalWriteErrors
import org.starfall.generative.shared.storage.preferences.AppPreferences
import org.starfall.generative.shared.storage.preferences.AppPreferencesRepository
import org.starfall.generative.features.home.chat.logic.markdown.PersistentMarkdownAstCache

/** Handles chat-message mutations and generation entry points; state is supplied by the ViewModel. */
internal class ChatMessageActions(
    private val viewModelScope: CoroutineScope,
    private val conversationRepo: ConversationRepository,
    private val prefsRepo: AppPreferencesRepository,
    private val toolChat: ToolChatViewModel,
    private val _currentConversation: MutableStateFlow<Conversation?>,
    private val providers: StateFlow<List<ModelProvider>>,
    private val toolSettings: StateFlow<ToolSettings>,
    private val appPreferences: StateFlow<AppPreferences>,
    private val isGenerating: StateFlow<Boolean>,
    private val enqueueMessage: (String, StoredMessage) -> Unit,
    private val summaryJob: () -> Job?,
    private val pendingConversationWrites: () -> Int,
    private val isRestoreInProgress: () -> Boolean = { false },
    private val guardRestoreBoolean: (() -> Boolean) -> Boolean = { it() },
    private val guardRestoreUnit: (() -> Unit) -> Unit = { it() },
    private val isReplacingGeneration: () -> Boolean,
    private val setReplacingGeneration: (Boolean) -> Unit,
    private val generationFor: (String) -> ChatGeneration,
    private val existingGeneration: (String) -> ChatGeneration?,
    private val generationForCurrent: () -> ChatGeneration?,
    private val selectedChatModel: (Conversation?, AppPreferences) -> Pair<ModelProvider?, String>,
    private val providerWithReasoning: (ModelProvider, String, Conversation) -> ModelProvider,
    private val conversationTitleGenerator: ConversationTitleGenerator,
    private val toolEvents: (ModelProvider, String, List<StoredMessage>, String, String, Boolean) -> Flow<GenerationEvent>,
    private val writeConversation: (() -> Unit, suspend () -> Unit) -> Boolean,
    private val saveEditedConversation: (Conversation, Conversation) -> Boolean,
    private val drainQueuedMessages: () -> Unit,
    private val maybeStartAutomaticSummary: (StoredMessage?) -> Boolean,
) {
    private fun summaryAfterMessageMutation(
        conversation: Conversation,
        changedMessageIndex: Int,
        newMessages: List<StoredMessage>
    ): ConversationSummary? {
        val summary = conversation.summary ?: return null
        val boundaryIndex = conversation.messages.indexOfFirst { it.id == summary.throughMessageId }
        if (boundaryIndex < 0 || changedMessageIndex <= boundaryIndex) return null
        return summary.takeIf { candidate ->
            newMessages.any { it.id == candidate.throughMessageId }
        }
    }

    internal fun generatedVersion(provider: ModelProvider?, modelId: String, timestamp: String) = MessageVersion(
        timestamp = timestamp,
        providerId = provider?.id.orEmpty(),
        modelId = modelId,
        modelDisplayName = provider?.config?.modelConfigs?.get(modelId)?.displayName?.ifBlank { modelId } ?: modelId
    )

    fun sendMessage(
        userText: String,
        fileAttachments: List<String> = emptyList(),
        createSkillRequested: Boolean = false,
        selectedSkillName: String? = null
    ): Boolean {
        if (userText.isBlank() && fileAttachments.isEmpty()) return false

        return guardRestoreBoolean {
            val existing = _currentConversation.value
            if (isGenerating.value || summaryJob()?.isActive == true || pendingConversationWrites() > 0) {
                val conversationId = existing?.id ?: return@guardRestoreBoolean false
                val queued = StoredMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatRole.USER,
                    versions = listOf(
                        MessageVersion(
                            content = userText,
                            createSkillRequested = createSkillRequested,
                            selectedSkillName = selectedSkillName,
                            timestamp = System.currentTimeMillis().toString(),
                            files = fileAttachments
                        )
                    ),
                    isQueued = true
                )
                enqueueMessage(conversationId, queued)
                return@guardRestoreBoolean true
            }

            val incoming = StoredMessage(UUID.randomUUID().toString(), ChatRole.USER,
                listOf(MessageVersion(content = userText, files = fileAttachments, createSkillRequested = createSkillRequested, selectedSkillName = selectedSkillName, timestamp = System.currentTimeMillis().toString())),
                isQueued = true)
            if (maybeStartAutomaticSummary(incoming)) {
                val conversationId = existing?.id ?: return@guardRestoreBoolean false
                enqueueMessage(conversationId, incoming)
                return@guardRestoreBoolean true
            }
            val prefs = appPreferences.value
            val (baseProvider, modelId) = selectedChatModel(existing, prefs)
            val canGenerate = baseProvider != null && modelId.isNotBlank()
            val now = System.currentTimeMillis()
            val user = StoredMessage(
                UUID.randomUUID().toString(),
                ChatRole.USER,
                listOf(
                    MessageVersion(
                        content = userText,
                        createSkillRequested = createSkillRequested,
                        selectedSkillName = selectedSkillName,
                        timestamp = now.toString(),
                        files = fileAttachments
                    )
                )
            )
            val assistant = StoredMessage(
                UUID.randomUUID().toString(),
                ChatRole.MODEL,
                listOf(generatedVersion(baseProvider, modelId, now.toString()))
            )
            val firstMessage = existing == null || existing.messages.isEmpty()
            val fallbackTitle = conversationFallbackTitle(userText)
            val conv = (existing ?: Conversation(
                id = UUID.randomUUID().toString(),
                title = fallbackTitle,
                createdAt = now,
                updatedAt = now
            )).copy(
                title = if (firstMessage) fallbackTitle else existing.title,
                messages = (existing?.messages ?: emptyList()) + user +
                    if (canGenerate) listOf(assistant) else emptyList(),
                updatedAt = now,
                providerId = baseProvider?.id.orEmpty(),
                modelId = modelId,
                profileId = null,
                selectedInstructionIds = existing?.selectedInstructionIds ?: prefs.instructionLibraryFor(null).selectedIds,
                structuralRevision = (existing?.structuralRevision ?: 0L) + 1L
            )

            if (!canGenerate) {
                return@guardRestoreBoolean writeConversation(
                    {
                        _currentConversation.value = conv
                        viewModelScope.launch(LocalWriteErrors.handler) {
                            if (!isRestoreInProgress() && !conv.isTemporary) prefsRepo.setLastActiveConversationId(conv.id)
                        }
                    }
                ) {
                    conversationRepo.saveConversation(conv)
                }
            }

            _currentConversation.value = conv
            viewModelScope.launch(LocalWriteErrors.handler) {
                if (!isRestoreInProgress() && !conv.isTemporary) prefsRepo.setLastActiveConversationId(conv.id)
            }

            val provider = providerWithReasoning(requireNotNull(baseProvider), modelId, conv)
            val instructionTime = java.time.ZonedDateTime.now()
            val instructionPrompt = prefs.systemPromptFor(conv, instructionTime)
            val instructionMessages = prefs.instructionRoleMessagesFor(conv, instructionTime)
            val context = effectiveContext(conv, conv.messages.dropLast(1), instructionPrompt)
                .let { it.copy(messages = instructionMessages + it.messages) }
            val started = generationFor(conv.id).startEvents(
                conv,
                assistant.id,
                toolEvents(provider, modelId, context.messages, context.systemPrompt, conv.id, createSkillRequested),
                usageContextKey = contextUsageKey(conv, instructionPrompt,
                    baseProvider.config.modelConfigs[modelId] ?: ModelConfiguration(displayName = modelId),
                    instructionMessages, toolSettings.value)
            )
            val titleSettings = toolSettings.value.system["title_generation"]
            if (started && !conv.isTemporary && !conv.startedWithDirectMedia() && shouldGenerateConversationTitle(
                    userText = userText,
                    messageCount = conv.messages.count { it.role == ChatRole.USER || it.role == ChatRole.MODEL },
                    minimumWords = titleSettings?.titleGenerationMinWords ?: DEFAULT_TITLE_GENERATION_MIN_WORDS,
                    minimumMessages = titleSettings?.titleGenerationMinMessages ?: DEFAULT_TITLE_GENERATION_MIN_MESSAGES
                )) {
                val expectedFallbackTitle = conversationFallbackTitle(
                    conv.messages.firstOrNull { it.role == ChatRole.USER }?.content.orEmpty()
                )
                conversationTitleGenerator.generate(conv, expectedFallbackTitle)
            }
            started
        }
    }

    fun sendMedia(request: DirectMediaRequest): Boolean = guardRestoreBoolean {
        if (isGenerating.value || summaryJob()?.isActive == true || pendingConversationWrites() > 0 ||
            request.prompt.isBlank() || request.prompt.length > 32000) return@guardRestoreBoolean false
        if (request.kind !in listOf(ModelType.IMAGE_GENERATION, ModelType.VIDEO_GENERATION)) {
            return@guardRestoreBoolean false
        }
        val provider = providers.value.find { it.id == request.providerId } ?: return@guardRestoreBoolean false
        if (provider.config.modelConfigs[request.modelId]?.modelType != request.kind ||
            provider.config.modelIds?.contains(request.modelId) == false) return@guardRestoreBoolean false
        val now = System.currentTimeMillis()
        val user = StoredMessage(UUID.randomUUID().toString(), ChatRole.USER,
            listOf(MessageVersion(content = request.prompt, timestamp = now.toString(), files = request.attachments)))
        val assistant = StoredMessage(UUID.randomUUID().toString(), ChatRole.MODEL,
            listOf(generatedVersion(provider, request.modelId, now.toString())))
        val existing = _currentConversation.value
        val isFirstMessage = existing == null || existing.messages.isEmpty()
        val conversation = (existing ?: Conversation(
            id = UUID.randomUUID().toString(), title = conversationFallbackTitle(request.prompt),
            createdAt = now, updatedAt = now
        )).copy(
            title = if (isFirstMessage) conversationFallbackTitle(request.prompt) else existing.title,
            messages = existing?.messages.orEmpty() + user + assistant,
            updatedAt = now,
            structuralRevision = (existing?.structuralRevision ?: 0L) + 1L
        )
        _currentConversation.value = conversation
        viewModelScope.launch(LocalWriteErrors.handler) {
            if (!isRestoreInProgress() && !conversation.isTemporary) prefsRepo.setLastActiveConversationId(conversation.id)
        }
        generationFor(conversation.id).startEvents(conversation, assistant.id,
            mediaEvents(provider, request.modelId, request.kind, request.prompt, request.attachments))
    }

    private fun mediaEvents(
        provider: ModelProvider,
        modelId: String,
        kind: ModelType,
        prompt: String,
        attachments: List<String>
    ): Flow<GenerationEvent> {
        val name = if (kind == ModelType.IMAGE_GENERATION) "generate_image" else "generate_video"
        val config = toolSettings.value.system[name]?.takeIf {
            it.providerId == provider.id && it.modelId == modelId
        }
        val modelConfig = provider.config.modelConfigs[modelId]
        val requestProfile = toolSettings.value.resolveMediaProfile(kind == ModelType.VIDEO_GENERATION, modelConfig)
        val generationMode = requestProfile.mode
        val builtIn = requestProfile.id == org.starfall.generative.shared.model.media.defaultMediaProfile(kind == ModelType.VIDEO_GENERATION).id
        val imageOptions = if (builtIn && requestProfile.options.isEmpty()) config?.imageOptions ?: obj() else requestProfile.options
        val videoOptions = if (builtIn && requestProfile.options.isEmpty()) config?.videoOptions ?: obj() else requestProfile.options
        return toolChat.generateMedia(provider, modelId, kind, prompt,
            imageOptions, attachments,
            videoOptions = videoOptions,
            generationMode = generationMode,
            requestChain = if (generationMode == org.starfall.generative.shared.model.media.MediaGenerationMode.CUSTOM) requestProfile.chain else null)
    }

    /** Image/video model a response was produced with, or null when it was a normal chat reply. */
    internal class MediaTarget(val provider: ModelProvider, val modelId: String, val kind: ModelType)

    private class ComposerMedia(val kind: ModelType, val providerId: String, val modelId: String)

    @Volatile private var composerMedia: ComposerMedia? = null

    /** Mirrors the composer's image/video mode; null kind means the composer is in chat mode. */
    fun setComposerMedia(kind: ModelType?, providerId: String, modelId: String) {
        composerMedia = kind?.let { ComposerMedia(it, providerId, modelId) }
    }

    /** Media target for the composer's current image/video mode, or null when it is in chat mode. */
    internal fun composerMediaTarget(): MediaTarget? {
        val media = composerMedia ?: return null
        val provider = providers.value.find { it.id == media.providerId } ?: return null
        if (provider.config.modelConfigs[media.modelId]?.modelType != media.kind) return null
        return MediaTarget(provider, media.modelId, media.kind)
    }

    /** Restart a media response from the user message that precedes it (resend/regenerate/retry). */
    internal fun startMediaResponse(
        conversation: Conversation,
        responseId: String,
        target: MediaTarget,
        rollbackConversation: Conversation? = null
    ): Boolean {
        val responseIndex = conversation.messages.indexOfFirst { it.id == responseId }
        if (responseIndex <= 0) return false
        val user = conversation.messages[responseIndex - 1].takeIf { it.role == ChatRole.USER } ?: return false
        val prompt = user.activeVersion.content
        if (prompt.isBlank() || prompt.length > 32000) return false
        if (target.provider.config.modelIds?.contains(target.modelId) == false) return false
        return generationFor(conversation.id).startEvents(
            conversation,
            responseId,
            mediaEvents(target.provider, target.modelId, target.kind, prompt, user.activeVersion.files),
            rollbackConversation = rollbackConversation
        )
    }

    fun stopGeneration() { generationForCurrent()?.stop() }

    fun editMessage(messageId: String, newContent: String, files: List<String>): Boolean {
        if (isRestoreInProgress() || summaryJob()?.isActive == true) return false
        if (newContent.isBlank() && files.isEmpty()) {
            val target = _currentConversation.value?.messages?.firstOrNull { it.id == messageId }
            if (target?.role != ChatRole.MODEL ||
                assistantVisibleBlocks(target.activeVersion).isEmpty()) return false
        }
        if (isGenerating.value) {
            val conversation = _currentConversation.value ?: return false
            val message = conversation.messages.firstOrNull { it.id == messageId } ?: return false
            if (isReplacingGeneration()) return false
            if (message.role == ChatRole.USER) {
                val (provider, modelId) = selectedChatModel(conversation, appPreferences.value)
                if (provider == null || modelId.isBlank()) return false
            }
            setReplacingGeneration(true)
            viewModelScope.launch {
                try {
                    generationFor(conversation.id).stopAndJoin()
                    editMessageNow(messageId, newContent, files)
                } finally {
                    setReplacingGeneration(false)
                    if (!isGenerating.value) drainQueuedMessages()
                }
            }
            return true
        }
        return editMessageNow(messageId, newContent, files)
    }

    private fun editMessageNow(messageId: String, newContent: String, files: List<String>): Boolean =
        guardRestoreBoolean {
            if (summaryJob()?.isActive == true) {
                return@guardRestoreBoolean false
            }
            val conv = _currentConversation.value ?: return@guardRestoreBoolean false
            val currentMsgs = conv.messages.toMutableList()
            val idx = currentMsgs.indexOfFirst { it.id == messageId }
            if (idx == -1) return@guardRestoreBoolean false

            val oldMsg = currentMsgs[idx]
            if (newContent.isBlank() && files.isEmpty() &&
                (oldMsg.role != ChatRole.MODEL ||
                    assistantVisibleBlocks(oldMsg.activeVersion).isEmpty())) return@guardRestoreBoolean false
            val newVersions = oldMsg.versions.toMutableList()
            val now = System.currentTimeMillis()
            newVersions.add(editedMessageVersion(oldMsg, newContent, files, now.toString()))
            currentMsgs[idx] = oldMsg.copy(
                versions = newVersions,
                activeVersionIndex = newVersions.size - 1
            )

            if (oldMsg.role == ChatRole.USER) {
                val prefs = appPreferences.value
                val (provider, modelId) = selectedChatModel(conv, prefs)
                if (provider == null || modelId.isBlank()) return@guardRestoreBoolean false

                val previousResponse = currentMsgs.getOrNull(idx + 1)?.takeIf { it.role == ChatRole.MODEL }
                val responseId = previousResponse?.id ?: UUID.randomUUID().toString()
                val responseVersion = generatedVersion(provider, modelId, now.toString())
                val response = previousResponse?.copy(
                    versions = previousResponse.versions + responseVersion,
                    activeVersionIndex = previousResponse.versions.size
                ) ?: StoredMessage(responseId, ChatRole.MODEL, listOf(responseVersion))
                val updatedMessages = currentMsgs.take(idx + 1) + response
                val updated = conv.withUpdatedMessageStructure(updatedMessages).copy(
                    summary = summaryAfterMessageMutation(conv, idx, updatedMessages),
                    updatedAt = now,
                    providerId = provider.id,
                    modelId = modelId,
                    profileId = null
                )
                return@guardRestoreBoolean startUserMessageResponse(updated, responseId, provider, modelId, prefs)
            }

            val updated = conv.withUpdatedMessageStructure(currentMsgs).copy(
                summary = summaryAfterMessageMutation(conv, idx, currentMsgs),
                updatedAt = System.currentTimeMillis()
            )
            saveEditedConversation(conv, updated)
        }

    internal fun startUserMessageResponse(
        conversation: Conversation,
        responseId: String,
        provider: ModelProvider,
        modelId: String,
        preferences: AppPreferences,
        rollbackConversation: Conversation? = null,
        resume: Boolean = false
    ): Boolean {
        val responseIndex = conversation.messages.indexOfFirst { it.id == responseId }
        if (responseIndex <= 0) return false
        // Resuming keeps the partial answer and its tool results in the context, followed by a
        // hidden instruction (never stored or shown) to carry on from where the error happened.
        val contextMessages = if (resume) {
            conversation.messages.take(responseIndex + 1) + StoredMessage(
                id = "resume_$responseId",
                role = ChatRole.USER,
                versions = listOf(MessageVersion(
                    content = RESUME_AFTER_ERROR_PROMPT,
                    timestamp = System.currentTimeMillis().toString()
                ))
            )
        } else conversation.messages.take(responseIndex)
        val instructionTime = java.time.ZonedDateTime.now()
        val instructionPrompt = preferences.systemPromptFor(conversation, instructionTime)
        val instructionMessages = preferences.instructionRoleMessagesFor(conversation, instructionTime)
        val context = effectiveContext(conversation, contextMessages.dropLast(1), instructionPrompt)
            .let { it.copy(messages = instructionMessages + it.messages + contextMessages.last()) }
        val configuredProvider = providerWithReasoning(provider, modelId, conversation)
        return generationFor(conversation.id).startEvents(
            conversation,
            responseId,
            toolEvents(configuredProvider, modelId, context.messages, context.systemPrompt, conversation.id,
                conversation.messages[responseIndex - 1].activeVersion.createSkillRequested),
            rollbackConversation = rollbackConversation,
            resume = resume,
            usageContextKey = contextUsageKey(conversation, instructionPrompt,
                provider.config.modelConfigs[modelId] ?: ModelConfiguration(displayName = modelId),
                instructionMessages, toolSettings.value)
        )
    }

    fun deleteMessage(messageId: String) {
        if (isRestoreInProgress() || summaryJob()?.isActive == true) return
        val current = _currentConversation.value ?: return
        if (isGenerating.value && existingGeneration(current.id)?.snapshot?.id == current.id) {
            viewModelScope.launch {
                generationFor(current.id).stopAndJoin()
                deleteMessageNow(messageId)
            }
            return
        }
        deleteMessageNow(messageId)
    }

    private fun deleteMessageNow(messageId: String) = guardRestoreUnit {
        val conv = _currentConversation.value ?: return@guardRestoreUnit
        val messageIndex = conv.messages.indexOfFirst { it.id == messageId }
        if (messageIndex == -1) return@guardRestoreUnit
        val currentMsgs = conv.messages.filter { it.id != messageId }
        PersistentMarkdownAstCache.invalidateMessage(conv.id, messageId)
        val updated = conv.withUpdatedMessageStructure(currentMsgs).copy(
            summary = summaryAfterMessageMutation(conv, messageIndex, currentMsgs),
            updatedAt = System.currentTimeMillis()
        )
        saveEditedConversation(conv, updated)
    }

    fun deleteMessageVersion(messageId: String) {
        if (isRestoreInProgress() || summaryJob()?.isActive == true) return
        val current = _currentConversation.value ?: return
        if (isGenerating.value && existingGeneration(current.id)?.snapshot?.id == current.id) {
            viewModelScope.launch {
                generationFor(current.id).stopAndJoin()
                deleteMessageVersionNow(messageId)
            }
            return
        }
        deleteMessageVersionNow(messageId)
    }

    private fun deleteMessageVersionNow(messageId: String) = guardRestoreUnit {
        val conv = _currentConversation.value ?: return@guardRestoreUnit
        val currentMsgs = conv.messages.toMutableList()
        val messageIndex = currentMsgs.indexOfFirst { it.id == messageId }
        if (messageIndex == -1) return@guardRestoreUnit

        val message = currentMsgs[messageIndex]
        if (message.versions.size <= 1) {
            currentMsgs.removeAt(messageIndex)
            PersistentMarkdownAstCache.invalidateMessage(conv.id, messageId)
        } else {
            val versions = message.versions.toMutableList().apply {
                removeAt(message.activeVersionIndex.coerceIn(indices))
            }
            currentMsgs[messageIndex] = message.copy(
                versions = versions,
                activeVersionIndex = message.activeVersionIndex.coerceAtMost(versions.lastIndex)
            )
        }

        val updated = conv.withUpdatedMessageStructure(currentMsgs).copy(
            summary = summaryAfterMessageMutation(conv, messageIndex, currentMsgs),
            updatedAt = System.currentTimeMillis()
        )
        saveEditedConversation(conv, updated)
    }

    fun deleteAssistantBlock(messageId: String, blockNumber: Int) = guardRestoreUnit {
        if (isGenerating.value) return@guardRestoreUnit
        val conversation = _currentConversation.value ?: return@guardRestoreUnit
        val index = conversation.messages.indexOfFirst { it.id == messageId && it.role == ChatRole.MODEL }
        if (index < 0) return@guardRestoreUnit
        val message = conversation.messages[index]
        val updated = deleteAssistantBlock(message.activeVersion, blockNumber)
        if (updated == message.activeVersion) return@guardRestoreUnit
        val messages = conversation.messages.toMutableList()
        val versions = message.versions + updated.copy(timestamp = System.currentTimeMillis().toString())
        messages[index] = message.copy(versions = versions, activeVersionIndex = versions.lastIndex)
        saveEditedConversation(conversation, conversation.withUpdatedMessageStructure(messages).copy(
            summary = summaryAfterMessageMutation(conversation, index, messages),
            updatedAt = System.currentTimeMillis()
        ))
    }

    fun switchMessageVersion(messageId: String, versionIndex: Int) = guardRestoreUnit {
        if (isGenerating.value) return@guardRestoreUnit
        val conv = _currentConversation.value ?: return@guardRestoreUnit
        val currentMsgs = conv.messages.toMutableList()
        val idx = currentMsgs.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val oldMsg = currentMsgs[idx]
            if (versionIndex in oldMsg.versions.indices) {
                currentMsgs[idx] = oldMsg.copy(activeVersionIndex = versionIndex)
                val updated = conv.copy(
                    messages = currentMsgs,
                    summary = summaryAfterMessageMutation(conv, idx, currentMsgs),
                    updatedAt = System.currentTimeMillis()
                )
                saveEditedConversation(conv, updated)
            }
        }
    }


}

private const val RESUME_AFTER_ERROR_PROMPT =
    "The previous response was interrupted by an error. Continue it exactly from where it stopped " +
        "without repeating what was already written or already done by tools."


/** Editing a response changes only its Content; tool results and Thinking are retained. */
internal fun editedMessageVersion(
    message: StoredMessage,
    content: String,
    files: List<String>,
    timestamp: String
): MessageVersion = message.activeVersion.let { previous ->
    if (message.role == ChatRole.MODEL) {
        editedAssistantVersion(message, content, files, timestamp)
    } else {
        MessageVersion(content = content, files = files, timestamp = timestamp,
            providerId = previous.providerId, modelId = previous.modelId,
            modelDisplayName = previous.modelDisplayName)
    }
}

/** Keep the full fallback title; the drawer ellipsizes only at render time. */
internal fun conversationFallbackTitle(text: String): String = text.ifBlank { "Attachment" }
