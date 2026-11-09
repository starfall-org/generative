package org.starfall.generative.navigation

import org.starfall.generative.shared.model.CustomHttpTool
import org.starfall.generative.features.httptools.ui.CustomHttpToolsScreen
import org.starfall.generative.features.httptools.ui.HttpToolEditor
import org.starfall.generative.features.httptools.logic.CustomHttpToolsViewModel
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.starfall.generative.R
import org.starfall.generative.features.about.ui.AboutScreen
import org.starfall.generative.features.appdata.logic.AppDataTransferService
import org.starfall.generative.shared.model.BackupExportKind
import org.starfall.generative.features.appdata.ui.AppDataScreen
import org.starfall.generative.features.chatoptions.ui.ChatOptionsScreen
import org.starfall.generative.features.icons.ui.IconsScreen
import org.starfall.generative.features.machines.details.ui.MachineDetailsScreen
import org.starfall.generative.features.machines.details.ui.RemoteMachineSettings
import org.starfall.generative.features.machines.list.ui.RemoteMachinesScreen
import org.starfall.generative.features.machines.list.ui.PortForwardDialog
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.features.machines.shared.ui.terminal.RemoteTerminalScreen
import org.starfall.generative.features.menu.ui.AppMenuScreen
import org.starfall.generative.features.customization.ui.CustomizationScreen
import org.starfall.generative.features.mcp.editor.ui.ServerEditor
import org.starfall.generative.features.mcp.list.ui.ServerListScreen
import org.starfall.generative.features.providers.editor.ui.ProviderEditor
import org.starfall.generative.features.providers.list.ui.ProvidersScreen
import org.starfall.generative.features.providers.list.ui.ProvidersDemoScreen
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.features.settings.logic.SettingsViewModel
import org.starfall.generative.features.settings.ui.AppSettings
import org.starfall.generative.features.settings.ui.DeveloperOptionsScreen
import org.starfall.generative.features.settings.ui.LauncherDragTestScreen
import org.starfall.generative.features.speech.ui.SpeechServicesScreen
import org.starfall.generative.features.storage.ui.MediaStorageSheet
import org.starfall.generative.features.storage.ui.StorageScreen
import org.starfall.generative.features.tools.ui.ToolsEditor
import org.starfall.generative.features.tools.ui.MediaRequestChainSettings
import org.starfall.generative.features.tools.ui.MediaRequestProfileEditor
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.home.chat.logic.contextWindowStatus
import org.starfall.generative.features.home.chat.ui.ChatScreen
import org.starfall.generative.features.home.chat.ui.RetainedChatConversations
import org.starfall.generative.features.home.chat.ui.RetainedChatPages
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.features.home.drawer.ui.ConversationSideDrawer
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import kotlinx.coroutines.CancellationException
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.media.modelIndependent
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.media.MediaKind
import org.starfall.generative.shared.model.media.defaultMediaProfile
import org.starfall.generative.shared.model.media.withDefaultMediaProfile
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.shared.ui.theme.modalScrimColor
import org.starfall.generative.features.home.shared.ui.InstructionControls
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.features.home.shared.ui.LocalInstructionControls
import org.starfall.generative.features.home.shared.ui.LocalToolControls
import org.starfall.generative.features.home.shared.ui.ToolControls
import org.starfall.generative.shared.ui.screens.RetainedScreenContent
import org.starfall.generative.features.home.chat.ui.LocalChatAttachmentSelection
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate
import org.starfall.generative.shared.ui.screens.PreviewScreenTemplate

/** Stable route names are persisted by Navigation across Activity/process recreation. */
private data class MachineEditorPage(val machine: RemoteMachine, val isNew: Boolean)
private data class ProviderEditorPage(val provider: ModelProvider, val isNew: Boolean)
private enum class RemoteMachineSettingsPage { SETTINGS }

/** Distinct NavHost destinations, not conditional pages inside Feature Tools. */
internal object MediaProfileRoutes {
    const val LIBRARY = "system_tools/profiles/{kind}"
    const val EDITOR = "system_tools/profiles/{kind}/edit/{profileId}"
    fun library(kind: MediaKind) = "system_tools/profiles/${kind.name.lowercase()}"
    fun editor(kind: MediaKind, profileId: String) = "${library(kind)}/edit/${Uri.encode(profileId)}"
    fun library(video: Boolean) = "system_tools/profiles/${if (video) "video" else "image"}"
    fun editor(video: Boolean, profileId: String) =
        "${library(video)}/edit/${Uri.encode(profileId)}"
}

internal val NullableFilePathSaver = Saver<File?, String>(
    save = { it?.absolutePath },
    restore = { path -> path.takeIf { it.isNotBlank() }?.let(::File) }
)

enum class AppDestination(val route: String) {
    CHAT("chat"),
    PROVIDERS("providers"),
    MCP("mcp"),
    CUSTOM_HTTP_TOOLS("custom_http_tools"),
    SPEECH("speech"),
    FEATURES("features"),
    CUSTOMIZATION("user_profile"),
    CHAT_OPTIONS("chat_options"),
    TOOLS_EDITOR("system_tools"),
    STORAGE("storage"),
    REMOTE_MACHINES("remote_machines"),
    MENU("menu"),
    APP_SETTINGS("AppSettings"),
    DEVELOPER_OPTIONS("developer_options"),
    LAUNCHER_DRAG_TEST("launcher_drag_test"),
    PROVIDERS_DEMO("providers_demo"),
    ICONS("Icons"),
    APP_DATA("AppData"),
    ABOUT("About"),
}

/** Longest a chat selection waits for the drawer to finish closing before it is applied anyway. */
private const val DRAWER_CLOSE_WAIT_MS = 700L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppNavigation(
    viewModel: ChatViewModel,
    configurationViewModel: ConfigurationViewModel,
    settingsViewModel: SettingsViewModel,
    remoteMachinesViewModel: RemoteMachinesViewModel,
    toolFiles: ToolFiles,
    appDataTransfer: AppDataTransferService,
    pendingTerminalMachineId: String? = null,
    onTerminalLaunchHandled: () -> Unit = {},
    pendingAppDeepLink: AppDeepLinkRequest? = null,
    onAppDeepLinkHandled: (Long) -> Unit = {},
    pendingExternalShare: ExternalShareRequest? = null,
    onExternalShareHandled: (Long) -> Unit = {},
    customHttpToolsViewModel: CustomHttpToolsViewModel,
) {
    val httpTools by customHttpToolsViewModel.tools.collectAsStateWithLifecycle()
    var httpEditor by remember { mutableStateOf<CustomHttpTool?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showMediaSheet by rememberSaveable { mutableStateOf(false) }
    val drawerFocusManager = LocalFocusManager.current
    // Opening the sidebar (button or swipe) takes focus away from the chat input.
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) drawerFocusManager.clearFocus(force = true)
    }
    val coroutineScope = rememberCoroutineScope()

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val chatOnTop = navBackStackEntry?.destination?.route?.let { it == AppDestination.CHAT.route } ?: true
    fun showChat() {
        navController.popBackStack(AppDestination.CHAT.route, inclusive = false)
    }
    fun navigate(destination: AppDestination) {
        if (destination == AppDestination.CHAT) {
            showChat()
        } else if (destination == AppDestination.FEATURES &&
            navController.popBackStack(AppDestination.FEATURES.route, inclusive = false)
        ) {
            // Existing Menu entry remains alive until it is popped.
        } else {
            navController.navigate(destination.route) { launchSingleTop = true }
        }
    }

    fun startNewChat() {
        viewModel.startNewChat()
        navigate(AppDestination.CHAT)
    }

    fun startTemporaryChat() {
        viewModel.startTemporaryChat()
        navigate(AppDestination.CHAT)
    }

    fun openConfiguration(destination: AppDestination) {
        navController.navigate(destination.route) { launchSingleTop = true }
    }

    fun openTerminal(machine: RemoteMachine) {
        navController.navigate("terminal/${Uri.encode(machine.id)}") { launchSingleTop = true }
    }

    fun openSkillsSettings() {
        navController.navigate("${AppDestination.CHAT_OPTIONS.route}?tab=skills") { launchSingleTop = true }
    }

    fun openInstructions(instructionId: String? = null) {
        coroutineScope.launch { drawerState.close() }
        val route = buildString {
            append(AppDestination.CHAT_OPTIONS.route)
            append("?tab=instructions")
            instructionId?.let { append("&instructionId=${Uri.encode(it)}") }
        }
        navController.navigate(route) { launchSingleTop = true }
    }

    val conversations: List<ConversationListItem> by viewModel.conversations.collectAsStateWithLifecycle()
    val availableChatIds = remember(conversations) { conversations.mapTo(mutableSetOf()) { it.id } }
    val currentConvState = viewModel.currentConversation.collectAsStateWithLifecycle()
    val loadingConversationId by viewModel.loadingConversationId.collectAsStateWithLifecycle()
    // Streaming republishes the conversation about 20 times a second. Reading it in this function would
    // recompose the drawer, navigation and every CompositionLocal below on each tick, which slows the whole
    // app. Only values that rarely change are derived here; the chat screen reads the conversation itself.
    val currentConvId by remember { derivedStateOf { currentConvState.value?.id } }
    val currentConvModel by remember { derivedStateOf { currentConvState.value?.let { it.providerId to it.modelId } } }
    val currentConvInstructionIds by remember { derivedStateOf { currentConvState.value?.selectedInstructionIds } }
    val retainedChats = remember { RetainedChatPages() }
    val chatAttachments = remember(currentConvId) { retainedChats.selection(currentConvId) }
    var mcpEditor by remember { mutableStateOf<Pair<McpInfo, Boolean>?>(null) }
    var pendingNewProvider by remember { mutableStateOf<ProviderEditorPage?>(null) }
    val isGenerating: Boolean by viewModel.isGenerating.collectAsStateWithLifecycle()
    val generatingConversationIds by viewModel.generatingConversationIds.collectAsStateWithLifecycle()
    val generatingConversationId = currentConvId.takeIf { isGenerating }
    val chatError by viewModel.chatError.collectAsStateWithLifecycle()
    val speakingMessageId by viewModel.speakingMessageId.collectAsStateWithLifecycle()
    val activeTestSpeechServiceId by viewModel.activeTestSpeechServiceId.collectAsStateWithLifecycle()
    val summaryProgress by viewModel.summaryProgress.collectAsStateWithLifecycle()
    val contextWindowStatus by viewModel.contextWindowStatus.collectAsStateWithLifecycle()
    val appPrefs: AppPreferences by settingsViewModel.preferences.collectAsStateWithLifecycle()
    val providers: List<ModelProvider> by viewModel.providers.collectAsStateWithLifecycle()
    val providerGroups: List<ProviderGroup> by configurationViewModel.providerGroups.collectAsStateWithLifecycle()
    val mcpServers: List<McpInfo> by viewModel.mcpServers.collectAsStateWithLifecycle()
    val speechServices: List<SpeechService> by configurationViewModel.speechServices.collectAsStateWithLifecycle()
    val mcpToolsCache by configurationViewModel.mcpToolsCache.collectAsStateWithLifecycle()
    val mcpToolErrors by configurationViewModel.mcpToolErrors.collectAsStateWithLifecycle()
    val mcpToolsLoading by configurationViewModel.mcpToolsLoading.collectAsStateWithLifecycle()
    val remoteMachineState by remoteMachinesViewModel.state.collectAsStateWithLifecycle()
    var remoteMachinePage by remember { mutableStateOf<Any?>(null) }
    var drawerPortForwardMachine by remember { mutableStateOf<RemoteMachine?>(null) }
    LaunchedEffect(pendingTerminalMachineId, remoteMachineState.machines) {
        val machine = remoteMachineState.machines.firstOrNull { it.id == pendingTerminalMachineId }
        if (machine != null) {
            drawerState.close()
            openTerminal(machine)
            onTerminalLaunchHandled()
        }
    }

    val toolSettings by viewModel.toolSettings.collectAsStateWithLifecycle()

    fun persistMusicProfiles(profiles: Map<String, MediaRequestProfile>, preferredId: String) {
        val latest = toolSettings.system["generate_music"] ?: SystemToolConfig()
        viewModel.setSystemTool("generate_music", latest.withMusicProfiles(profiles, preferredId))
    }

    fun persistMediaProfiles(video: Boolean, updatedProfiles: Map<String, MediaRequestProfile>, preferredId: String) {
        val toolName = if (video) "generate_video" else "generate_image"
        // Read the latest config on every write; an editor must never overwrite model or toggle changes.
        val config = toolSettings.system[toolName] ?: SystemToolConfig()
        val profiles = updatedProfiles.withDefaultMediaProfile(video)
        val requestedId = preferredId.takeIf { it in profiles } ?: defaultMediaProfile(video).id
        val selectedId = requestedId.takeIf {
            (config.providerId.isNotBlank() && config.modelId.isNotBlank()) ||
                profiles[it]?.modelIndependent == true
        } ?: defaultMediaProfile(video).id
        val updated = config.withMediaProfiles(video, profiles)
        val next = if (video) updated.copy(
            activeVideoRequestProfileId = selectedId,
            videoGenerationMode = updated.mediaProfile(true, selectedId).mode
        ) else updated.copy(
            activeImageRequestProfileId = selectedId,
            imageGenerationMode = updated.mediaProfile(false, selectedId).mode
        )
        viewModel.setSystemTool(toolName,
            if (profiles[selectedId]?.modelIndependent == true) next.copy(providerId = "", modelId = "") else next)
        // Reset references for deleted profiles and profiles converted to model-independent.
        val kind = if (video) ModelType.VIDEO_GENERATION else ModelType.IMAGE_GENERATION
        providers.forEach { source ->
            source.config.modelConfigs.forEach { (id, model) ->
                if (model.modelType == kind && model.mediaRequestProfileId.isNotBlank() &&
                    (model.mediaRequestProfileId !in profiles ||
                        profiles[model.mediaRequestProfileId]?.modelIndependent == true)) {
                    val fallback = next.mediaProfile(video, "")
                    configurationViewModel.saveModelConfiguration(source.id, id, model.copy(
                        mediaRequestProfileId = fallback.id,
                        mediaApiType = fallback.mode
                    ))
                }
            }
        }
    }
    val queuedMessages by viewModel.queuedMessages.collectAsStateWithLifecycle()
    val importedProviderId by configurationViewModel.importedProviderId.collectAsStateWithLifecycle()
    val providerImportFailed by configurationViewModel.providerImportFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    var pendingExport by rememberSaveable(stateSaver = NullableFilePathSaver) { mutableStateOf<File?>(null) }
    var exportPickerOpen by rememberSaveable { mutableStateOf(false) }
    var pendingImport by rememberSaveable { mutableStateOf(false) }
    val preparingExport by appDataTransfer.preparingExport.collectAsStateWithLifecycle()
    val preparedExport by appDataTransfer.preparedExport.collectAsStateWithLifecycle()
    val importInProgress by appDataTransfer.importInProgress.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val archive = pendingExport
        val wasWaiting = exportPickerOpen
        exportPickerOpen = false
        if (uri == null || !wasWaiting) {
            pendingExport = null
            archive?.delete()
            return@rememberLauncherForActivityResult
        }
        if (archive == null || !archive.isFile) {
            pendingExport = null
            Toast.makeText(context, R.string.export_write_failed, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        appDataTransfer.writeExportToUri(uri, archive)
        pendingExport = null
    }

    LaunchedEffect(preparedExport, exportPickerOpen) {
        if (preparedExport != null && !exportPickerOpen && pendingExport == null) {
            val archive = appDataTransfer.consumePreparedExport() ?: return@LaunchedEffect
            pendingExport = archive
            exportPickerOpen = true
            try {
                exportLauncher.launch(BackupExportKind.FULL_BACKUP.fileName)
            } catch (_: Exception) {
                exportPickerOpen = false
                pendingExport = null
                archive.delete()
                Toast.makeText(context, R.string.export_write_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(exportPickerOpen, pendingExport?.absolutePath, preparedExport, preparingExport) {
        if (!exportPickerOpen && pendingExport == null && preparedExport == null && !preparingExport) {
            withContext(Dispatchers.IO) {
                appDataTransfer.cleanupAbandonedExports()
            }
        }
    }

    fun exportData() {
        if (preparingExport || exportPickerOpen || pendingExport != null || importInProgress) return
        appDataTransfer.prepareExport()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val shouldImport = pendingImport
        pendingImport = false
        if (uri == null || !shouldImport || importInProgress) return@rememberLauncherForActivityResult
        appDataTransfer.importFromUri(
            uri = uri,
            beforeFullRestore = viewModel::prepareForRestore,
            afterFullRestore = viewModel::finishRestore
        )
    }

    fun importData() {
        if (pendingImport || importInProgress || preparingExport || exportPickerOpen) return
        pendingImport = true
        try {
            importLauncher.launch(arrayOf("application/zip", "application/json", "text/*"))
        } catch (_: Exception) {
            pendingImport = false
            Toast.makeText(context, R.string.export_open_file_failed, Toast.LENGTH_LONG).show()
        }
    }
    // The app preference is the default for new chats. Existing chats keep showing
    // the model they last used, independently of that default.
    val conversationModel =
        currentConvModel?.takeIf { (_, modelId) -> appPrefs.persistSelectedModel && modelId.isNotBlank() }
            ?.let { (conversationProviderId, conversationModelId) ->
                providers.firstOrNull { provider ->
                    provider.id == conversationProviderId &&
                            provider.config.modelIds?.contains(conversationModelId) != false &&
                            (provider.config.modelConfigs[conversationModelId]?.modelType
                                ?: ModelType.TEXT_GENERATION) == ModelType.TEXT_GENERATION
                }?.let { conversationProviderId to conversationModelId }
            }
    val chatProviderId = conversationModel?.first ?: appPrefs.selectedModel.providerId
    val chatModelId = conversationModel?.second ?: appPrefs.selectedModel.modelId
    LaunchedEffect(Unit) {
        org.starfall.generative.shared.state.LocalWriteErrors.errors.collect {
            Toast.makeText(context, R.string.save_change_failed, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(importedProviderId) {
        importedProviderId?.let { id ->
            drawerState.close()
            navController.navigate("providers/edit/$id") { launchSingleTop = true }
            configurationViewModel.providerImportOpened(id)
        }
    }
    LaunchedEffect(providerImportFailed) {
        if (providerImportFailed) {
            Toast.makeText(context, localizedResources.getString(R.string.provider_import_failed), Toast.LENGTH_LONG).show()
            configurationViewModel.clearProviderImportFailed()
        }
    }
    LaunchedEffect(chatOnTop) {
        if (!chatOnTop) drawerState.close()
    }
    LaunchedEffect(pendingAppDeepLink?.id) {
        val request = pendingAppDeepLink ?: return@LaunchedEffect
        when (val action = request.action) {
            AppDeepLink.Providers -> navigate(AppDestination.PROVIDERS)
            is AppDeepLink.Mcp -> {
                mcpEditor = null
                navigate(AppDestination.MCP)
                if (action.name != null && action.url != null) {
                    try {
                        configurationViewModel.importMcpLink(action.name, action.url)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.save_change_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            is AppDeepLink.Chat -> {
                if (action.temporary) viewModel.startTemporaryChat() else viewModel.startNewChat()
                navigate(AppDestination.CHAT)
                if (!viewModel.sendMessage(action.text)) {
                    Toast.makeText(context, R.string.save_change_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        onAppDeepLinkHandled(request.id)
    }


    LaunchedEffect(pendingExternalShare?.id) {
        val request = pendingExternalShare ?: return@LaunchedEffect
        // Share to the current chat, without replacing the current draft or sending it.
        drawerState.close()
        navigate(AppDestination.CHAT)
        // If there is no active chat (e.g. first launch), create a stable blank draft
        // before the asynchronous last-conversation restore can replace the selection.
        if (viewModel.currentConversation.value == null) viewModel.startNewChat()
        val destination = viewModel.currentConversation.value
        val selection = retainedChats.selection(destination?.id)
        selection.requestChatMode()
        request.content.text?.let(selection::queueExternalText)
        val temporaryId = destination?.takeIf { it.isTemporary }?.id
        var failed = false
        try {
            request.content.streams.forEach { uri ->
                try {
                    val file = stageSharedAttachment(
                        context, toolFiles, uri, request.content.fallbackMime, temporaryId
                    )
                    selection.addAttachments(listOf(file))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                }
            }
            if (failed) Toast.makeText(context, R.string.shared_attachment_failed, Toast.LENGTH_LONG).show()
        } finally {
            onExternalShareHandled(request.id)
        }
    }

    CompositionLocalProvider(
        org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences provides appPrefs,
        org.starfall.generative.shared.ui.layout.LocalItemTransparency provides appPrefs.itemTransparency,
        org.starfall.generative.shared.ui.background.LocalProfileModalImages provides
            org.starfall.generative.shared.ui.background.ProfileModalImages(
                appPrefs.userProfile.sheetBackground, appPrefs.userProfile.dialogBackground,
                appPrefs.userProfile.sheetTransparency, appPrefs.userProfile.dialogTransparency
            ),
        org.starfall.generative.shared.ui.layout.LocalInputAreaAppearance provides
            org.starfall.generative.shared.ui.layout.InputAreaAppearance(appPrefs.inputAreaTransparency, appPrefs.inputAreaBlur),
        LocalChatAttachmentSelection provides chatAttachments,
        LocalInstructionControls provides InstructionControls(
            library = appPrefs.instructionLibraryForSelection(currentConvInstructionIds, currentConvId),
            setInstructionEnabled = viewModel::setInstructionEnabled,
            openInstructions = { openInstructions() },
            editInstruction = { instructionId -> openInstructions(instructionId) }
        ),
        LocalToolControls provides ToolControls(
            servers = mcpServers,
            customHttpTools = httpTools,
            setCustomHttpTool = customHttpToolsViewModel::setEnabled,
            openCustomHttpTools = { openConfiguration(AppDestination.CUSTOM_HTTP_TOOLS) },
            machines = remoteMachineState.machines,
            settings = toolSettings,
            providers = providers,
            setSystem = viewModel::setSystemTool,
            setMcp = viewModel::setQuickMcp,
            setMachines = viewModel::setMachinesEnabled,
            setMachine = { machineId, enabled ->
                viewModel.setMachineEnabled(machineId, enabled, remoteMachineState.machines.map { it.id })
            },
            selectMachine = { machineId ->
                viewModel.selectRemoteMachine(machineId, remoteMachineState.machines.map { it.id })
            },
            setMachineTool = viewModel::setMachineToolEnabled,
            setSendFile = viewModel::setSendFileEnabled,
            setWebSearch = viewModel::setWebSearchEnabled,
            setSearchTools = viewModel::setSearchToolsEnabled,
            setAgentSkillsSearch = viewModel::setAgentSkillsSearchEnabled
        )
    ) {
        if (showMediaSheet) MediaStorageSheet(toolFiles) { showMediaSheet = false }
        val drawerScrim = modalScrimColor(DrawerDefaults.scrimColor)
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = chatOnTop,
            scrimColor = drawerScrim,
            drawerContent = {
                ConversationSideDrawer(
                    conversations = conversations,
                    providers = providers,
                    currentConversationId = currentConvId,
                    isOpen = drawerState.isOpen,
                    generatingConversationIds = generatingConversationIds,
                    organization = appPrefs.chatRelations.sidebar,
                    backgroundImage = appPrefs.userProfile.drawerBackground,
                    backgroundTransparency = appPrefs.userProfile.drawerTransparency,
                    onUpdateOrganization = settingsViewModel::updateSidebar,
                    onDeleteConversations = { ids ->
                        viewModel.deleteConversations(ids, chatAttachments.attachments.value)
                        retainedChats.discard(ids)
                    },
                    onSelectConversation = { item ->
                        navigate(AppDestination.CHAT)
                        // Switching chats composes the whole history. Doing that while the drawer is still
                        // sliding away makes the close animation drop frames, so the switch waits for the
                        // drawer to settle (bounded, in case it is reopened or never reports closed).
                        coroutineScope.launch {
                            withTimeoutOrNull(DRAWER_CLOSE_WAIT_MS) {
                                snapshotFlow { drawerState.isClosed }.first { it }
                            }
                            viewModel.selectConversation(item.id)
                        }
                    },
                    onNewChat = {
                        startNewChat()
                    },
                    onRenameConversation = { id, newTitle ->
                        viewModel.renameConversation(id, newTitle)
                    },
                    onSuggestConversationTitle = viewModel::suggestConversationTitle,
                    onDeleteConversation = {
                        viewModel.deleteConversations(setOf(it), chatAttachments.attachments.value)
                        retainedChats.discard(setOf(it))
                    },
                    onNavigateToSettings = {
                        navigate(AppDestination.FEATURES)
                    },
                    featuresLogoImage = appPrefs.featuresLogoImage,
                    onCloseDrawer = {
                        coroutineScope.launch { drawerState.close() }
                    },
                    onOpenMediaStorage = {
                        coroutineScope.launch {
                            drawerState.close()
                            showMediaSheet = true
                        }
                    },
                    onOpenRemoteMachines = {
                        coroutineScope.launch { drawerState.close() }
                        openConfiguration(AppDestination.REMOTE_MACHINES)
                    },
                    onPortForward = { machine ->
                        drawerPortForwardMachine = machine
                        coroutineScope.launch { drawerState.close() }
                    },
                    onOpenTerminal = { machine ->
                        coroutineScope.launch { drawerState.close() }
                        openTerminal(machine)
                    },
                    onSaveMachineTools = remoteMachinesViewModel::saveMachineTools
                )
            }
        ) {
            val focusManager = LocalFocusManager.current
            LaunchedEffect(chatOnTop) {
                if (!chatOnTop) focusManager.clearFocus(force = true)
            }
            Box(Modifier.fillMaxSize()) {
                RetainedNavHost(
                    navController = navController,
                    startDestination = AppDestination.CHAT.route,
                    menuBackground = {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            org.starfall.generative.shared.ui.background.ProfileBackground(
                                appPrefs.userProfile.menuBackground,
                                transparency = appPrefs.userProfile.menuTransparency
                            )
                        }
                    }
                ) {
                    composable(AppDestination.CHAT.route) {
                        RetainedChatConversations(
                            state = retainedChats,
                            conversation = currentConvState.value,
                            availableIds = availableChatIds,
                            foreground = chatOnTop
                        ) { chatPage, active ->
                    ChatScreen(
                        backgroundImage = appPrefs.userProfile.chatBackground,
                        backgroundTransparency = appPrefs.userProfile.chatTransparency,
                        conversation = chatPage,
                        // Keep the outgoing viewport intact while the next history loads.
                        loadingConversationId = loadingConversationId.takeIf { active && chatPage == null },
                        isDrawerOpening = drawerState.targetValue == DrawerValue.Open || drawerState.isOpen,
                        activeStream = viewModel.activeStreamForCurrentConversation.takeIf { active },
                        isGenerating = isGenerating && active,
                        generatingConversationId = generatingConversationId.takeIf { active },
                        chatError = chatError.takeIf { active },
                        providers = providers,
                        providerGroups = providerGroups,
                        modelPickerCollapsedGroups = appPrefs.modelPickerCollapsedGroups,
                        modelPickerCollapsedProviders = appPrefs.modelPickerCollapsedProviders,
                        onModelPickerCollapsedGroupsChange = settingsViewModel::setModelPickerCollapsedGroups,
                        onModelPickerCollapsedProvidersChange = settingsViewModel::setModelPickerCollapsedProviders,
                         selectedProviderId = chatProviderId,
                          selectedModelName = chatModelId,
                         agentSkills = appPrefs.agentSkills.skills,
                         onOpenSkillsSettings = { openSkillsSettings() },
                          featuresLogoImage = appPrefs.featuresLogoImage,
                        autoScroll = appPrefs.autoScroll,
                        onSendMedia = viewModel::sendMedia,
                        onMediaModeChange = viewModel::setComposerMedia,
                        onResendUserMessage = viewModel::resendUserMessage,
                        onSendMessage = { text, files -> viewModel.sendMessage(text, files) },
                        onSendMessageWithSkill = { text, files, requested, skillName ->
                            viewModel.sendMessage(text, files, requested, skillName)
                        },
                        onStopGenerating = {
                            viewModel.stopGeneration()
                        },
                        onOpenDrawer = {
                            coroutineScope.launch { drawerState.open() }
                        },
                        onNewChat = {
                            startNewChat()
                        },
                        onOpenTemporaryChat = { startTemporaryChat() },
                        onOpenSettings = {
                            navigate(AppDestination.FEATURES)
                        },
                        onRegenerate = { id ->
                            viewModel.regenerateMessage(id)
                        },
                        onRetryFromError = { id ->
                            viewModel.retryFromError(id)
                        },
                        onEditMessage = { id, content, files ->
                            viewModel.editMessage(id, content, files)
                        },
                        onDeleteMessage = { id ->
                            viewModel.deleteMessage(id)
                        },
                        onDeleteAssistantBlock = { id, block ->
                            viewModel.deleteAssistantBlock(id, block)
                        },
                        onDeleteMessageVersion = { id ->
                            viewModel.deleteMessageVersion(id)
                        },
                        queuedMessages = if (active) queuedMessages else emptyList(),
                        onEditQueuedMessage = { id, content, files ->
                            viewModel.editQueuedMessage(id, content, files)
                        },
                        onDeleteQueuedMessage = { id ->
                            viewModel.deleteQueuedMessage(id)
                        },
                        onSwitchVersion = { id, idx ->
                            viewModel.switchMessageVersion(id, idx)
                        },
                        onSelectModel = { provId, modelId ->
                            viewModel.selectModel(provId, modelId)
                        },
                        summaryProgress = summaryProgress.takeIf { active },
                        contextWindowStatus = contextWindowStatus.takeIf { active },
                        onSetReasoningEffort = viewModel::setConversationReasoningEffort,
                        onSetThinkingPassback = configurationViewModel::setThinkingPassback,
                        onSetModelReasoningEffort = configurationViewModel::setModelReasoningEffort,
                        onSetModelTools = configurationViewModel::setModelTools,
                        onEditModel = { providerId, modelId ->
                            navController.navigate(
                                "providers/edit/${Uri.encode(providerId)}?modelId=${
                                    Uri.encode(
                                        modelId
                                    )
                                }&returnToChat=true"
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onEditProvider = { providerId ->
                            navController.navigate("providers/edit/${Uri.encode(providerId)}?returnToChat=true") {
                                launchSingleTop = true
                            }
                        },
                        onStartConversationSummary = viewModel::startConversationSummary,
                        onCancelConversationSummary = viewModel::cancelConversationSummary,
                        onSummaryRoleChange = viewModel::setSummaryRole,
                        onDeleteSummary = viewModel::deleteConversationSummary,
                        speakingMessageId = speakingMessageId.takeIf { active },
                        onReadMessage = { messageId, text ->
                            viewModel.speakText(text, messageId)
                        },
                        onFetchOllamaModels = { url ->
                            configurationViewModel.fetchOllamaModels(url)
                        },
                         onOpenFeatureToolsSettings = { openConfiguration(AppDestination.TOOLS_EDITOR) },
                         onOpenMcpConnectorsSettings = { openConfiguration(AppDestination.MCP) }
                     )
                        }
                    }
                    composable(
                        "terminal/{machineId}",
                        arguments = listOf(navArgument("machineId") { type = NavType.StringType })
                    ) { entry ->
                        val machineId = entry.arguments?.getString("machineId")
                        val machine = remoteMachineState.machines.firstOrNull { it.id == machineId }
                        if (machine != null) {
                            key(machine.id) {
                                RemoteTerminalScreen(
                                    machine = machine,
                                    openTerminal = { selectedMachine, askSecret ->
                                        remoteMachinesViewModel.openTerminal(selectedMachine, askSecret = askSecret)
                                    },
                                    disconnect = remoteMachinesViewModel::disconnect,
                                    onDismiss = { navController.popBackStack() }
                                )
                            }
                        } else {
                            // Wait for stored machines to load on a cold notification launch.
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                LoadingIndicator()
                            }
                        }
                    }

                    composable("providers/new") {
                        val page = pendingNewProvider
                        if (page == null) {
                            LaunchedEffect(Unit) { navController.popBackStack() }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                LoadingIndicator()
                            }
                        } else {
                            // Clear the pending page only once this screen has left composition. Clearing it
                            // inside onDismiss made the exiting screen recompose into the `page == null`
                            // branch above, whose popBackStack() ran a second time and also popped Providers.
                            DisposableEffect(Unit) { onDispose { pendingNewProvider = null } }
                            key(page.provider.id) {
                                ProviderEditor(
                                    initialProvider = page.provider,
                                    isNew = page.isNew,
                                    onSave = { saved ->
                                        configurationViewModel.saveProvider(saved)
                                        pendingNewProvider = ProviderEditorPage(saved, false)
                                        Toast.makeText(
                                            context,
                                            localizedResources.getString(R.string.provider_saved),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onDismiss = { navController.popBackStack() },
                                    onSaveModels = configurationViewModel::saveProviderModels,
                                    onSaveIcon = configurationViewModel::saveProviderIcon,
                                    onSaveModelIcon = configurationViewModel::saveModelIcon,
                                    onUpdateAuxiliary = configurationViewModel::saveProviderAuxiliaryChange,
                                    onAutoCreateProvider = { newProvider ->
                                        configurationViewModel.saveProvider(newProvider)
                                        pendingNewProvider = ProviderEditorPage(newProvider, false)
                                    },
                                    onReorderModels = configurationViewModel::reorderProviderModels,
                                    onReorderModelsTracked = configurationViewModel::reorderProviderModelsTracked,
                                    onAuthorizeProvider = configurationViewModel::authorizeProvider,
                                    onCancelProviderAuthorization = configurationViewModel::cancelProviderAuthorization,
                                    onClearOAuthCredentials = configurationViewModel::clearOAuthCredentials,
                                    onTestConnection = configurationViewModel::testConnection,
                                    onFetchModels = configurationViewModel::fetchProviderModels,
                                    customImageMediaProfiles = toolSettings.system.values
                                        .flatMap { it.imageRequestProfiles.values }
                                        .associateBy { it.id },
                                    customVideoMediaProfiles = toolSettings.system.values
                                        .flatMap { it.videoRequestProfiles.values }
                                        .associateBy { it.id }
                                )
                            }
                        }
                    }

                    composable(
                        route = "providers/edit/{providerId}?modelId={modelId}&returnToChat={returnToChat}",
                        arguments = listOf(navArgument("modelId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }, navArgument("returnToChat") {
                            type = NavType.BoolType
                            defaultValue = false
                        })
                    ) { entry ->
                        val providerId = entry.arguments?.getString("providerId").orEmpty()
                        val modelId = entry.arguments?.getString("modelId")
                        // Store the origin on this back-stack entry, including across recreation.
                        val returnToChat = entry.arguments?.getBoolean("returnToChat") == true
                        val dismissEditor: () -> Unit = {
                            if (navController.currentBackStackEntry == entry && navController.popBackStack()) {
                                if (returnToChat) showChat()
                            }
                        }
                        TargetedEditorContent(
                            recordId = providerId,
                            title = stringResource(R.string.provider_editor),
                            loadingMessage = stringResource(R.string.loading_provider_editor),
                            onBack = dismissEditor,
                            load = { configurationViewModel.loadProviderForEditor(providerId) }
                        ) { provider ->
                            key(provider.id) {
                                ProviderEditor(
                                    initialProvider = provider,
                                    isNew = false,
                                    initialEditingModelId = modelId,
                                    onSave = { saved ->
                                        configurationViewModel.saveProvider(saved)
                                        Toast.makeText(
                                            context,
                                            localizedResources.getString(R.string.provider_saved),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onDismiss = dismissEditor,
                                    onDelete = configurationViewModel::deleteProvider,
                                    onSaveModels = configurationViewModel::saveProviderModels,
                                    onSaveIcon = configurationViewModel::saveProviderIcon,
                                    onSaveModelIcon = configurationViewModel::saveModelIcon,
                                    onUpdateAuxiliary = configurationViewModel::saveProviderAuxiliaryChange,
                                    onReorderModels = configurationViewModel::reorderProviderModels,
                                    onReorderModelsTracked = configurationViewModel::reorderProviderModelsTracked,
                                    onAuthorizeProvider = configurationViewModel::authorizeProvider,
                                    onCancelProviderAuthorization = configurationViewModel::cancelProviderAuthorization,
                                    onClearOAuthCredentials = configurationViewModel::clearOAuthCredentials,
                                    onTestConnection = configurationViewModel::testConnection,
                                    onFetchModels = configurationViewModel::fetchProviderModels,
                                    customImageMediaProfiles = toolSettings.system.values
                                        .flatMap { it.imageRequestProfiles.values }
                                        .associateBy { it.id },
                                    customVideoMediaProfiles = toolSettings.system.values
                                        .flatMap { it.videoRequestProfiles.values }
                                        .associateBy { it.id }
                                )
                            }
                        }
                    }

                    composable(AppDestination.PROVIDERS.route) {
                        ProvidersScreen(
                            providers = providers,
                            providerGroups = providerGroups,
                            collapsedSectionsState = appPrefs.providersCollapsedSections,
                            onCollapsedSectionsChange = settingsViewModel::setProvidersCollapsedSections,
                            isGridView = appPrefs.showProvidersAsGrid,
                            onToggleGridView = { configurationViewModel.setShowProvidersAsGrid(it) },
                            onOpenProviderEditor = { provider, isNew ->
                                if (isNew) {
                                    pendingNewProvider = ProviderEditorPage(provider, true)
                                    navController.navigate("providers/new")
                                } else {
                                    navController.navigate("providers/edit/${Uri.encode(provider.id)}")
                                }
                            },
                            onSaveGroup = configurationViewModel::saveProviderGroup,
                            onSaveGroupIcon = configurationViewModel::saveProviderGroupIcon,
                            onDeleteGroup = { groupId ->
                                configurationViewModel.deleteProviderGroup(groupId)
                                settingsViewModel.setProvidersCollapsedSections(appPrefs.providersCollapsedSections - groupId)
                                settingsViewModel.setModelPickerCollapsedGroups(appPrefs.modelPickerCollapsedGroups - groupId)
                            },
                            onMoveProviderToGroup = configurationViewModel::moveProviderToGroup,
                            onPlaceProviderImmediately = configurationViewModel::queueProviderPlacement,
                            onPlaceProviderTracked = configurationViewModel::queueProviderPlacementTracked,
                            onReorderRootItemsTracked = configurationViewModel::reorderProviderRootItemsTracked,
                            onDeleteProvider = { providerId ->
                                configurationViewModel.deleteProvider(providerId)
                                settingsViewModel.setModelPickerCollapsedProviders(appPrefs.modelPickerCollapsedProviders - providerId)
                            },
                            onSetProviderHidden = configurationViewModel::setProviderHidden,
                            onReorderProviders = configurationViewModel::reorderProviders,
                            onReorderRootItems = configurationViewModel::reorderProviderRootItems,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AppDestination.CUSTOM_HTTP_TOOLS.route) {
                        DisposableEffect(Unit) { onDispose { httpEditor = null } }
                        RetainedScreenContent(editor = httpEditor) { tool ->
                            if (tool != null) {
                                HttpToolEditor(
                                    tool = tool, onSave = customHttpToolsViewModel::save,
                                    onBack = { httpEditor = null }
                                )
                            } else {
                                CustomHttpToolsScreen(
                                    tools = httpTools, onOpenEditor = { httpEditor = it },
                                    onSetEnabled = customHttpToolsViewModel::setEnabled,
                                    onDelete = customHttpToolsViewModel::delete,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }

                    composable(AppDestination.MCP.route) {
                        DisposableEffect(Unit) { onDispose { mcpEditor = null } }
                        RetainedScreenContent(editor = mcpEditor) { page ->
                            if (page != null) {
                                TargetedEditorContent(
                                    recordId = page.first.id,
                                    title = stringResource(R.string.mcp_editor),
                                    loadingMessage = stringResource(R.string.loading_mcp_server_editor),
                                    onBack = { mcpEditor = null },
                                    load = { configurationViewModel.loadMcpServerForEditor(page.first.id) },
                                    draft = page.first.takeIf { page.second }
                                ) { server ->
                                    key(server.id) {
                                        ServerEditor(
                                            initialServer = server,
                                            isNew = page.second,
                                            onRefreshTools = { configurationViewModel.refreshMcpTools(it) },
                                            onAuthorizeOAuth = { configurationViewModel.authorizeMcpOAuth(it) },
                                            onClearOAuth = { configurationViewModel.clearMcpOAuth(it) },
                                            cachedTools = mcpToolsCache[server.id] ?: server.cachedTools,
                                            cachedError = mcpToolErrors[server.id],
                                            cachedLoading = server.id in mcpToolsLoading,
                                            toolSettings = toolSettings,
                                            onSetToolEnabled = viewModel::setMcpToolEnabled,
                                            onSetToolParameter = viewModel::setMcpToolParameter,
                                            onDismiss = { mcpEditor = null },
                                            onSave = { configurationViewModel.saveMcpServer(it) },
                                            onSaveIcon = configurationViewModel::saveMcpServerIcon
                                        )
                                    }
                                }
                            } else {
                                ServerListScreen(
                                    mcpServers = mcpServers,
                                    isGridView = appPrefs.showMcpAsGrid,
                                    onToggleGridView = { configurationViewModel.setShowMcpAsGrid(it) },
                                    toolsCache = mcpToolsCache,
                                    toolErrors = mcpToolErrors,
                                    toolsLoading = mcpToolsLoading,
                                    toolSettings = toolSettings,
                                    onSetToolEnabled = viewModel::setMcpToolEnabled,
                                    onSetMcpEnabled = viewModel::setQuickMcp,
                                    onDeleteMcpServer = { configurationViewModel.deleteMcpServer(it) },
                                    onReorderMcpServers = configurationViewModel::reorderMcpServers,
                                    onOpenEditor = { server, isNew -> mcpEditor = server to isNew },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }

                    composable(AppDestination.SPEECH.route) {
                        SpeechServicesScreen(
                            speechServices = speechServices,
                            isGridView = appPrefs.showSpeechAsGrid,
                            onToggleGridView = { configurationViewModel.setShowSpeechAsGrid(it) },
                            providers = providers,
                            selectedSpeechServiceId = appPrefs.selectedSpeechServiceId,
                            onSetTtsProvider = viewModel::selectSpeechService,
                            onSaveService = { configurationViewModel.saveSpeechService(it) },
                            onSaveServiceIcon = configurationViewModel::saveSpeechServiceIcon,
                            onDeleteService = { configurationViewModel.deleteSpeechService(it) },
                            onReorderServices = configurationViewModel::reorderSpeechServices,
                            onTestVoice = viewModel::testVoice,
                            onBack = { navController.popBackStack() },
                            activeTestServiceId = activeTestSpeechServiceId,
                            onStopPlayback = viewModel::stopSpeaking
                        )
                    }

                    composable(
                        route = "${AppDestination.CHAT_OPTIONS.route}?tab={tab}&instructionId={instructionId}",
                        arguments = listOf(
                            navArgument("tab") { type = NavType.StringType; defaultValue = "helpers" },
                            navArgument("instructionId") { type = NavType.StringType; defaultValue = "" }
                        )
                    ) { chatOptionsEntry ->
                        ChatOptionsScreen(
                            initialTab = chatOptionsEntry.arguments?.getString("tab") ?: "helpers",
                            initialInstructionId = chatOptionsEntry.arguments?.getString("instructionId").orEmpty(),
                            instructionLibrary = appPrefs.instructionLibrary,
                            onInstructionLibraryChange = viewModel::setInstructionLibrary,
                            agentSkills = appPrefs.agentSkills.skills,
                            onAgentSkillsChange = viewModel::setAgentSkills,
                            providers = providers,
                            settings = toolSettings,
                            mcpServers = mcpServers,
                            onSetWebSearchProvider = viewModel::setWebSearchProvider,
                            onSetWebSearchEnabled = viewModel::setWebSearchEnabled,
                            speechServices = speechServices,
                            selectedSpeechServiceId = appPrefs.selectedSpeechServiceId,
                            onSetTtsProvider = { viewModel.selectSpeechService(it) },
                            appPreferences = appPrefs,
                            onAutoSummaryEnabledChange = settingsViewModel::setAutoSummaryEnabled,
                            onSummarySuggestionEnabledChange = settingsViewModel::setSummarySuggestionEnabled,
                            onAutoSummaryPercentChange = settingsViewModel::setAutoSummaryPercent,
                            providerGroups = providerGroups,
                            collapsedGroupIds = appPrefs.modelPickerCollapsedGroups,
                            collapsedProviderIds = appPrefs.modelPickerCollapsedProviders,
                            onCollapsedGroupIdsChange = settingsViewModel::setModelPickerCollapsedGroups,
                            onCollapsedProviderIdsChange = settingsViewModel::setModelPickerCollapsedProviders,
                            onSave = viewModel::setSystemTool,
                            onEditProvider = { providerId ->
                                navController.navigate("providers/edit/${Uri.encode(providerId)}") {
                                    launchSingleTop = true
                                }
                            },
                            onEditModel = { providerId, modelId ->
                                navController.navigate(
                                    "providers/edit/${Uri.encode(providerId)}?modelId=${Uri.encode(modelId)}"
                                ) { launchSingleTop = true }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(AppDestination.TOOLS_EDITOR.route) {
                        ToolsEditor(
                            providers = providers,
                            settings = toolSettings,
                            onSave = viewModel::setSystemTool,
                            onSetSendFile = viewModel::setSendFileEnabled,
                            onSetSearchTools = viewModel::setSearchToolsEnabled,
                            onSetAgentSkillsSearch = viewModel::setAgentSkillsSearchEnabled,
                            providerGroups = providerGroups,
                            collapsedGroupIds = appPrefs.modelPickerCollapsedGroups,
                            collapsedProviderIds = appPrefs.modelPickerCollapsedProviders,
                            onCollapsedGroupIdsChange = settingsViewModel::setModelPickerCollapsedGroups,
                            onCollapsedProviderIdsChange = settingsViewModel::setModelPickerCollapsedProviders,
                            onSaveModelConfiguration = configurationViewModel::saveModelConfiguration,
                            onEditProvider = { providerId ->
                                navController.navigate("providers/edit/${Uri.encode(providerId)}") {
                                    launchSingleTop = true
                                }
                            },
                            onEditModel = { providerId, modelId ->
                                navController.navigate(
                                    "providers/edit/${Uri.encode(providerId)}?modelId=${Uri.encode(modelId)}"
                                ) { launchSingleTop = true }
                            },
                            onOpenMusicProfiles = { navController.navigate(MediaProfileRoutes.library(MediaKind.MUSIC)) },
                            onOpenMediaProfiles = { video ->
                                navController.navigate(MediaProfileRoutes.library(video)) { launchSingleTop = true }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = MediaProfileRoutes.LIBRARY,
                        arguments = listOf(navArgument("kind") { type = NavType.StringType })
                    ) { entry ->
                        val kind = MediaKind.entries.firstOrNull { it.name.equals(entry.arguments?.getString("kind"), true) } ?: MediaKind.IMAGE
                        val video = kind == MediaKind.VIDEO
                        val music = kind == MediaKind.MUSIC
                        val toolName = kind.toolName
                        val config = toolSettings.system[toolName] ?: SystemToolConfig()
                        MediaRequestChainSettings(
                            provider = if (music) null else providers.firstOrNull { it.id == config.providerId },
                            config = config,
                            onAutoSave = { profiles, activeId -> if (music) persistMusicProfiles(profiles, activeId) else persistMediaProfiles(video, profiles, activeId) },
                            onOpenProfile = { id -> navController.navigate(MediaProfileRoutes.editor(kind, id)) },
                            onAddProfile = { navController.navigate(MediaProfileRoutes.editor(kind, "new")) },
                            onBack = { navController.popBackStack() },
                            video = video, kind = kind
                        )
                    }
                    composable(
                        route = MediaProfileRoutes.EDITOR,
                        arguments = listOf(
                            navArgument("kind") { type = NavType.StringType },
                            navArgument("profileId") { type = NavType.StringType }
                        )
                    ) { entry ->
                        val kind = MediaKind.entries.firstOrNull { it.name.equals(entry.arguments?.getString("kind"), true) } ?: MediaKind.IMAGE
                        val video = kind == MediaKind.VIDEO
                        val music = kind == MediaKind.MUSIC
                        val profileId = entry.arguments?.getString("profileId").orEmpty()
                        val toolName = kind.toolName
                        val config = toolSettings.system[toolName] ?: SystemToolConfig()
                        val profile = if (profileId == "new") {
                            val id = rememberSaveable { UUID.randomUUID().toString() }
                            // Empty profiles are not inserted until a valid name can be auto-saved.
                            remember(id) { MediaRequestProfile(id = id, name = "", mode = if (music) MediaGenerationMode.CUSTOM else MediaGenerationMode.DEFAULT) }
                        } else {
                            remember(profileId) { config.profiles(kind)[profileId] }
                        }
                        if (profile == null) {
                            LaunchedEffect(profileId) { navController.popBackStack() }
                        } else {
                            key(profile.id) {
                                MediaRequestProfileEditor(
                                    provider = if (music) null else providers.firstOrNull { it.id == config.providerId },
                                    config = config,
                                    profile = profile,
                                    video = video, kind = kind,
                                    onAutoSave = { updated ->
                                        val latest = toolSettings.system[toolName] ?: SystemToolConfig()
                                        val current = latest.profiles(kind)
                                        val selected = if (music) latest.activeMusicRequestProfileId else if (video) latest.activeVideoRequestProfileId
                                            else latest.activeImageRequestProfileId
                                        if (music) persistMusicProfiles(current + (updated.id to updated), selected)
                                        else persistMediaProfiles(video, current + (updated.id to updated), selected)
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                    composable(AppDestination.STORAGE.route) {
                        StorageScreen(
                            store = toolFiles,
                            onBack = { navController.popBackStack() },
                            onBrowseFiles = { ExplorerActivity.open(context) }
                        )
                    }
                    composable(AppDestination.REMOTE_MACHINES.route) {
                        // Sub-pages are transient: never restore an editor/settings page on re-entry.
                        DisposableEffect(Unit) {
                            onDispose {
                                remoteMachinePage = null
                            }
                        }
                        RetainedScreenContent(
                            editor = remoteMachinePage
                        ) { selected ->
                            when {
                                selected is MachineEditorPage -> MachineDetailsScreen(
                                    initial = selected.machine,
                                    state = remoteMachineState,
                                    onSave = {
                                        remoteMachinesViewModel.saveMachine(it)
                                        remoteMachinePage = null
                                    },
                                    onBack = { remoteMachinePage = null },
                                    onRefreshIp = { remoteMachinesViewModel.refreshIp(it) },
                                    onFetchUsername = { remoteMachinesViewModel.fetchGCloudUsername(it) },
                                    onAuthorizeOAuth = { remoteMachinesViewModel.authorize(it) },
                                    onStoreAndroidOAuthToken = { client, token ->
                                        remoteMachinesViewModel.storeAndroidOAuthToken(
                                            client,
                                            token
                                        )
                                    }
                                )

                                selected == RemoteMachineSettingsPage.SETTINGS -> RemoteMachineSettings(
                                    state = remoteMachineState,
                                    viewModel = remoteMachinesViewModel,
                                    onBack = { remoteMachinePage = null }
                                )

                                else -> RemoteMachinesScreen(
                                    viewModel = remoteMachinesViewModel,
                                    onBack = { navController.popBackStack() },
                                    onOpenEditor = { machine, isNew ->
                                        remoteMachinePage = MachineEditorPage(machine, isNew)
                                    },
                                    onOpenSettings = { remoteMachinePage = RemoteMachineSettingsPage.SETTINGS },
                                    onOpenTerminal = { machine ->
                                        openTerminal(machine)
                                    },
                                    onOpenFiles = { machine -> ExplorerActivity.open(context, machineId = machine.id) }
                                )
                            }
                        }
                    }
                    composable(AppDestination.CUSTOMIZATION.route) {
                        CustomizationScreen(
                            profile = appPrefs.userProfile,
                            appearance = appPrefs,
                            customizationEnabled = appPrefs.customizationEnabled,
                            onCustomizationEnabledChange = settingsViewModel::setCustomizationEnabled,
                            onChange = settingsViewModel::updateUserProfile,
                            onDynamicColorChange = settingsViewModel::setUseDynamicColor,
                            onColorSchemeChange = settingsViewModel::setColorSchemeName,
                            onCustomPaletteChange = settingsViewModel::setCustomPalette,
                            onItemTransparencyChange = settingsViewModel::setItemTransparency,
                            onInputAreaTransparencyChange = settingsViewModel::setInputAreaTransparency,
                            onInputAreaBlurChange = settingsViewModel::setInputAreaBlur,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(AppDestination.FEATURES.route) {
                        AppMenuScreen(
                            modifier = Modifier.fillMaxSize(),
                            logoImage = appPrefs.featuresLogoImage,
                            onLogoImageChange = settingsViewModel::setFeaturesLogoImage,
                            onNavigateToCustomization = { openConfiguration(AppDestination.CUSTOMIZATION) },
                            onNavigateToChatOptions = { openConfiguration(AppDestination.CHAT_OPTIONS) },
                            onNavigateToToolsEditor = { openConfiguration(AppDestination.TOOLS_EDITOR) },
                            onNavigateToProviders = { openConfiguration(AppDestination.PROVIDERS) },
                            onNavigateToMcp = { openConfiguration(AppDestination.MCP) },
                            onNavigateToCustomHttpTools = { openConfiguration(AppDestination.CUSTOM_HTTP_TOOLS) },
                            onNavigateToSpeech = { openConfiguration(AppDestination.SPEECH) },
                            onNavigateToRemoteMachines = { openConfiguration(AppDestination.REMOTE_MACHINES) },
                            onNavigateToStorage = { openConfiguration(AppDestination.STORAGE) },
                            onNavigateToAppSettings = { openConfiguration(AppDestination.APP_SETTINGS) },
                            onNavigateToIcons = { openConfiguration(AppDestination.ICONS) },
                            onNavigateToAppData = { openConfiguration(AppDestination.APP_DATA) },
                            onNavigateToAbout = { openConfiguration(AppDestination.ABOUT) },
                            onNavigateToChat = { showChat() }
                        )
                    }

                    composable(AppDestination.APP_SETTINGS.route) {
                        EditorScreenTemplate(
                            title = stringResource(R.string.settings_general_settings),
                            onBack = { navController.popBackStack() }
                        ) { editorPadding ->
                                AppSettings(
                                    scaffoldPadding = editorPadding,
                                    appPreferences = appPrefs,
                                    onThemeChange = { mode ->
                                        settingsViewModel.setThemeMode(mode)
                                    },
                                    onAmoledChange = { use ->
                                        settingsViewModel.setUseAmoled(use)
                                    },
                                    onHideStatusBarChange = { value ->
                                        settingsViewModel.setHideStatusBar(value)
                                    },
                                    onWordWrapModeChange = settingsViewModel::setWordWrapMode,
                                    onWordWrapColumnChange = settingsViewModel::setWordWrapColumn,
                                    onCodePreviewEnabledChange = settingsViewModel::setCodePreviewEnabled,
                                    onCodeBlockDisplayModeChange = settingsViewModel::setCodeBlockDisplayMode,
                                    onCodeBlockMaxLinesChange = settingsViewModel::setCodeBlockMaxLines,
                                    onCodeBlockIncludeUndefinedChange = settingsViewModel::setCodeBlockIncludeUndefined,
                                    onMessageFontSizeChange = settingsViewModel::setMessageFontSize,
                                    onMessageFontSizeCustomChange = settingsViewModel::setMessageFontSizeCustom,
                                    onMessageFontFamilyChange = settingsViewModel::setMessageFontFamily,
                                    onContinueLastConversationChange = { value ->
                                        settingsViewModel.setContinueLastConversation(value)
                                    },
                                    onPersistSelectedModelChange = { value ->
                                        viewModel.setPersistSelectedModel(value)
                                    },
                                    onInstructionSelectionModeChange = settingsViewModel::setInstructionSelectionMode,
                                    onAutoScrollChange = settingsViewModel::setAutoScroll,
                                    onEnableVibrationChange = { value ->
                                        settingsViewModel.setEnableVibration(value)
                                    },
                                    onOpenDeveloperOptions = { openConfiguration(AppDestination.DEVELOPER_OPTIONS) },
                                    onLatexModeChange = { mode ->
                                        settingsViewModel.setLatexMode(mode)
                                    },
                                    onTtsReadCodeBlocksChange = settingsViewModel::setTtsReadCodeBlocks,
                                    onToolRoundLimitEnabledChange = settingsViewModel::setToolRoundLimitEnabled,
                                    onToolRoundLimitChange = settingsViewModel::setToolRoundLimit,
                                    onToolCallStrategyChange = settingsViewModel::setToolCallStrategy,
                                    onToolCallConcurrentLimitChange = settingsViewModel::setToolCallConcurrentLimit,
                                    onMcpAttachmentsChange = settingsViewModel::setMcpAttachments,
                                    onScreenWakelockModeChange = settingsViewModel::setScreenWakelockMode,
                                    onAppLanguageChange = settingsViewModel::setAppLanguage,
                                )
                        }
                    }

                    composable(AppDestination.DEVELOPER_OPTIONS.route) {
                        DeveloperOptionsScreen(
                            preferences = appPrefs,
                            onDebugModeChange = settingsViewModel::setDebugMode,
                            onDebugLogCategoriesChange = settingsViewModel::setDebugLogCategories,
                            onDebugLogLevelChange = settingsViewModel::setDebugLogLevel,
                            onOpenLauncherDragTest = { openConfiguration(AppDestination.LAUNCHER_DRAG_TEST) },
                            onOpenProvidersDemo = { openConfiguration(AppDestination.PROVIDERS_DEMO) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AppDestination.LAUNCHER_DRAG_TEST.route) {
                        LauncherDragTestScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AppDestination.PROVIDERS_DEMO.route) {
                        ProvidersDemoScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AppDestination.ICONS.route) {
                        IconsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AppDestination.APP_DATA.route) {
                        AppDataScreen(
                            conversationCount = conversations.size,
                            providerCount = providers.size,
                            onClearAllConversations = {
                                retainedChats.trimToFraction(0f)
                                viewModel.clearAllConversations(chatAttachments.attachments.value)
                            },
                            onResetAllData = {
                                retainedChats.trimToFraction(0f)
                                viewModel.deleteAllUserData(chatAttachments.attachments.value)
                            },
                            onExportZip = ::exportData,
                            onImportZip = ::importData,
                            onRepairProviders = configurationViewModel::repairCorruptProviders,
                            exportInProgress = preparingExport,
                            importInProgress = importInProgress,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AppDestination.ABOUT.route) {
                        PreviewScreenTemplate(
                            title = stringResource(R.string.settings_category_about),
                            onBack = { navController.popBackStack() },
                            horizontalPadding = 0.dp, topPadding = 0.dp, bottomPadding = 0.dp
                        ) {
                            AboutScreen(scrollable = false)
                        }
                    }

                    // Compatibility with old persisted Menu routes.
                    composable(AppDestination.MENU.route) {
                        LaunchedEffect(Unit) {
                            navController.navigate(AppDestination.FEATURES.route) {
                                popUpTo(AppDestination.MENU.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }
        }
    }
    drawerPortForwardMachine?.let { selected ->
        val machine = remoteMachineState.machines.firstOrNull { it.id == selected.id }
        if (machine == null) {
            LaunchedEffect(selected.id) { drawerPortForwardMachine = null }
        } else {
            val activeForwards by remoteMachinesViewModel.forwardedPorts.collectAsStateWithLifecycle()
            PortForwardDialog(
                machine = machine,
                activeForwards = activeForwards[machine.id].orEmpty(),
                onDismiss = { drawerPortForwardMachine = null },
                onStop = {
                    drawerPortForwardMachine = null
                    remoteMachinesViewModel.stopPortForward(machine.id) { result ->
                        val message = result.fold(
                            onSuccess = { localizedResources.getString(R.string.remote_server_port_forward_stopped) },
                            onFailure = { it.message ?: it.toString() }
                        )
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
                onSaveAndStart = { updated ->
                    drawerPortForwardMachine = null
                    remoteMachinesViewModel.saveMachineAndStartPortForward(updated) { result ->
                        val message = result.fold(
                            onSuccess = { localizedResources.getString(R.string.remote_server_port_forward_started) },
                            onFailure = { it.message ?: it.toString() }
                        )
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }
    BackHandler(enabled = drawerState.isOpen && drawerPortForwardMachine == null) {
        coroutineScope.launch { drawerState.close() }
    }
}
