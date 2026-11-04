package org.starfall.generative.features.home.chat.ui

import org.starfall.generative.R
import org.starfall.generative.features.home.BottomSheetsLinkState

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.starfall.generative.features.home.chat.logic.SAVE_CONVERSATION_ERROR_PREFIX
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationSummaryProgress
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.SummaryRole
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.layout.rememberInputAreaBackdrop
import org.starfall.generative.shared.ui.layout.inputAreaBackdropSource
import org.starfall.generative.shared.ui.layout.LocalInputAreaAppearance
import org.starfall.generative.shared.ui.screens.MainScreenTemplate
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.text.PlatformTextSelection
import org.starfall.generative.shared.ui.text.PlatformTextSelectionRegion
import org.starfall.generative.features.home.chat.logic.input.ChatInputEditDraft
import org.starfall.generative.features.home.chat.logic.input.DirectMediaRequest
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.defaultAgentSkills
import org.starfall.generative.features.home.chat.logic.unansweredUserMessageIds
import org.starfall.generative.features.home.chat.ui.input.UserInputArea
import org.starfall.generative.features.home.shared.ui.ConversationSummarySheet
import org.starfall.generative.features.home.chat.model.ContextWindowStatus
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState

@Composable
fun ChatScreen(
    conversation: Conversation?,
    isGenerating: Boolean,
    generatingConversationId: String?,
    chatError: String?,
    providers: List<ModelProvider>,
    providerGroups: List<ProviderGroup> = emptyList(),
    modelPickerCollapsedGroups: Set<String> = emptySet(),
    modelPickerCollapsedProviders: Set<String> = emptySet(),
    onModelPickerCollapsedGroupsChange: (Set<String>) -> Unit = {},
    onModelPickerCollapsedProvidersChange: (Set<String>) -> Unit = {},
    selectedProviderId: String,
    selectedModelName: String,
    onSendMessage: (String, List<String>) -> Boolean,
    onSendMedia: (DirectMediaRequest) -> Boolean,
    onMediaModeChange: (ModelType?, String, String) -> Unit = { _, _, _ -> },
    onStopGenerating: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenTemporaryChat: () -> Unit = onNewChat,
    onOpenSettings: () -> Unit,
    featuresLogoImage: String? = null,
    backgroundImage: String? = null,
    backgroundTransparency: Float = 0.5f,
    onRegenerate: (String) -> Unit,
    onRetryFromError: (String) -> Unit = {},
    onEditMessage: (messageId: String, newContent: String, files: List<String>) -> Boolean,
    onDeleteAssistantBlock: (String, Int) -> Unit = { _, _ -> },
    onDeleteMessage: (messageId: String) -> Unit,
    onDeleteMessageVersion: (messageId: String) -> Unit,
    onSwitchVersion: (messageId: String, versionIndex: Int) -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    summaryProgress: ConversationSummaryProgress?,
    onSetReasoningEffort: (String?) -> Unit,
    onSetThinkingPassback: (providerId: String, modelId: String, mode: ThinkingPassback) -> Unit = { _, _, _ -> },
    onSetModelReasoningEffort: (providerId: String, modelId: String, effort: String?) -> Unit = { _, _, _ -> },
    onSetModelTools: (providerId: String, modelId: String, tools: List<String>) -> Unit = { _, _, _ -> },
    onEditModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    onEditProvider: (providerId: String) -> Unit = {},
    onStartConversationSummary: (ConversationSummaryRequest) -> Boolean,
    onCancelConversationSummary: () -> Unit = {},
    onSummaryRoleChange: (SummaryRole) -> Unit,
    onDeleteSummary: () -> Unit,
    onReadMessage: (String, String) -> Unit,
    onFetchOllamaModels: (suspend (String) -> List<String>)? = null,
    queuedMessages: List<StoredMessage> = emptyList(),
    onEditQueuedMessage: (String, String, List<String>) -> Boolean = { _, _, _ -> true },
    onDeleteQueuedMessage: (String) -> Unit = {},
    autoScroll: Boolean = false,
    loadingConversationId: String? = null,
    contextWindowStatus: ContextWindowStatus? = null,
    speakingMessageId: String? = null,
    onResendUserMessage: (String) -> Boolean = { false },
    onOpenFeatureToolsSettings: () -> Unit = {},
    onOpenMcpConnectorsSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    activeStream: StateFlow<ActiveChatStreamState?>? = null,
    agentSkills: List<AgentSkill> = defaultAgentSkills(),
    onSendMessageWithSkill: (String, List<String>, Boolean, String?) -> Boolean = { text, files, _, _ -> onSendMessage(text, files) },
    onOpenSkillsSettings: (() -> Unit)? = null,
    isDrawerOpening: Boolean = false
) {
    SideEffect { ChatRenderMetrics.recordChatScreenRecomposition() }
    val density = LocalDensity.current
    var chatRootCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var inputAreaBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    val conversationMessages = conversation?.messages.orEmpty()
    val structuralRevision = conversation?.structuralRevision ?: 0L

    val streamingHere = isGenerating && generatingConversationId == conversation?.id
    // Tool activity and reasoning updates do not necessarily advance updatedAt or the
    // structural revision. Key by the actual message snapshot so live Actions become visible.
    val messages = remember(conversationMessages, queuedMessages) {
        conversationMessages + queuedMessages
    }

    val conversationKey = conversation?.id ?: "__empty_conversation__"
    val foreground = LocalChatForeground.current
    // Keep sheet navigation only for the current foreground chat; parked pages retain
    // their scroll/composer state but receive a fresh, closed sheet state on return.
    val bottomSheets = remember(conversationKey, foreground) { BottomSheetsLinkState() }
    val viewport = remember(conversationKey, loadingConversationId, messages.isEmpty()) {
        ChatMessageListState(initialMessages = messages)
    }
    val listState = viewport.listState
    org.starfall.generative.features.home.chat.ui.markdown.PrepareStreamingMarkdown(
        conversation?.id, conversationMessages.lastOrNull(), activeStream,
        composedMessageIds = { viewport.ends.keys.toSet() }
    )
    val minimapState = remember(conversationKey) { ConversationMinimapSideBarState() }
    LaunchedEffect(isDrawerOpening) {
        if (isDrawerOpening) minimapState.isOpen = false
    }
    // This event survives the null -> newly created conversation transition.
    var firstSendAwaitingAnchor by remember { mutableStateOf(false) }
    fun resumeFollowing() = viewport.resume()
    val copyLabel = stringResource(R.string.common_copy)
    val copiedLabel = stringResource(R.string.common_copied_to_clipboard)
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    val selectedModelDisplayName = selectedProvider?.config?.modelConfigs?.get(selectedModelName)
        ?.displayName?.takeIf { it.isNotBlank() } ?: selectedModelName

    LaunchedEffect(viewport, structuralRevision, conversationMessages.isEmpty(), firstSendAwaitingAnchor, loadingConversationId) {
        if (loadingConversationId != null) return@LaunchedEffect
        val sentId = viewport.sentMessages.observe(conversationMessages, structuralRevision)
            ?: if (firstSendAwaitingAnchor) {
                conversationMessages.lastOrNull { it.role == ChatRole.USER && !it.isQueued }?.id
            } else null
        if (sentId != null) {
            firstSendAwaitingAnchor = false
            viewport.send(sentId)
        }
    }

    var knownQueuedIds by remember(viewport) { mutableStateOf(queuedMessages.map { it.id }.toSet()) }
    LaunchedEffect(viewport, queuedMessages) {
        val added = queuedMessages.lastOrNull { it.id !in knownQueuedIds }
        knownQueuedIds = queuedMessages.map { it.id }.toSet()
        added?.let { viewport.send(it.id) }
    }

    var editDraft by remember(conversation?.id) { mutableStateOf<ChatInputEditDraft?>(null) }
    val selection = LocalChatAttachmentSelection.current ?: remember(conversation?.id) { ChatAttachmentSelection() }
    var inputAttachments by selection.attachments
    val chatModeRequest = selection.requestId
    val toggleChatImage: (String) -> Unit = remember(selection) { selection::toggle }

    // Dialog state for deleting a message
    var deletingMessageId by remember(conversationKey, foreground) { mutableStateOf<String?>(null) }

    var regeneratingMessageId by remember(conversationKey, foreground) { mutableStateOf<String?>(null) }
    var showContextSummarySheet by remember(conversationKey, foreground) { mutableStateOf(false) }
    var showSummaryDialog by remember(conversationKey, foreground) { mutableStateOf(false) }
    // Persistence errors belong to the chat history; composer submission errors stay by InputArea.
    var saveConversationError by remember(conversation?.id) { mutableStateOf<String?>(null) }
    var inlineChatError by remember(conversation?.id) { mutableStateOf<String?>(null) }
    fun submitMessage(action: () -> Boolean): Boolean {
        saveConversationError = null
        inlineChatError = null
        return action().also { accepted ->
            if (accepted) {
                if (conversationMessages.isEmpty()) firstSendAwaitingAnchor = true
                if (!isGenerating) resumeFollowing()
            }
        }
    }
    val lastMessage = conversationMessages.lastOrNull()
    val streamingMessage = if (streamingHere) conversationMessages.lastOrNull { it.role == ChatRole.MODEL } else null
    val lastResponseState = conversationMessages.lastOrNull()?.activeVersion
    val unansweredMessages = remember(
        conversationKey,
        structuralRevision,
        lastResponseState?.generationError,
        lastResponseState?.generationInterrupted,
        lastResponseState?.processingFinishedAt
    ) { unansweredUserMessageIds(conversationMessages) }
    val historyLoading = loadingConversationId != null
    fun whenIdle(action: () -> Unit) {
        if (!isGenerating) action()
    }
    val saveConversationFailedPrefix = stringResource(R.string.chat_save_conversation_failed_prefix)
    LaunchedEffect(chatError, saveConversationFailedPrefix) {
        chatError?.takeIf { it.startsWith(SAVE_CONVERSATION_ERROR_PREFIX) }?.let { error ->
            saveConversationError = saveConversationFailedPrefix + error.removePrefix(SAVE_CONVERSATION_ERROR_PREFIX)
        }
    }
    LaunchedEffect(conversation?.id, foreground) {
        if (foreground) focusManager.clearFocus(force = true)
    }
    LaunchedEffect(listState.isScrollInProgress, foreground) {
        // A parked chat cannot dismiss the visible chat's keyboard.
        if (foreground && listState.isScrollInProgress) keyboardController?.hide()
    }

    val inputBackdrop = rememberInputAreaBackdrop()
    val blurInputBackdrop = LocalInputAreaAppearance.current.blurRadius > 0f && android.os.Build.VERSION.SDK_INT >= 31
    MainScreenTemplate {
    PlatformTextSelection {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { chatRootCoordinates = it }
            // A plain tap anywhere on the chat screen except the input area (messages, blank space, app bar)
            // drops focus: the input field loses focus and any text selection is cleared. Scrolling and dragging
            // do not. Nothing is consumed, and a press held past the long-press timeout is selection, not a tap.
            .pointerInput(focusManager) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.size > 1) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        if (change.changedToUpIgnoreConsumed()) {
                            val inRoot = chatRootCoordinates?.takeIf { it.isAttached }?.localToRoot(down.position)
                            val onInput = inRoot != null && inputAreaBounds?.contains(inRoot) == true
                            if (!onInput) focusManager.clearFocus(force = true)
                            break
                        }
                    }
                }
            }
    ) {
        // Scaffold measures both overlay regions before the first content layout, including
        // composer errors/suggestions and system insets. The list still draws edge to edge.
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                ChatAppBar(
                    modelName = selectedModelDisplayName,
                    providerName = selectedProvider?.name.orEmpty(),
                    onOpenDrawer = {
                        minimapState.isOpen = false
                        onOpenDrawer()
                    },
                    onNewChat = onNewChat,
                    onOpenTemporaryChat = onOpenTemporaryChat,
                    onOpenSettings = onOpenSettings,
                    featuresLogoImage = featuresLogoImage,
                    isTemporaryChat = conversationMessages.isEmpty() && conversation?.isTemporary != true,
                    onOpenModelPicker = {
                        minimapState.isOpen = false
                        bottomSheets.openModelPicker()
                    },
                    modifier = Modifier
                )
            },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                    if (messages.isNotEmpty() && viewport.showJumpToLatest) {
                        FilledTonalIconButton(
                            onClick = { viewport.jump() },
                            modifier = Modifier
                        ) {
                            SharedIcon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.chat_jump_to_latest),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .testTag("chat-bottom-panel")
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    inlineChatError?.let { error ->
                        ErrorContainer(
                            message = error,
                            onDismiss = { inlineChatError = null },
                            expandable = false,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    var summarySuggestionDismissed by remember(conversation?.id, conversation?.summary?.id) { mutableStateOf(false) }
                    if (contextWindowStatus?.shouldSuggestSummary == true && !streamingHere && summaryProgress == null &&
                        !summarySuggestionDismissed &&
                        org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences.current.summarySuggestionEnabled) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(androidx.compose.ui.res.stringResource(org.starfall.generative.R.string.context_summary_suggestion,
                                    contextWindowStatus.percentage), style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = { showContextSummarySheet = true }) {
                                    Text(androidx.compose.ui.res.stringResource(org.starfall.generative.R.string.summarize_now))
                                }
                                IconButton(onClick = { summarySuggestionDismissed = true }, modifier = Modifier.size(32.dp)) {
                                    SharedIcon(Icons.Default.Close,
                                        androidx.compose.ui.res.stringResource(org.starfall.generative.R.string.common_close),
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    // Composer state must not escape a disposable chat when switching sessions.
                    key(if (conversation?.isTemporary == true) conversationKey else "regular_composer") {
                    UserInputArea(
                        backdrop = inputBackdrop,
                        temporaryChatId = conversation?.takeIf { it.isTemporary }?.id,
                        clipboardSessionKey = conversationKey,
                        attachments = inputAttachments,
                        onAttachmentsChange = { inputAttachments = it },
                        onAddClipboardImages = { images -> inputAttachments = (inputAttachments + images).distinct() },
                        chatModeRequest = chatModeRequest,
                        modifier = Modifier
                            .onGloballyPositioned { inputAreaBounds = it.boundsInRoot() },
                        isGenerating = isGenerating,
                        onMediaModeChange = onMediaModeChange,
                        onSendMedia = { request -> submitMessage { onSendMedia(request) } },
                        onSendMessage = { text, files -> submitMessage { onSendMessage(text, files) } },
                        onSendMessageWithSkill = { text, files, requested, skillName ->
                            submitMessage { onSendMessageWithSkill(text, files, requested, skillName) }
                        },
                        onEditMessage = { id, text, files ->
                            val isQueued = editDraft?.isQueued == true
                            if (isQueued) {
                                onEditQueuedMessage(id, text, files)
                            } else {
                                saveConversationError = null
                                inlineChatError = null
                                onEditMessage(id, text, files).also { if (it) resumeFollowing() }
                            }
                        },
                        onSubmissionFailed = { inlineChatError = it },
                        editDraft = editDraft,
                        onCancelEdit = { editDraft = null },
                        onStopGenerating = onStopGenerating,
                        contextWindowStatus = contextWindowStatus,
                        onStartSummary = onStartConversationSummary,
                        selectedModelName = selectedModelName,
                        providers = providers,
                        providerGroups = providerGroups,
                        modelPickerCollapsedGroups = modelPickerCollapsedGroups,
                        modelPickerCollapsedProviders = modelPickerCollapsedProviders,
                        onModelPickerCollapsedGroupsChange = onModelPickerCollapsedGroupsChange,
                        onModelPickerCollapsedProvidersChange = onModelPickerCollapsedProvidersChange,
                         bottomSheets = bottomSheets,
                        selectedProviderId = selectedProviderId,
                        onSelectModel = onSelectModel,
                        conversationReasoningEffort = conversation?.reasoningEffort,
                        onSetReasoningEffort = onSetReasoningEffort,
                        onSetThinkingPassback = onSetThinkingPassback,
                         onSetModelReasoningEffort = onSetModelReasoningEffort,
                         onSetModelTools = onSetModelTools,
                         onEditModel = onEditModel,
                         onEditProvider = onEditProvider,
                         onFetchOllamaModels = onFetchOllamaModels,
                         onOpenFeatureToolsSettings = onOpenFeatureToolsSettings,
                          onOpenMcpConnectorsSettings = onOpenMcpConnectorsSettings,
                          agentSkills = agentSkills,
                          onOpenSkillsSettings = onOpenSkillsSettings,
                         onModelSheetOpen = { minimapState.isOpen = false }
                      )
                    }
                }

            }
        ) { insets ->
        // Let the list occupy the whole edge-to-edge surface. Its top padding keeps the newest
        // content readable initially, while later scrolls visibly pass behind the translucent app bar.
        BoxWithConstraints(
            Modifier.fillMaxSize().clipToBounds()
                .inputAreaBackdropSource(inputBackdrop, blurInputBackdrop)
                .background(MaterialTheme.colorScheme.background)
                .minimapEdgeSwipe(minimapState, enabled = foreground && messages.isNotEmpty() && !isDrawerOpening)
        ) {
        org.starfall.generative.shared.ui.background.ProfileBackground(backgroundImage, transparency = backgroundTransparency)
        val appBarHeight = insets.calculateTopPadding()
        val bottomClearance = insets.calculateBottomPadding() + maxHeight * 0.015f
        if (messages.isEmpty() && saveConversationError == null) {
            ChatOpeningVeil(
                active = historyLoading,
                modifier = Modifier.fillMaxSize().padding(top = appBarHeight + 8.dp, bottom = bottomClearance)
            ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = if (conversation?.isTemporary == true)
                            stringResource(R.string.chat_temporary_title) else stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (conversation?.isTemporary == true)
                            stringResource(R.string.chat_temporary_description) else
                            stringResource(R.string.connect_providers_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            if (isGenerating && !streamingHere) {
                Text(
                    stringResource(R.string.chat_error_other_conversation),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 12.dp, end = 12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            }
        } else {
            PlatformTextSelectionRegion {
            ChatMessageList(
                state = viewport,
                messages = messages,
                streaming = streamingHere,
                autoScroll = autoScroll,
                historyLoading = historyLoading,
                activeStream = activeStream,
                appBarHeight = appBarHeight + 8.dp,
                bottomClearance = bottomClearance,
                modifier = Modifier.fillMaxSize(),
                footer = if ((isGenerating && !streamingHere) || saveConversationError != null) {
                    {
                        if (isGenerating && !streamingHere) {
                            Text(stringResource(R.string.chat_error_other_conversation),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        saveConversationError?.let { error ->
                            ErrorContainer(
                                message = error,
                                onDismiss = { saveConversationError = null },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("chat-save-error")
                            )
                        }
                    }
                } else null
            ) { msg ->
                    val isLast = msg.id == lastMessage?.id
                    if (msg.role == ChatRole.USER) {
                        UserMessageCard(
                            message = msg,
                            conversationId = conversation?.id,
                            cacheEnabled = conversation?.isTemporary != true,
                            selectedImageAttachments = inputAttachments,
                            onToggleChatImage = toggleChatImage,
                            onResend = if (msg.id in unansweredMessages) rememberChatAction {
                                if (onResendUserMessage(msg.id)) resumeFollowing()
                            } else null,
                            onResendFromMenu = if (!msg.isQueued) rememberChatAction {
                                if (onResendUserMessage(msg.id)) resumeFollowing()
                            } else null,
                            resendEnabled = !isGenerating && summaryProgress == null &&
                                selectedModelName.isNotBlank() && providers.any { it.id == selectedProviderId },
                            onEdit = rememberChatValueAction<String> {
                                if (msg.isQueued) {
                                    editDraft = ChatInputEditDraft(
                                        messageId = msg.id,
                                        text = msg.content,
                                        attachments = msg.files,
                                        isQueued = true,
                                        revision = System.nanoTime()
                                    )
                                } else {
                                    editDraft = ChatInputEditDraft(
                                        messageId = msg.id,
                                        text = msg.content,
                                        attachments = msg.files,
                                        revision = System.nanoTime()
                                    )
                                }
                            },
                            onDelete = rememberChatValueAction<String> {
                                if (msg.isQueued) {
                                    onDeleteQueuedMessage(msg.id)
                                } else {
                                    deletingMessageId = msg.id
                                }
                            },
                            onSwitchVersion = rememberChatValueAction { newIdx: Int ->
                                if (!msg.isQueued) {
                                    whenIdle { onSwitchVersion(msg.id, newIdx) }
                                }
                            },
                            modifier = if (msg.isQueued) Modifier.alpha(0.5f) else Modifier
                        )
                    } else {
                        AssistantMessageCard(
                            message = msg,
                            isReading = speakingMessageId == msg.id,
                            fallbackModelName = providers.find { it.id == conversation?.providerId }
                                ?.config?.modelConfigs?.get(conversation?.modelId)?.displayName
                                ?.ifBlank { conversation?.modelId.orEmpty() } ?: conversation?.modelId.orEmpty(),
                            selectedImageAttachments = inputAttachments,
                            onToggleChatImage = toggleChatImage,
                            isStreaming = streamingHere && msg.id == streamingMessage?.id,
                            conversationId = conversation?.id,
                            cacheEnabled = conversation?.isTemporary != true,
                            streamStateFlow = activeStream,
                            onCopy = rememberChatAction {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, assistantCopyText(msg)))
                                Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                            },
                            onRegenerate = rememberChatAction {
                                whenIdle {
                                    if (!isLast) regeneratingMessageId = msg.id
                                    else onRegenerate(msg.id)
                                }
                            },
                            onRetryError = if (isLast) {
                                rememberChatAction { whenIdle { onRetryFromError(msg.id) } }
                            } else null,
                            onEdit = rememberChatAction {
                                editDraft = ChatInputEditDraft(
                                    messageId = msg.id,
                                    text = assistantCopyText(msg),
                                    attachments = msg.files,
                                    revision = System.nanoTime()
                                )
                            },
                            onDeleteBlock = rememberChatValueAction<Int> { block ->
                                whenIdle { onDeleteAssistantBlock(msg.id, block) }
                            },
                            onDelete = rememberChatAction {
                                deletingMessageId = msg.id
                            },
                            onRead = rememberChatAction {
                                onReadMessage(msg.id, msg.content.ifBlank { msg.reasoningContent.orEmpty() })
                            },
                            onSwitchVersion = rememberChatValueAction { newIdx: Int ->
                                whenIdle { onSwitchVersion(msg.id, newIdx) }
                            }
                        )
                    }

                    val progressHere = summaryProgress?.takeIf {
                        it.conversationId == conversation?.id && it.throughMessageId == msg.id
                    }
                    val summaryHere = conversation?.summary?.takeIf {
                        it.throughMessageId == msg.id && progressHere == null
                    }
                    if (progressHere != null || summaryHere != null) {
                        ConversationSummaryDivider(
                            summary = summaryHere,
                            progress = progressHere,
                            onOpenSummary = { showSummaryDialog = true },
                            onCancelSummary = onCancelConversationSummary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
            }
            }
        }

        ConversationMinimapSideBar(
            conversationKey = conversationKey,
            messages = messages,
            listState = listState,
            topObscuredPx = with(density) { appBarHeight.roundToPx() },
            bottomObscuredPx = with(density) { (insets.calculateBottomPadding() + 8.dp).roundToPx() },
            // Dragging the thumb is manual scrolling: it stops following and resumes at the end.
            onScrub = { towardsEnd -> viewport.manualScroll(towardsEnd) },
            state = minimapState
        )
        }
        }

    }
    }

    }

    if (foreground && showContextSummarySheet) {
        ConversationSummarySheet(onStart = onStartConversationSummary, onDismiss = { showContextSummarySheet = false })
    }

    if (foreground && showSummaryDialog) {
        conversation?.summary?.let { summary ->
            ConversationSummaryDialog(
                summary = summary,
                onRoleChange = onSummaryRoleChange,
                onDelete = onDeleteSummary,
                onDismiss = { showSummaryDialog = false }
            )
        } ?: run { showSummaryDialog = false }
    }

    if (foreground && regeneratingMessageId != null) {
        RegenerateResponseDialog(
            onConfirm = {
                regeneratingMessageId?.let(onRegenerate)
                regeneratingMessageId = null
            },
            onDismiss = { regeneratingMessageId = null }
        )
    }

    // Delete message/version confirmation dialog
    val deletingMessage = deletingMessageId?.let { id -> messages.firstOrNull { it.id == id } }
    if (foreground && deletingMessage != null) {
        DeleteMessageDialog(
            message = deletingMessage,
            onDeleteVersion = {
                onDeleteMessageVersion(deletingMessage.id)
                deletingMessageId = null
            },
            onDeleteAll = {
                onDeleteMessage(deletingMessage.id)
                deletingMessageId = null
            },
            onDismiss = { deletingMessageId = null }
        )
    }
}
