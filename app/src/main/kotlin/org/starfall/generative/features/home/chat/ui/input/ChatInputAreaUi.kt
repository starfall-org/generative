@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.starfall.generative.features.home.chat.ui.input

import androidx.compose.ui.window.Popup
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import org.starfall.generative.R
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.model.DEFAULT_AGENT_SKILL_ID
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.defaultAgentSkills
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.DEFAULT_REASONING_EFFORTS
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.layout.InputAreaBackdrop
import org.starfall.generative.shared.ui.layout.InputAreaBackdropLayer
import org.starfall.generative.shared.ui.layout.LocalInputAreaAppearance
import org.starfall.generative.shared.theme.LocalAmoledMode
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.model.localizedReasoningEffortLabels
import org.starfall.generative.shared.ui.text.selectAllOnTripleClick
import org.starfall.generative.shared.ui.icons.EntityIcon
import org.starfall.generative.shared.ui.model.reasoningEffortIndex
import org.starfall.generative.shared.ui.model.reasoningEffortOptions
import org.starfall.generative.shared.ui.model.reasoningEffortTier
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.language.supportedReasoningEfforts
import org.starfall.generative.features.home.chat.logic.input.ChatInputEditDraft
import org.starfall.generative.features.home.chat.logic.input.DirectMediaRequest
import org.starfall.generative.features.home.chat.logic.input.MAX_CLIPBOARD_IMAGE_BYTES
import org.starfall.generative.features.home.chat.logic.input.clipboardImageUris
import org.starfall.generative.features.home.chat.model.ContextWindowStatus
import org.starfall.generative.features.home.chat.logic.input.canSendChatInput
import org.starfall.generative.features.home.chat.logic.input.directMediaHint
import org.starfall.generative.features.home.chat.logic.input.filterMediaProvidersForKind
import org.starfall.generative.features.home.chat.ui.AttachmentStrip
import org.starfall.generative.features.home.chat.ui.input.AgentSkillsMenu
import org.starfall.generative.features.home.chat.ui.input.agentSkillMenuQuery
import org.starfall.generative.features.home.chat.ui.ContextWindowBar
import org.starfall.generative.features.home.chat.ui.ContextWindowRing
import org.starfall.generative.features.home.BottomSheetsLink
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.features.home.BottomSheetsLinkState
import org.starfall.generative.features.home.chat.ui.LocalChatForeground
import org.starfall.generative.shared.ui.media.LocalChatAttachmentSelection
import org.starfall.generative.shared.ui.model.modelInitial

@Composable
internal fun UserInputArea(
    backdrop: InputAreaBackdrop? = null,
    isGenerating: Boolean,
    onSendMessage: (String, List<String>) -> Boolean,
    onSendMedia: (DirectMediaRequest) -> Boolean,
    onMediaModeChange: (ModelType?, String, String) -> Unit = { _, _, _ -> },
    onEditMessage: (String, String, List<String>) -> Boolean,
    onSubmissionFailed: (String) -> Unit = {},
    editDraft: ChatInputEditDraft?,
    onCancelEdit: () -> Unit,
    onStopGenerating: () -> Unit,
    contextWindowStatus: ContextWindowStatus? = null,
    onStartSummary: ((ConversationSummaryRequest) -> Boolean)? = null,
    selectedModelName: String,
    providers: List<ModelProvider>,
    providerGroups: List<ProviderGroup> = emptyList(),
    modelPickerCollapsedGroups: Set<String> = emptySet(),
    modelPickerCollapsedProviders: Set<String> = emptySet(),
    onModelPickerCollapsedGroupsChange: (Set<String>) -> Unit = {},
    onModelPickerCollapsedProvidersChange: (Set<String>) -> Unit = {},
    bottomSheets: BottomSheetsLinkState,
    selectedProviderId: String,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    conversationReasoningEffort: String?,
    onSetReasoningEffort: (String?) -> Unit,
    onSetThinkingPassback: (providerId: String, modelId: String, mode: ThinkingPassback) -> Unit = { _, _, _ -> },
    onSetModelReasoningEffort: (providerId: String, modelId: String, effort: String?) -> Unit = { _, _, _ -> },
    onSetModelTools: (providerId: String, modelId: String, tools: List<String>) -> Unit = { _, _, _ -> },
    onEditModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    onEditProvider: (providerId: String) -> Unit = {},
    onFetchOllamaModels: (suspend (String) -> List<String>)? = null,
    attachments: List<String> = emptyList(),
    onAttachmentsChange: (List<String>) -> Unit = {},
    // Append to the owner's latest state; asynchronous clipboard imports must not overwrite other attachments.
    onAddClipboardImages: (List<String>) -> Unit = { images -> onAttachmentsChange((attachments + images).distinct()) },
    onOpenFeatureToolsSettings: () -> Unit = {},
    onOpenMcpConnectorsSettings: () -> Unit = {},
    chatModeRequest: Int = 0,
    temporaryChatId: String? = null,
    clipboardSessionKey: String? = null,
    modifier: Modifier = Modifier,
    agentSkills: List<AgentSkill> = defaultAgentSkills(),
    onSendMessageWithSkill: (String, List<String>, Boolean, String?) -> Boolean = { text, files, _, _ -> onSendMessage(text, files) },
    onOpenSkillsSettings: (() -> Unit)? = null,
    onModelSheetOpen: () -> Unit = {}
) {
    val textFieldState = rememberTextFieldState()
    // Picker provenance controls activation; the composer shows only /skill-name.
    var selectedSkillId by remember { mutableStateOf<String?>(null) }
    val selectedSkill = agentSkills.firstOrNull { it.id == selectedSkillId }
    // The field may only take focus after the user touches it. Returning from another page (for example the
    // terminal, whose Android view held the window focus) hands the focus back to Compose, which would otherwise
    // move it into the only focusable item here and pop the keyboard up.
    var inputFocusAllowed by remember { mutableStateOf(false) }
    val textState = textFieldState.text.toString()
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val externalSelection = LocalChatAttachmentSelection.current
    val externalText = externalSelection?.pendingExternalText
    LaunchedEffect(externalSelection, externalText) {
        val incoming = externalSelection?.takeExternalText() ?: return@LaunchedEffect
        val combined = listOf(textFieldState.text.toString().takeIf { it.isNotEmpty() }, incoming)
            .filterNotNull().joinToString("\n\n")
        selectedSkillId = null
        textFieldState.setTextAndPlaceCursorAtEnd(combined)
    }
    val toolFiles = remember(context) { ToolFiles(context) }
    val latestOnAddClipboardImages by rememberUpdatedState(onAddClipboardImages)
    val latestOnSubmissionFailed by rememberUpdatedState(onSubmissionFailed)
    val latestTemporaryChatId by rememberUpdatedState(temporaryChatId)
    val latestClipboardSessionKey by rememberUpdatedState(clipboardSessionKey)
    val clipboardContentListener = remember(context, toolFiles, coroutineScope) {
        ReceiveContentListener { content ->
            val clip = content.clipEntry.clipData
            val images = clipboardImageUris(clip) { uri -> context.contentResolver.getType(uri) }
            if (images.isEmpty()) {
                content // Let Compose handle ordinary text pastes.
            } else {
                val sessionId = latestTemporaryChatId
                val sessionKey = latestClipboardSessionKey
                // Clipboard URI grants are temporary, so copy the bytes into app-private storage.
                coroutineScope.launch {
                    val fileContext = sessionId?.let { org.starfall.generative.shared.files.TemporaryChatFiles(it) }
                        ?: kotlin.coroutines.EmptyCoroutineContext
                    val (saved, failures) = withContext(Dispatchers.IO + fileContext) {
                        val saved = mutableListOf<String>()
                        var failures = 0
                        images.forEach { uri ->
                            val reference = try {
                                val mime = context.contentResolver.getType(uri)
                                    ?: (0 until clip.description.mimeTypeCount)
                                        .map { clip.description.getMimeType(it) }
                                        .firstOrNull { it.startsWith("image/") }
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    toolFiles.saveUpload(input, mime, MAX_CLIPBOARD_IMAGE_BYTES)
                                }?.let { name -> toolFiles.resolve(name)?.absolutePath }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            if (reference != null) saved += reference else failures++
                        }
                        saved to failures
                    }
                    if (latestTemporaryChatId == sessionId && latestClipboardSessionKey == sessionKey) {
                        if (saved.isNotEmpty()) latestOnAddClipboardImages(saved.distinct())
                        if (failures > 0) {
                            latestOnSubmissionFailed(
                                localizedResources.getString(R.string.media_image_open_failed)
                            )
                        }
                    }
                }
                // Only consume image URIs; text and unrelated items still reach the text field.
                content.consume { item -> item.uri != null && item.uri in images }
            }
        }
    }
    var inputRowWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val inputTextStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 22.sp
    )
    // Measure against the horizontal layout so switching to wider text does not toggle back.
    val horizontalTextWidthPx = inputRowWidthPx - with(density) { (48.dp + 50.dp + 48.dp + 16.dp).roundToPx() }
    val inputLineCount = if (horizontalTextWidthPx > 0) textMeasurer.measure(
        text = textState,
        style = inputTextStyle,
        constraints = Constraints(maxWidth = horizontalTextWidthPx),
        maxLines = 6
    ).lineCount else 1
    val stackInputActions = attachments.isNotEmpty() || inputLineCount >= 3
    // From five lines on, the buttons drop below the text so it can use the full width.
    val buttonsBelow = inputLineCount >= 5
    // The whole input grows to half the screen for text only, 60% when attachments are shown.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxInputHeight = (screenHeightDp * if (attachments.isNotEmpty()) 0.6f else 0.5f).dp
    val thinkingProvider = providers.firstOrNull { it.id == selectedProviderId }
    val thinkingConfig = thinkingProvider?.config?.modelConfigs?.get(selectedModelName)
    val thinkingEfforts = thinkingConfig?.supportedReasoningEfforts ?: DEFAULT_REASONING_EFFORTS
    val reasoningLabels = localizedReasoningEffortLabels()
    val thinkingOptions = remember(thinkingEfforts, reasoningLabels) {
        reasoningEffortOptions(thinkingEfforts, reasoningLabels)
    }
    val currentEffort = conversationReasoningEffort ?: thinkingConfig?.reasoningEffort
    val amoledMode = LocalAmoledMode.current
    val maximumThinking = thinkingConfig?.supportsThinking == true &&
        reasoningEffortTier(reasoningEffortIndex(currentEffort, thinkingOptions), thinkingOptions) == 6
    val inputColor by animateColorAsState(
        if (maximumThinking) lerp(MaterialTheme.colorScheme.surfaceContainerHigh, Color(0xFFF97316), 0.12f)
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(1400), label = "thinking-input-color"
    )
    var mediaKind by rememberSaveable { mutableStateOf<ModelType?>(null) }
    LaunchedEffect(chatModeRequest) {
        if (chatModeRequest > 0) mediaKind = null
    }
    var mediaProviderId by rememberSaveable { mutableStateOf("") }
    var mediaModelId by rememberSaveable { mutableStateOf("") }
    val mediaProviders = filterMediaProvidersForKind(providers, mediaKind)
    val mediaProvider = mediaProviders.find { it.id == mediaProviderId }
    val mediaModel = mediaProvider?.config?.modelConfigs?.get(mediaModelId)
    val mediaLabel = stringResource(if (mediaKind == ModelType.VIDEO_GENERATION) R.string.common_video else R.string.common_image)
    val exitMediaModeLabel = stringResource(R.string.chat_exit_generation_mode, mediaLabel)
    val selectMediaModelLabel = stringResource(R.string.chat_select_generation_model, mediaLabel)
    val generateMediaLabel = stringResource(R.string.chat_generate_media, mediaLabel)
    val selectModelLabel = stringResource(R.string.select_model)
    val sendMessageLabel = stringResource(R.string.chat_send_message)
    LaunchedEffect(mediaKind, mediaProviders) {
        if (mediaKind != null && mediaModel == null) {
            mediaProviderId = mediaProviders.firstOrNull()?.id.orEmpty()
            mediaModelId = mediaProviders.firstOrNull()?.config?.modelIds?.firstOrNull().orEmpty()
        }
    }
    // Regenerate/resend follow the composer: media mode uses the selected media model, chat mode sends as chat.
    val foreground = LocalChatForeground.current
    LaunchedEffect(mediaKind, mediaProviderId, mediaModelId, mediaModel, foreground) {
        if (foreground) onMediaModeChange(mediaKind.takeIf { mediaModel != null }, mediaProviderId, mediaModelId)
    }
    val currentForeground by rememberUpdatedState(foreground)
    DisposableEffect(Unit) {
        onDispose { if (currentForeground) onMediaModeChange(null, "", "") }
    }
    val focusManager = LocalFocusManager.current
    val latestOnModelSheetOpen by rememberUpdatedState(onModelSheetOpen)
    LaunchedEffect(bottomSheets.modelSheetPage) {
        if (bottomSheets.modelSheetPage != null) latestOnModelSheetOpen()
    }
    var skillsMenuOpen by remember(foreground) { mutableStateOf(false) }
    var inputBoxWidthPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(textState) {
        if (selectedSkill != null && !textState.hasSelectedSkillInvocation(selectedSkill.id)) {
            selectedSkillId = null
        }
        skillsMenuOpen = textState.startsWith("/") &&
            (selectedSkill == null || !textState.hasSelectedSkillInvocation(selectedSkill.id))
    }
    val dynamicModelsMap = remember { mutableStateMapOf<String, List<String>>() }
    val dynamicModelEndpoints = remember { mutableMapOf<String, String>() }
    val ollamaCache = remember(context) { org.starfall.generative.integrations.modelapis.ollama.OllamaModelCache(context) }
    val dynamicProviders = providers.filter { it.type == ModelApiType.OLLAMA && it.config.modelIds == null }
        .map { it.id to it.baseUrl }.sortedBy { it.first }
    val fetchOllamaModels by rememberUpdatedState(onFetchOllamaModels)
    LaunchedEffect(dynamicProviders, foreground) {
        if (!foreground) return@LaunchedEffect
        dynamicModelsMap.keys.retainAll(dynamicProviders.map { it.first }.toSet())
        dynamicModelEndpoints.keys.retainAll(dynamicProviders.map { it.first }.toSet())
        // Publish disk snapshots before any network discovery; one slow/offline host
        // must not block the other providers or discard models on a screen remount.
        dynamicProviders.forEach { (id, url) ->
            if (dynamicModelEndpoints[id] == url && dynamicModelsMap.containsKey(id)) return@forEach
            dynamicModelEndpoints[id] = url
            dynamicModelsMap.remove(id)
            ollamaCache.read(url)?.let { dynamicModelsMap[id] = it.models }
        }
        kotlinx.coroutines.coroutineScope {
            dynamicProviders.forEach { (id, url) ->
                launch {
                    fetchOllamaModels?.let { fetch -> dynamicModelsMap[id] = fetch(url) }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.yield()
        focusManager.clearFocus(force = true)
    }

    LaunchedEffect(editDraft?.revision) {
        editDraft?.let {
            mediaKind = null
            selectedSkillId = null
            textFieldState.setTextAndPlaceCursorAtEnd(it.text)
            onAttachmentsChange(it.attachments)
        }
    }

    val hasContent = textState.isNotBlank() || attachments.isNotEmpty()
    LaunchedEffect(mediaKind) {
        if (mediaKind != null) selectedSkillId = null
    }
    var attachmentImporting by remember(clipboardSessionKey) { mutableStateOf(false) }
    val canSend = !attachmentImporting && canSendChatInput(textState, attachments, mediaKind, isGenerating, mediaModel != null)
    val showStop = isGenerating && editDraft == null && (mediaKind != null || !hasContent)
    val mediaHint = directMediaHint(textState, attachments, mediaKind, mediaModel, mediaModelId, mediaLabel)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .onSizeChanged { inputBoxWidthPx = it.width }
                .clip(RoundedCornerShape(36.dp))
        ) {
        InputAreaBackdropLayer(backdrop, Modifier.matchParentSize())
        Surface(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .testTag("chat-input"),
            shape = RoundedCornerShape(36.dp),
            color = LocalInputAreaAppearance.current.surfaceColor(if (amoledMode) MaterialTheme.colorScheme.surface else inputColor),
            border = when {
                mediaKind != null -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                amoledMode -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                else -> null
            },
            tonalElevation = if (amoledMode) 0.dp else 2.dp,
            // No shadowElevation: the surface is translucent, so its shadow would show through inside.
            shadowElevation = 0.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = maxInputHeight)) {
                if (mediaKind != null) {
                    // Mode chip lives inside the input; tapping it leaves the generation mode.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                                .clickable { mediaKind = null }
                                .semantics { contentDescription = exitMediaModeLabel }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SharedIcon(
                                if (mediaKind == ModelType.IMAGE_GENERATION) Icons.Outlined.Image else Icons.Outlined.Videocam,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                mediaLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1
                            )
                        }
                        // Only surface the hint when something blocks sending.
                        val mediaInvalid = mediaModel == null || textState.length > 32000 ||
                            attachments.size > (if (mediaKind == ModelType.VIDEO_GENERATION) 1 else 16)
                        if (mediaInvalid) {
                            Text(
                                mediaHint,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                if (editDraft != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 10.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.chat_editing_message),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                onCancelEdit()
                                textFieldState.clearText()
                                selectedSkillId = null
                                onAttachmentsChange(emptyList())
                            }
                        ) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
                if (attachments.isNotEmpty()) {
                    AttachmentStrip(
                        references = attachments,
                        removable = true,
                        onRemove = { ref -> onAttachmentsChange(attachments.filterNot { it == ref }) },
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                        compact = false
                    )
                }
                // Both action groups are declared once so they can sit beside the text or under it.
                val addButton: @Composable () -> Unit = {
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            bottomSheets.openAddMenu()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        SharedIcon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.chat_attachments_and_tools),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                    val actionButtons: @Composable () -> Unit = {
                    // The context window ring only describes the chat model, so media mode keeps a plain button.
                    val contextRingStatus = contextWindowStatus.takeIf { mediaKind == null }
                    val contextUsedLabel = contextRingStatus?.let { stringResource(R.string.context_window_percent_used, it.percentage) }
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .semantics {
                                contentDescription = if (mediaKind != null) selectMediaModelLabel else selectModelLabel
                                contextUsedLabel?.let { stateDescription = it }
                            }
                            .clickable {
                                if (mediaKind != null) bottomSheets.openModelPicker()
                                else bottomSheets.openSelectedModelOverview()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (contextRingStatus != null) {
                            ContextWindowRing(contextRingStatus, Modifier.fillMaxSize().padding(1.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(if (contextRingStatus != null) 40.dp else 44.dp)
                                .clip(CircleShape)
                                .background(if (mediaKind != null) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            if (mediaKind != null) {
                                EntityIcon(
                                    mediaModel?.icon, Modifier.fillMaxSize(),
                                    text = modelInitial(mediaModelId),
                                    fallback = if (mediaModel == null) {
                                        if (mediaKind == ModelType.IMAGE_GENERATION) Icons.Outlined.Image else Icons.Outlined.Videocam
                                    } else null,
                                    matchName = mediaModel?.displayName?.ifBlank { mediaModelId }, fallbackMatchName = mediaModelId, model = true,
                                    shape = CircleShape
                                )
                            } else {
                                val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
                                val selectedConfig = selectedProvider?.config?.modelConfigs?.get(selectedModelName)
                                    ?: selectedProvider?.config?.modelConfigs?.values?.firstOrNull {
                                        it.displayName.isNotBlank() && it.displayName == selectedModelName
                                    }
                                EntityIcon(selectedConfig?.icon, Modifier.fillMaxSize(),
                                    text = modelInitial(selectedModelName),
                                    matchName = selectedConfig?.displayName?.ifBlank { selectedModelName } ?: selectedModelName, fallbackMatchName = selectedModelName, model = true,
                                    shape = CircleShape)
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showStop || hasContent,
                        enter = expandHorizontally(tween(180), expandFrom = Alignment.End) + fadeIn(tween(140)),
                        exit = shrinkHorizontally(tween(180), shrinkTowards = Alignment.End) + fadeOut(tween(100))
                    ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    showStop -> MaterialTheme.colorScheme.surfaceContainerHighest
                                    canSend -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                }
                            )
                            .clickable(
                                enabled = showStop || canSend,
                                onClick = {
                                    if (showStop) {
                                        onStopGenerating()
                                    } else if (canSend) {
                                        val submitted = editDraft?.let { draft ->
                                            onEditMessage(draft.messageId, textState, attachments)
                                        } ?: mediaKind?.let { kind ->
                                            onSendMedia(DirectMediaRequest(textState, kind, mediaProviderId, mediaModelId, attachments))
                                        } ?: onSendMessageWithSkill(
                                            textState, attachments,
                                            selectedSkill?.id == DEFAULT_AGENT_SKILL_ID,
                                            selectedSkill?.name?.takeUnless { it == DEFAULT_AGENT_SKILL_ID }
                                        )
                                        if (submitted) {
                                            focusManager.clearFocus(force = true)
                                            textFieldState.clearText()
                                            selectedSkillId = null
                                            onAttachmentsChange(emptyList())
                                            if (editDraft != null) onCancelEdit()
                                        } else {
                                            onSubmissionFailed(localizedResources.getString(R.string.chat_submit_failed))
                                        }
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showStop) {
                            SharedIcon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = stringResource(R.string.chat_stop_generation),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        } else if (attachmentImporting) {
                            org.starfall.generative.shared.ui.LoadingIndicator(Modifier.size(20.dp))
                        } else {
                            SharedIcon(
                                imageVector = when (mediaKind) {
                                    ModelType.IMAGE_GENERATION -> Icons.Outlined.Image
                                    ModelType.VIDEO_GENERATION -> Icons.Outlined.Videocam
                                    else -> Icons.Default.ArrowUpward
                                },
                                contentDescription = if (mediaKind != null) generateMediaLabel else sendMessageLabel,
                                tint = if (canSend) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    }
                    }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = if (buttonsBelow) 0.dp else 6.dp)
                        .onSizeChanged { inputRowWidthPx = it.width },
                    verticalAlignment = if (stackInputActions) Alignment.Bottom else Alignment.CenterVertically
                ) {
                    // The text field keeps its position in this Row, so it keeps focus when the buttons move.
                    if (!buttonsBelow) addButton()

                    BasicTextField(
                        state = textFieldState,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        textStyle = inputTextStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = if (buttonsBelow) 14.dp else 8.dp, vertical = 10.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.any { it.pressed }) inputFocusAllowed = true
                                    }
                                }
                            }
                            .focusProperties { canFocus = inputFocusAllowed }
                            .onFocusChanged {
                                if (!it.isFocused) inputFocusAllowed = false
                            }
                            .selectAllOnTripleClick {
                                textFieldState.edit { selection = TextRange(0, length) }
                            }
                            .contentReceiver(clipboardContentListener),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = Int.MAX_VALUE),
                        decorator = { innerTextField ->
                            Column {
                                Box(contentAlignment = Alignment.CenterStart) {
                                if (textState.isEmpty()) {
                                    Text(
                                        text = when (mediaKind) {
                                            ModelType.IMAGE_GENERATION -> stringResource(R.string.chat_describe_image)
                                            ModelType.VIDEO_GENERATION -> stringResource(R.string.chat_describe_video)
                                            else -> stringResource(R.string.chat_input_placeholder)
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    )
                                }
                                innerTextField()
                                }
                            }
                        }
                    )

                    if (!buttonsBelow) Row(verticalAlignment = Alignment.CenterVertically) { actionButtons() }
                }
                if (buttonsBelow) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        addButton()
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) { actionButtons() }
                    }
                }
            }
        }
        if (foreground && skillsMenuOpen) {
            Popup(
                popupPositionProvider = AgentSkillsMenuPositionProvider(
                    verticalGapPx = with(density) { 4.dp.roundToPx() }
                ),
                onDismissRequest = { skillsMenuOpen = false },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                    clippingEnabled = false
                )
            ) {
                AgentSkillsMenu(
                    skills = agentSkills,
                    query = agentSkillMenuQuery(textState),
                    modifier = if (inputBoxWidthPx > 0) {
                        Modifier.width(with(density) { inputBoxWidthPx.toDp() })
                    } else {
                        Modifier.widthIn(max = 720.dp).fillMaxWidth()
                    },
                    onSelect = { skill ->
                        val nextText = textState.withSelectedSkillInvocation(skill.id)
                        textFieldState.setTextAndPlaceCursorAtEnd(nextText)
                        selectedSkillId = skill.id
                        skillsMenuOpen = false
                    },
                    onDismiss = { skillsMenuOpen = false },
                    onOpenSkillsSettings = onOpenSkillsSettings?.let { openSettings ->
                        {
                            skillsMenuOpen = false
                            openSettings()
                        }
                    }
                )
            }
        }
        }
    }


    // Shown above the model card in the model sheet, where the exact usage and Summarize live.
    val modelSheetContextWindow: (@Composable (Boolean) -> Unit)? = contextWindowStatus?.let { status ->
        val content: @Composable (Boolean) -> Unit = { onDarkBackground -> ContextWindowBar(status, onDarkBackground) }
        content
    }
    if (foreground) BottomSheetsLink(
        sheets = bottomSheets,
        temporaryChatId = temporaryChatId,
        attachmentSessionKey = clipboardSessionKey,
        onAttachmentImportingChange = { attachmentImporting = it },
        mediaKind = mediaKind,
        onMediaKindChange = { mediaKind = it },
        onCancelEdit = onCancelEdit,
        onShowSkillsMenu = { skillsMenuOpen = true },
        providers = providers,
        providerGroups = providerGroups,
        selectedProviderId = selectedProviderId,
        selectedModelName = selectedModelName,
        conversationReasoningEffort = conversationReasoningEffort,
        onSetReasoningEffort = onSetReasoningEffort,
        onSelectModel = onSelectModel,
        onSetThinkingPassback = onSetThinkingPassback,
        onSetModelReasoningEffort = onSetModelReasoningEffort,
        onSetModelTools = onSetModelTools,
        onEditModel = onEditModel,
        onEditProvider = onEditProvider,
        modelPickerCollapsedGroups = modelPickerCollapsedGroups,
        modelPickerCollapsedProviders = modelPickerCollapsedProviders,
        onModelPickerCollapsedGroupsChange = onModelPickerCollapsedGroupsChange,
        onModelPickerCollapsedProvidersChange = onModelPickerCollapsedProvidersChange,
        dynamicModelsMap = if (mediaKind != null) emptyMap() else dynamicModelsMap.toMap(),
        mediaProviders = mediaProviders,
        mediaProviderId = mediaProviderId,
        mediaModelId = mediaModelId,
        onMediaModelChange = { provider, model -> mediaProviderId = provider; mediaModelId = model },
        attachments = attachments,
        onAttachmentsChange = onAttachmentsChange,
        onOpenFeatureToolsSettings = onOpenFeatureToolsSettings,
        onOpenMcpConnectorsSettings = onOpenMcpConnectorsSettings,
        contextWindowContent = modelSheetContextWindow,
        onStartSummary = onStartSummary.takeIf { contextWindowStatus != null }
    )
}
