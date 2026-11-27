package org.starfall.generative.features.home.chat.ui

import org.starfall.generative.R
import org.starfall.generative.features.home.BottomSheetsLinkState

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
import androidx.compose.ui.graphics.Color
import org.starfall.generative.shared.ui.text.PlatformTextSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.ConversationSummary
import org.starfall.generative.shared.model.ConversationSummaryProgress
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.SummaryRole
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.layout.rememberInputAreaBackdrop
import org.starfall.generative.shared.ui.layout.inputAreaBackdropSource
import org.starfall.generative.shared.ui.layout.inputAreaFooterSurface
import org.starfall.generative.shared.ui.layout.LocalInputAreaAppearance
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.features.home.chat.logic.input.ChatInputEditDraft
import org.starfall.generative.features.home.chat.logic.input.DirectMediaRequest
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.defaultAgentSkills
import org.starfall.generative.features.home.chat.ui.input.UserInputArea
import org.starfall.generative.features.home.shared.ui.ConversationSummarySheet
import org.starfall.generative.features.home.chat.model.ContextWindowStatus
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState

/** Complete chat presentation. The host supplies snapshots, stream state and actions. */
@Composable
fun ChatInterface(
    conversationId: String? = null,
    conversationMessages: List<StoredMessage> = emptyList(),
    structuralRevision: Long? = null,
    isTemporary: Boolean = false,
    conversationReasoningEffort: String? = null,
    summary: ConversationSummary? = null,
    fallbackModelName: String = "",
    isGenerating: Boolean = false,
    streamingHere: Boolean = false,
    saveError: String? = null,
    providers: List<ModelProvider> = emptyList(),
    mediaProviders: List<ModelProvider>? = null,
    providerGroups: List<ProviderGroup> = emptyList(),
    modelPickerCollapsedGroups: Set<String> = emptySet(),
    modelPickerCollapsedProviders: Set<String> = emptySet(),
    onModelPickerCollapsedGroupsChange: (Set<String>) -> Unit = {},
    onModelPickerCollapsedProvidersChange: (Set<String>) -> Unit = {},
    selectedProviderId: String = "",
    selectedModelName: String = "",
    onSendMessage: (String, List<String>) -> Boolean = { _, _ -> false },
    onSendMedia: (DirectMediaRequest) -> Boolean = { false },
    onMediaModeChange: (ModelType?, String, String) -> Unit = { _, _, _ -> },
    onStopGenerating: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onOpenTemporaryChat: () -> Unit = onNewChat,
    onOpenSettings: () -> Unit = {},
    featuresLogoImage: String? = null,
    workspaceLabel: String? = null,
    showTemporaryChatAction: Boolean = true,
    backgroundImage: String? = null,
    backgroundTransparency: Float = 0.5f,
    onRegenerate: (String) -> Unit = {},
    onRetryFromError: (String) -> Unit = {},
    onEditMessage: (messageId: String, newContent: String, files: List<String>) -> Boolean = { _, _, _ -> false },
    onDeleteAssistantBlock: (String, Int) -> Unit = { _, _ -> },
    onDeleteMessage: (messageId: String) -> Unit = {},
    onDeleteMessageVersion: (messageId: String) -> Unit = {},
    onSwitchVersion: (messageId: String, versionIndex: Int) -> Unit = { _, _ -> },
    onSelectModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    summaryProgress: ConversationSummaryProgress? = null,
    onSetReasoningEffort: (String?) -> Unit = {},
    onSetThinkingPassback: (providerId: String, modelId: String, mode: ThinkingPassback) -> Unit = { _, _, _ -> },
    onSetModelReasoningEffort: (providerId: String, modelId: String, effort: String?) -> Unit = { _, _, _ -> },
    onSetModelTools: (providerId: String, modelId: String, tools: List<String>) -> Unit = { _, _, _ -> },
    onEditModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    onEditProvider: (providerId: String) -> Unit = {},
    onStartConversationSummary: (ConversationSummaryRequest) -> Boolean = { false },
    onCancelConversationSummary: () -> Unit = {},
    onSummaryRoleChange: (SummaryRole) -> Unit = {},
    onDeleteSummary: () -> Unit = {},
    onReadMessage: (String, String) -> Unit = { _, _ -> },
    dynamicModels: Map<String, List<String>> = emptyMap(),
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
    onSendMessageWithSkill: suspend (String, List<String>, Boolean, String?) -> Boolean = { text, files, _, _ -> onSendMessage(text, files) },
    onOpenSkillsSettings: (() -> Unit)? = null,
    isDrawerOpening: Boolean = false,
    restoreViewport: Boolean = false,
    workspaceNotice: String? = null,
    workspaceStatus: String? = null,
    sendEnabled: Boolean = true,
) {
    SideEffect { ChatRenderMetrics.recordChatScreenRecomposition() }
    var chatRootCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var inputAreaBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // Tool activity and reasoning updates do not necessarily advance updatedAt or the
    // structural revision. Key by the actual message snapshot so live Actions become visible.
    val messages = remember(conversationMessages, queuedMessages) {
        conversationMessages + queuedMessages
    }

    val conversationKey = conversationId ?: "__empty_conversation__"
    val foreground = LocalChatForeground.current
    // Keep sheet navigation only for the current foreground chat; parked pages retain
    // their scroll/composer state but receive a fresh, closed sheet state on return.
    val bottomSheets = remember(conversationKey, foreground) { BottomSheetsLinkState() }
    var savedScrollIndex by rememberSaveable(conversationKey) { mutableIntStateOf(-1) }
    var savedScrollOffset by rememberSaveable(conversationKey) { mutableIntStateOf(0) }
    // Capture the saved position once: on a new chat the first message must still open at the end.
    val restoredPosition = remember(conversationKey) {
        savedScrollIndex.takeIf { restoreViewport && it >= 0 }?.let { it to savedScrollOffset }
    }
    val viewport = remember(conversationKey, messages.isEmpty()) {
        ChatMessageListState(initialMessages = messages,
            restoredScrollIndex = restoredPosition?.first, restoredScrollOffset = restoredPosition?.second ?: 0)
    }
    LaunchedEffect(viewport, restoreViewport, foreground) {
        if (!restoreViewport || !foreground) return@LaunchedEffect
        snapshotFlow { Triple(viewport.listState.isScrollInProgress,
            viewport.listState.firstVisibleItemIndex, viewport.listState.firstVisibleItemScrollOffset) }
            .collect { (scrolling, index, offset) ->
                if (!scrolling && viewport.listState.layoutInfo.totalItemsCount > 0) {
                    savedScrollIndex = index
                    savedScrollOffset = offset
                }
            }
    }
    val listState = viewport.listState
    org.starfall.generative.features.home.chat.ui.markdown.PrepareStreamingMarkdown(
        conversationId, conversationMessages.lastOrNull(), activeStream,
        composedMessageIds = { viewport.ends.keys.toSet() }
    )
    val minimapState = remember(conversationKey) { ConversationMinimapSideBarState() }
    LaunchedEffect(isDrawerOpening) {
        if (isDrawerOpening) minimapState.isOpen = false
    }
    // This event survives the null -> newly created conversation transition.
    var firstSendAwaitingAnchor by remember { mutableStateOf(false) }
    fun resumeFollowing() = viewport.resume()
    val focusManager = LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    val selectedModelDisplayName = selectedProvider?.config?.modelConfigs?.get(selectedModelName)
        ?.displayName?.takeIf { it.isNotBlank() } ?: selectedModelName

    val unversionedMessages = conversationMessages.takeIf { structuralRevision == null }
    LaunchedEffect(viewport, structuralRevision, unversionedMessages, conversationMessages.isEmpty(), firstSendAwaitingAnchor, loadingConversationId) {
        if (loadingConversationId != null) return@LaunchedEffect
        val sentId = (if (structuralRevision != null) viewport.sentMessages.observe(conversationMessages, structuralRevision)
            else viewport.sentMessages.observe(conversationMessages))
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

    var editDraft by remember(conversationId) { mutableStateOf<ChatInputEditDraft?>(null) }
    val selection = LocalChatAttachmentSelection.current ?: remember(conversationId) { ChatAttachmentSelection() }
    var inputAttachments by selection.attachments
    val chatModeRequest = selection.requestId
    val toggleChatImage: (String) -> Unit = remember(selection) { selection::toggle }

    var showContextSummarySheet by remember(conversationKey, foreground) { mutableStateOf(false) }
    // Persistence errors belong to the chat history; composer submission errors stay by InputArea.
    var saveConversationError by remember(conversationId) { mutableStateOf<String?>(null) }
    var inlineChatError by remember(conversationId) { mutableStateOf<String?>(null) }
    var noticeDismissed by remember(workspaceNotice) { mutableStateOf(false) }
    fun clearSubmissionErrors() {
        saveConversationError = null
        inlineChatError = null
        noticeDismissed = false
    }
    fun onSubmissionResult(accepted: Boolean): Boolean {
        if (accepted) {
            if (conversationMessages.isEmpty()) firstSendAwaitingAnchor = true
            if (!isGenerating) resumeFollowing()
        }
        return accepted
    }
    fun submitMessage(action: () -> Boolean): Boolean {
        clearSubmissionErrors()
        return onSubmissionResult(action())
    }
    LaunchedEffect(saveError) {
        saveError?.let { saveConversationError = it }
    }
    LaunchedEffect(conversationId, foreground) {
        if (foreground) focusManager.clearFocus(force = true)
    }
    LaunchedEffect(listState.isScrollInProgress, foreground) {
        // A parked chat cannot dismiss the visible chat's keyboard.
        if (foreground && listState.isScrollInProgress) keyboardController?.hide()
    }

    val inputBackdrop = rememberInputAreaBackdrop()
    val blurInputBackdrop = LocalInputAreaAppearance.current.hasBackdropBlur
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
                    providerName = if (workspaceLabel == null) selectedProvider?.name.orEmpty()
                        else listOfNotNull(workspaceLabel, selectedProvider?.name?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    onOpenDrawer = {
                        minimapState.isOpen = false
                        onOpenDrawer()
                    },
                    onNewChat = onNewChat,
                    onOpenTemporaryChat = onOpenTemporaryChat,
                    onOpenSettings = onOpenSettings,
                    featuresLogoImage = featuresLogoImage,
                    isTemporaryChat = showTemporaryChatAction && conversationMessages.isEmpty() && !isTemporary,
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
                        .inputAreaFooterSurface()
                        .navigationBarsPadding()
                ) {
                    if (workspaceStatus != null && conversationId != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LoadingIndicator(Modifier.size(24.dp))
                            Text(workspaceStatus, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    (inlineChatError ?: workspaceNotice.takeUnless { noticeDismissed })?.let { error ->
                        ErrorContainer(
                            message = error,
                            onDismiss = {
                                if (inlineChatError != null) inlineChatError = null
                                else noticeDismissed = true
                            },
                            expandable = false,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    var summarySuggestionDismissed by remember(conversationId, summary?.id) { mutableStateOf(false) }
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
                    key(if (isTemporary) conversationKey else "regular_composer") {
                    if (workspaceStatus != null && conversationId == null) return@key
                    UserInputArea(
                        backdrop = inputBackdrop,
                        sendEnabled = sendEnabled,
                        temporaryChatId = conversationId.takeIf { isTemporary },
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
                            clearSubmissionErrors()
                            onSubmissionResult(onSendMessageWithSkill(text, files, requested, skillName))
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
                        mediaProviders = mediaProviders,
                        providerGroups = providerGroups,
                        modelPickerCollapsedGroups = modelPickerCollapsedGroups,
                        modelPickerCollapsedProviders = modelPickerCollapsedProviders,
                        onModelPickerCollapsedGroupsChange = onModelPickerCollapsedGroupsChange,
                        onModelPickerCollapsedProvidersChange = onModelPickerCollapsedProvidersChange,
                         bottomSheets = bottomSheets,
                        selectedProviderId = selectedProviderId,
                        onSelectModel = onSelectModel,
                        conversationReasoningEffort = conversationReasoningEffort,
                        onSetReasoningEffort = onSetReasoningEffort,
                        onSetThinkingPassback = onSetThinkingPassback,
                         onSetModelReasoningEffort = onSetModelReasoningEffort,
                         onSetModelTools = onSetModelTools,
                         onEditModel = onEditModel,
                         onEditProvider = onEditProvider,
                         dynamicModels = dynamicModels,
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
                if (workspaceStatus != null && conversationId == null) {
                    Column(Modifier.align(Alignment.Center).padding(insets),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        Text(workspaceStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                    return@BoxWithConstraints
                }
                val appBarHeight = insets.calculateTopPadding()
                val bottomClearance = insets.calculateBottomPadding() + maxHeight * 0.015f
                ChatTranscript(
                    conversationId = conversationId,
                    conversationKey = conversationKey,
                    foreground = foreground,
                    conversationMessages = conversationMessages,
                    messages = messages,
                    structuralRevision = structuralRevision,
                    isTemporary = isTemporary,
                    isGenerating = isGenerating,
                    streamingHere = streamingHere,
                    activeStream = activeStream,
                    autoScroll = autoScroll,
                    historyLoading = loadingConversationId != null,
                    viewport = viewport,
                    minimapState = minimapState,
                    appBarHeight = appBarHeight,
                    bottomClearance = bottomClearance,
                    minimapBottomObscured = insets.calculateBottomPadding() + 8.dp,
                    fallbackModelName = fallbackModelName,
                    speakingMessageId = speakingMessageId,
                    inputAttachments = inputAttachments,
                    toggleChatImage = toggleChatImage,
                    resendEnabled = !isGenerating && summaryProgress == null && selectedModelName.isNotBlank() &&
                        providers.any { it.id == selectedProviderId },
                    saveConversationError = saveConversationError,
                    onDismissSaveError = { saveConversationError = null },
                    summary = summary,
                    summaryProgress = summaryProgress,
                    onCancelConversationSummary = onCancelConversationSummary,
                    onSummaryRoleChange = onSummaryRoleChange,
                    onDeleteSummary = onDeleteSummary,
                    onEditDraft = { editDraft = it },
                    onResendUserMessage = onResendUserMessage,
                    onDeleteQueuedMessage = onDeleteQueuedMessage,
                    onSwitchVersion = onSwitchVersion,
                    onRegenerate = onRegenerate,
                    onRetryFromError = onRetryFromError,
                    onDeleteAssistantBlock = onDeleteAssistantBlock,
                    onReadMessage = onReadMessage,
                    onDeleteMessageVersion = onDeleteMessageVersion,
                    onDeleteMessage = onDeleteMessage,
                )
            }
        }
    }
    }

    if (foreground && showContextSummarySheet) {
        ConversationSummarySheet(onStart = onStartConversationSummary, onDismiss = { showContextSummarySheet = false })
    }
}
