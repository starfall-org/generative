package org.starfall.generative.features.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import org.starfall.generative.R
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.ui.model.LocalThinkingScreenBurst
import org.starfall.generative.shared.ui.model.ThinkingScreenBurstLayer
import org.starfall.generative.shared.ui.model.rememberThinkingColorBurst
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.defaultNativeModelTools
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import org.starfall.generative.features.home.shared.ui.FeatureToolsSheetContent
import org.starfall.generative.features.home.shared.ui.ConversationSummarySheetContent
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.features.home.shared.ui.InstructionsSheetContent
import org.starfall.generative.features.home.shared.ui.McpConnectorsSheetContent
import org.starfall.generative.features.home.shared.ui.QuickActionsSheetContent
import org.starfall.generative.features.home.shared.ui.SelectedModelOverviewContent
import org.starfall.generative.features.home.shared.ui.SelectedModelOptionsSheetContent
import org.starfall.generative.features.home.shared.ui.rememberPreviewTier
import org.starfall.generative.features.home.shared.ui.selectedModelSheetStyle
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.model.ModelPickerSheetContent
import org.starfall.generative.shared.ui.model.chatModelPickerFilter
import org.starfall.generative.shared.ui.sheets.BottomSheet
import org.starfall.generative.shared.ui.sheets.SlideSheetContent

internal enum class QuickActionsPage {
    ROOT,
    INSTRUCTIONS,
    FEATURE_TOOLS,
    MCP_CONNECTORS,
}

internal enum class ModelSheetPage {
    SELECTED_MODEL,
    MODEL_OPTIONS,
    MODEL_PICKER,
    MODEL_PICKER_FROM_OVERVIEW,
    SUMMARY,
}

internal class BottomSheetsLinkState {
    /** Model overview, options, model picker, and summarization share one sheet. */
    var modelSheetPage by mutableStateOf<ModelSheetPage?>(null)
        private set
    val renderedModelSheetPage: ModelSheetPage?
        get() = if (modelSheetPage == ModelSheetPage.MODEL_PICKER_FROM_OVERVIEW)
            ModelSheetPage.MODEL_PICKER else modelSheetPage
    val showSelectedModelOverview: Boolean get() = modelSheetPage == ModelSheetPage.SELECTED_MODEL
    var quickActionsPage by mutableStateOf<QuickActionsPage?>(null)
        private set
    val showAddMenu: Boolean get() = quickActionsPage != null
    var showFilesSheet by mutableStateOf(false)
        private set

    fun openAddMenu() { quickActionsPage = QuickActionsPage.ROOT }
    fun openModelPicker() { modelSheetPage = ModelSheetPage.MODEL_PICKER }
    fun openSelectedModelOverview() { modelSheetPage = ModelSheetPage.SELECTED_MODEL }
    fun openModelOptions() { modelSheetPage = ModelSheetPage.MODEL_OPTIONS }
    /** Moves the open sheet from the overview to the picker in place. */
    fun switchToModelPicker() { modelSheetPage = ModelSheetPage.MODEL_PICKER_FROM_OVERVIEW }
    fun openSummary() { modelSheetPage = ModelSheetPage.SUMMARY }
    fun backFromModelPicker() {
        if (modelSheetPage == ModelSheetPage.MODEL_PICKER_FROM_OVERVIEW)
            openSelectedModelOverview() else dismissModelSheet()
    }
    fun openInstructions() {
        quickActionsPage = QuickActionsPage.INSTRUCTIONS
    }
    fun openFeatureTools() {
        quickActionsPage = QuickActionsPage.FEATURE_TOOLS
    }
    fun openMcpConnectors() {
        quickActionsPage = QuickActionsPage.MCP_CONNECTORS
    }
    fun backToQuickActions() { quickActionsPage = QuickActionsPage.ROOT }
    fun openFilesUnavailableDialog() { showFilesSheet = true }
    fun dismissAddMenu() { quickActionsPage = null }
    fun dismissModelSheet() { modelSheetPage = null }
    fun dismissFilesUnavailableDialog() { showFilesSheet = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BottomSheetsLink(
    sheets: BottomSheetsLinkState,
    mediaKind: ModelType?,
    onMediaKindChange: (ModelType?) -> Unit,
    onCancelEdit: () -> Unit,
    onShowSkillsMenu: () -> Unit,
    providers: List<ModelProvider>,
    providerGroups: List<ProviderGroup>,
    selectedProviderId: String,
    selectedModelName: String,
    conversationReasoningEffort: String?,
    onSetReasoningEffort: (String?) -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSetThinkingPassback: (providerId: String, modelId: String, mode: ThinkingPassback) -> Unit,
    onSetModelReasoningEffort: (providerId: String, modelId: String, effort: String?) -> Unit,
    onSetModelTools: (providerId: String, modelId: String, tools: List<String>) -> Unit,
    onEditModel: (providerId: String, modelId: String) -> Unit,
    onEditProvider: (providerId: String) -> Unit,
    modelPickerCollapsedGroups: Set<String>,
    modelPickerCollapsedProviders: Set<String>,
    onModelPickerCollapsedGroupsChange: (Set<String>) -> Unit,
    onModelPickerCollapsedProvidersChange: (Set<String>) -> Unit,
    dynamicModelsMap: Map<String, List<String>>,
    mediaProviders: List<ModelProvider>,
    mediaProviderId: String,
    mediaModelId: String,
    onMediaModelChange: (providerId: String, modelId: String) -> Unit,
    attachments: List<String>,
    onAttachmentsChange: (List<String>) -> Unit,
    onOpenFeatureToolsSettings: () -> Unit = {},
    onOpenMcpConnectorsSettings: () -> Unit = {},
    /** Context window indicator shown above the model card; null hides it. */
    contextWindowContent: (@Composable (onDarkBackground: Boolean) -> Unit)? = null,
    /** Starts summarization; null hides the Summarize button. */
    onStartSummary: ((ConversationSummaryRequest) -> Boolean)? = null,
    temporaryChatId: String? = null
) {
    val context = LocalContext.current
    fun retainReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        uris.forEach(::retainReadPermission)
        onAttachmentsChange((attachments + uris.map(Uri::toString)).distinct())
    }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val picked = buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        }.distinct()
        picked.forEach(::retainReadPermission)
        onAttachmentsChange((attachments + picked.map(Uri::toString)).distinct())
    }
    // Keep the destination across activity recreation while the external camera is open.
    var pendingPhotoPath by rememberSaveable(temporaryChatId) { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val photo = pendingPhotoPath?.let(::File)
        pendingPhotoPath = null
        if (captured && photo != null && photo.length() > 0L) {
            onAttachmentsChange((attachments + Uri.fromFile(photo).toString()).distinct())
        } else {
            photo?.delete()
            if (captured) sheets.openFilesUnavailableDialog()
        }
    }
    val selectedModelScreenBurst = rememberThinkingColorBurst()
    LaunchedEffect(sheets.showSelectedModelOverview) {
        if (!sheets.showSelectedModelOverview) selectedModelScreenBurst.hide()
    }
    if (sheets.showAddMenu) {
        BottomSheet(
            onDismissRequest = { sheets.dismissAddMenu() },
            dismissOnBack = sheets.quickActionsPage == QuickActionsPage.ROOT,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            SlideSheetContent(
                page = sheets.quickActionsPage.takeUnless { it == QuickActionsPage.ROOT },
                onBack = { sheets.backToQuickActions() },
                label = "Quick actions sheet"
            ) { page ->
                val pageActive = LocalScreenTransitionActive.current
                when (page) {
                    null, QuickActionsPage.ROOT -> QuickActionsSheetContent(
                        onPickImage = {
                            if (!pageActive) return@QuickActionsSheetContent
                            photoPicker.launch(PickVisualMediaRequest(
                                if (mediaKind == null || mediaKind == ModelType.VIDEO_GENERATION) ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                else ActivityResultContracts.PickVisualMedia.ImageOnly
                            ))
                        },
                        onPickDocument = {
                            if (!pageActive) return@QuickActionsSheetContent
                            documentPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = if (mediaKind == null || mediaKind == ModelType.VIDEO_GENERATION) "*/*" else "image/*"
                                if (mediaKind == ModelType.VIDEO_GENERATION) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            })
                        },
                        onTakePhoto = {
                            if (pageActive && pendingPhotoPath == null) {
                                try {
                                    val directory = temporaryChatId?.let { id ->
                                        // The temporary session owns this directory and removes it on close.
                                        File(context.cacheDir, "temporary_chat_media/$id").also {
                                            check(it.isDirectory) { "Temporary chat was discarded" }
                                        }
                                    } ?: ToolFiles.appStorageDirectory(context)
                                    val photo = File.createTempFile("capture_", ".jpg", directory)
                                    pendingPhotoPath = photo.absolutePath
                                    cameraLauncher.launch(FileProvider.getUriForFile(
                                        context, "${context.packageName}.tool-files", photo
                                    ))
                                } catch (error: Exception) {
                                    android.util.Log.w("QuickActions", "Unable to launch camera capture", error)
                                    pendingPhotoPath?.let { File(it).delete() }
                                    pendingPhotoPath = null
                                    sheets.openFilesUnavailableDialog()
                                }
                            }
                        },
                        onCreateImage = {
                            if (pageActive) {
                                onCancelEdit()
                                onMediaKindChange(ModelType.IMAGE_GENERATION)
                            }
                        },
                        onCreateVideo = {
                            if (pageActive) {
                                onCancelEdit()
                                onMediaKindChange(ModelType.VIDEO_GENERATION)
                            }
                        },
                        onOpenInstructions = {
                            if (pageActive) sheets.openInstructions()
                        },
                        onOpenSkills = {
                            if (pageActive) {
                                sheets.dismissAddMenu()
                                onShowSkillsMenu()
                            }
                        },
                        onOpenFeatureTools = {
                            if (pageActive) sheets.openFeatureTools()
                        },
                        onOpenMcpConnectors = {
                            if (pageActive) sheets.openMcpConnectors()
                        },
                        onDismiss = {
                            if (pageActive) sheets.dismissAddMenu()
                        }
                    )
                    QuickActionsPage.INSTRUCTIONS -> InstructionsSheetContent(
                        onBack = { if (pageActive) sheets.backToQuickActions() },
                        onLeave = { if (pageActive) sheets.dismissAddMenu() }
                    )
                    QuickActionsPage.FEATURE_TOOLS -> FeatureToolsSheetContent(
                        onBack = { if (pageActive) sheets.backToQuickActions() },
                        onOpenSettings = {
                            if (pageActive) {
                                sheets.dismissAddMenu()
                                onOpenFeatureToolsSettings()
                            }
                        }
                    )
                    QuickActionsPage.MCP_CONNECTORS -> McpConnectorsSheetContent(
                        onBack = { if (pageActive) sheets.backToQuickActions() },
                        onOpenSettings = {
                            if (pageActive) {
                                sheets.dismissAddMenu()
                                onOpenMcpConnectorsSettings()
                            }
                        }
                    )
                }
            }
        }
    }

    val modelSheetPage = sheets.renderedModelSheetPage
    if (modelSheetPage != null && (modelSheetPage == ModelSheetPage.MODEL_PICKER || mediaKind == null)) {
        val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
        val selectedConfigEntry = selectedProvider?.config?.modelConfigs?.entries?.firstOrNull { it.key == selectedModelName }
            ?: selectedProvider?.config?.modelConfigs?.entries?.firstOrNull {
                it.value.displayName.isNotBlank() && it.value.displayName == selectedModelName
            }
        val rawSelectedConfig = selectedConfigEntry?.value
            // Keep the same fallback as the model picker: discovered/custom models without
            // an explicit saved configuration still expose the reasoning control.
            ?: ModelConfiguration(displayName = selectedModelName.ifBlank { stringResource(R.string.select_model) })
        // Some provider catalogs omit native-tool metadata even for models that support the
        // provider's built-in tools. Keep explicit metadata authoritative, and use known native
        // presets only when the model has no tool list at all.
        val selectedConfig = if (rawSelectedConfig.supportedModelTools.isEmpty() && selectedProvider != null) {
            rawSelectedConfig.copy(modelTools = defaultNativeModelTools(selectedProvider.type, selectedModelName))
        } else rawSelectedConfig
        val selectedFolderName = selectedProvider?.groupId?.let { groupId ->
            providerGroups.firstOrNull { it.id == groupId }?.name
        }
        var pickerContentOverflows by remember(modelSheetPage) { mutableStateOf(false) }
        // A conversation override wins; otherwise show the level saved for this model.
        val effectiveReasoningEffort = conversationReasoningEffort ?: selectedConfig.reasoningEffort
        val previewTier = rememberPreviewTier(selectedConfig, effectiveReasoningEffort)
        val style = selectedModelSheetStyle(
            modelSheetPage == ModelSheetPage.SELECTED_MODEL && previewTier.intValue == 6 && selectedConfig.supportsThinking,
            modelSheetPage == ModelSheetPage.SELECTED_MODEL && selectedModelScreenBurst.isRetracting
        )
        CompositionLocalProvider(LocalThinkingScreenBurst provides selectedModelScreenBurst) {
            ThinkingScreenBurstLayer(selectedModelScreenBurst)
            // One sheet for all pages: switching slides the content and animates the height in place.
            BottomSheet(
                onDismissRequest = {
                    selectedModelScreenBurst.hide()
                    sheets.dismissModelSheet()
                },
                dismissOnBack = modelSheetPage == ModelSheetPage.SELECTED_MODEL,
                expandOnOpen = modelSheetPage == ModelSheetPage.MODEL_PICKER && pickerContentOverflows,
                containerColor = style.color,
                containerBrush = style.brush,
                navigationBarColor = style.navigationBarColor,
                // Keep the outer inset stable while pages slide and animate their height.
                contentBehindNavigationBar = false,
                // Read in the draw phase: the reveal animates every frame and must not recompose the sheet.
                containerBrushRevealOrigin = { selectedModelScreenBurst.origin.takeIf { it != Offset.Zero } },
                containerBrushRevealProgress = { selectedModelScreenBurst.progress.value },
                containerBrushRetractionProgress = {
                    selectedModelScreenBurst.retractionProgress.value.takeIf { selectedModelScreenBurst.isRetracting }
                },
                containerOverlay = style.overlay,
            ) {
                // The sheet is its own window, so the screen-owned star overlay must also be attached to it.
                ThinkingScreenBurstLayer(selectedModelScreenBurst)
                SlideSheetContent(
                    page = modelSheetPage.takeUnless { it == ModelSheetPage.SELECTED_MODEL },
                    onBack = {
                        if (sheets.modelSheetPage == ModelSheetPage.SUMMARY ||
                            sheets.modelSheetPage == ModelSheetPage.MODEL_OPTIONS) sheets.openSelectedModelOverview()
                        else sheets.backFromModelPicker()
                    },
                    label = "Model sheet"
                ) { target ->
                    val pageActive = LocalScreenTransitionActive.current
                    if (target == ModelSheetPage.MODEL_PICKER) {
                        ModelPickerSheetContent(
                            onOverflowChanged = { pickerContentOverflows = it },
                            providers = if (mediaKind != null) mediaProviders else providers,
                            providerGroups = providerGroups,
                            collapsedGroupIdsState = modelPickerCollapsedGroups,
                            collapsedProviderIdsState = modelPickerCollapsedProviders,
                            onCollapsedGroupIdsChange = onModelPickerCollapsedGroupsChange,
                            onCollapsedProviderIdsChange = onModelPickerCollapsedProvidersChange,
                            selectedProviderId = if (mediaKind != null) mediaProviderId else selectedProviderId,
                            selectedModelId = if (mediaKind != null) mediaModelId else selectedModelName,
                            conversationReasoningEffort = null,
                            onSelectModel = { provider, model ->
                                if (mediaKind != null) { onMediaModelChange(provider, model) }
                                else onSelectModel(provider, model)
                            },
                            onSetReasoningEffort = {},
                            showReasoningEffort = false,
                            includeNavigationBarPadding = false,
                            dynamicModelsMap = if (mediaKind != null) emptyMap() else dynamicModelsMap.toMap(),
                            modelFilter = if (mediaKind == null) chatModelPickerFilter else { _, _, config -> config.modelType == mediaKind },
                            onEditProvider = onEditProvider,
                            onEditModel = { providerId, modelId ->
                                if (pageActive) onEditModel(providerId, modelId)
                            },
                            onOpenSelectedModel = if (mediaKind == null) {
                                { if (pageActive) sheets.openSelectedModelOverview() }
                            } else null,
                            // Allow the picker to wrap short results; its scrollable list handles long results.
                            onDismiss = { sheets.dismissModelSheet() }
                        )
                    } else if (target == ModelSheetPage.MODEL_OPTIONS) {
                        val providerId = selectedProvider?.id
                        val configKey = selectedConfigEntry?.key ?: selectedModelName
                        SelectedModelOptionsSheetContent(
                            modelId = selectedModelName.ifBlank { selectedConfig.displayName },
                            config = selectedConfig,
                            providerName = selectedProvider?.name,
                            onBack = { if (pageActive) sheets.openSelectedModelOverview() },
                            onSetThinkingPassback = if (providerId != null && configKey.isNotBlank()) {
                                { mode -> onSetThinkingPassback(providerId, configKey, mode) }
                            } else null,
                            onSetModelTools = if (providerId != null && configKey.isNotBlank() &&
                                selectedConfig.supportedModelTools.isNotEmpty()) {
                                { tools -> onSetModelTools(providerId, configKey, tools) }
                            } else null
                        )
                    } else if (target == ModelSheetPage.SUMMARY) {
                        if (onStartSummary != null) {
                            ConversationSummarySheetContent(
                                onStart = { request -> pageActive && onStartSummary(request) },
                                onDismiss = { if (pageActive) sheets.dismissModelSheet() },
                                onBack = { if (pageActive) sheets.openSelectedModelOverview() }
                            )
                        }
                    } else {
                        SelectedModelOverviewContent(
                            provider = selectedProvider,
                            folderName = selectedFolderName,
                            modelId = selectedModelName.ifBlank { selectedConfig.displayName },
                            config = selectedConfig,
                            conversationReasoningEffort = effectiveReasoningEffort,
                            previewTier = previewTier.intValue,
                            onPreviewTierChange = { previewTier.intValue = it },
                            onSetReasoningEffort = { effort ->
                                onSetReasoningEffort(effort)
                                if (selectedProvider != null && selectedConfigEntry != null) {
                                    onSetModelReasoningEffort(selectedProvider.id, selectedConfigEntry.key, effort)
                                }
                            },
                            onOpenModelPicker = { if (pageActive) sheets.switchToModelPicker() },
                            onOpenModelOptions = { if (pageActive) sheets.openModelOptions() },
                            contextWindowContent = contextWindowContent,
                            onSummarize = onStartSummary?.let {
                                { if (pageActive) sheets.openSummary() }
                            }
                        )
                    }
                }
            }
        }
    }

    if (sheets.showFilesSheet) {
        AlertDialog(
            onDismissRequest = { sheets.dismissFilesUnavailableDialog() },
            title = { Text(stringResource(R.string.camera_unavailable_title)) },
            text = { Text(stringResource(R.string.camera_unavailable_message)) },
            confirmButton = {
                TextButton(onClick = { sheets.dismissFilesUnavailableDialog() }) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }
}
