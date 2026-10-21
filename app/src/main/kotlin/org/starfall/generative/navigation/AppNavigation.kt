package org.starfall.generative.navigation

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.features.about.ui.AboutScreen
import org.starfall.generative.features.appdata.logic.buildJsonExport
import org.starfall.generative.features.appdata.logic.parseFullAppDataExport
import org.starfall.generative.shared.model.JsonExportKind
import org.starfall.generative.features.appdata.ui.AppDataScreen
import org.starfall.generative.features.chatoptions.ui.ChatOptionsScreen
import org.starfall.generative.features.icons.ui.IconsScreen
import org.starfall.generative.features.machines.details.ui.MachineDetailsScreen
import org.starfall.generative.features.machines.details.ui.RemoteMachineSettings
import org.starfall.generative.features.machines.details.ui.RemoteTerminalScreen
import org.starfall.generative.features.machines.list.ui.RemoteMachinesScreen
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.features.main.ui.FeaturesScreen
import org.starfall.generative.features.mcp.editor.ui.ServerEditorScreen
import org.starfall.generative.features.mcp.list.ui.ServerListScreen
import org.starfall.generative.features.providers.editor.ui.ProviderEditorScreen
import org.starfall.generative.features.providers.list.ui.ProvidersScreen
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.features.settings.logic.SettingsViewModel
import org.starfall.generative.features.settings.ui.AppSettingsScreen
import org.starfall.generative.features.settings.ui.MainAppbar
import org.starfall.generative.features.speech.ui.SpeechServicesScreen
import org.starfall.generative.features.storage.ui.StorageScreen
import org.starfall.generative.features.tools.ui.ToolsEditorScreen
import org.starfall.generative.home.chat.logic.ChatViewModel
import org.starfall.generative.home.chat.logic.contextWindowStatus
import org.starfall.generative.home.chat.ui.ChatScreen
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.home.drawer.ui.LeftSideDrawer
import org.starfall.generative.home.drawer.ui.recordDrawerBackdrop
import org.starfall.generative.home.drawer.ui.rememberDrawerBackdrop
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.storage.preferences.AppPreferences
import org.starfall.generative.shared.theme.modalScrimColor
import org.starfall.generative.shared.ui.LocalToolControls
import org.starfall.generative.shared.ui.ToolControls
import org.starfall.generative.shared.ui.layout.SlideScreenContent
import org.starfall.generative.shared.ui.media.ChatAttachmentSelection
import org.starfall.generative.shared.ui.media.LocalChatAttachmentSelection

/** Stable route names are persisted by Navigation across Activity/process recreation. */
private data class MachineEditorPage(val machine: RemoteMachine, val isNew: Boolean)
private data class ProviderEditorPage(val provider: ModelProvider, val isNew: Boolean)
private enum class RemoteMachineSettingsPage { SETTINGS }

enum class AppDestination(val route: String) {
    CHAT("chat"),
    PROVIDERS("providers"),
    MCP("mcp"),
    SPEECH("speech"),
    FEATURES("features"),
    CHAT_OPTIONS("chat_options"),
    TOOLS_EDITOR("system_tools"),
    STORAGE("storage"),
    REMOTE_MACHINES("remote_machines"),
    MENU("menu"),
    APP_SETTINGS("AppSettings"),
    ICONS("Icons"),
    APP_DATA("AppData"),
    ABOUT("About"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    viewModel: ChatViewModel,
    configurationViewModel: ConfigurationViewModel,
    settingsViewModel: SettingsViewModel,
    remoteMachinesViewModel: RemoteMachinesViewModel,
    toolFiles: ToolFiles,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerFocusManager = LocalFocusManager.current
    // Opening the sidebar (button or swipe) takes focus away from the chat input.
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) drawerFocusManager.clearFocus(force = true)
    }
    val coroutineScope = rememberCoroutineScope()

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val chatOnTop = navBackStackEntry?.destination?.route == AppDestination.CHAT.route
    fun navigate(destination: AppDestination) {
        navController.navigate(destination.route) {
            popUpTo(AppDestination.CHAT.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun startNewChat() {
        viewModel.startNewChat()
        navigate(AppDestination.CHAT)
    }

    fun openConfiguration(destination: AppDestination) {
        navController.navigate(destination.route) { launchSingleTop = true }
    }

    val conversations: List<ConversationListItem> by viewModel.conversations.collectAsStateWithLifecycle()
    val currentConv: Conversation? by viewModel.currentConversation.collectAsStateWithLifecycle()
    val chatAttachments = remember { ChatAttachmentSelection() }
    var mcpEditor by remember { mutableStateOf<Pair<McpInfo, Boolean>?>(null) }
    var pendingNewProvider by remember { mutableStateOf<ProviderEditorPage?>(null) }
    LaunchedEffect(currentConv?.id) { chatAttachments.clear() }
    val isGenerating: Boolean by viewModel.isGenerating.collectAsStateWithLifecycle()
    val generatingConversationIds by viewModel.generatingConversationIds.collectAsStateWithLifecycle()
    val generatingConversationId = currentConv?.id.takeIf { isGenerating }
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
    val remoteMachineBinaries by remoteMachinesViewModel.binaries.collectAsStateWithLifecycle()
    var remoteMachinePage by remember { mutableStateOf<Any?>(null) }

    val toolSettings by viewModel.toolSettings.collectAsStateWithLifecycle()
    val queuedMessages by viewModel.queuedMessages.collectAsStateWithLifecycle()
    val importedProviderId by configurationViewModel.importedProviderId.collectAsStateWithLifecycle()
    val providerImportError by configurationViewModel.providerImportError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var preparingExport by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        OutputStreamWriter(output, Charsets.UTF_8).use { it.write(payload) }
                    } ?: error("Could not open the selected file.")
                }
                Toast.makeText(context, "JSON file exported", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "Export failed: could not write the selected file. Choose a writable location and retry.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun exportJson() {
        if (preparingExport || pendingExport != null) return
        preparingExport = true
        coroutineScope.launch {
            try {
                val fullProviders = configurationViewModel.loadProvidersForExport()
                val fullServers = configurationViewModel.loadServersForExport()
                val fullConversations = withContext(Dispatchers.IO) {
                    viewModel.conversationsForExport()
                }
                val payload = withContext(Dispatchers.Default) {
                    buildJsonExport(fullProviders, fullServers, speechServices, fullConversations, providerGroups)
                }
                pendingExport = payload
                exportLauncher.launch(JsonExportKind.FULL_BACKUP.fileName)
            } catch (cancelled: CancellationException) {
                pendingExport = null
                throw cancelled
            } catch (_: Exception) {
                pendingExport = null
                Toast.makeText(context, "Export failed: could not prepare a complete backup. Check provider/MCP credentials and try again.", Toast.LENGTH_LONG).show()
            } finally {
                preparingExport = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val shouldImport = pendingImport
        pendingImport = false
        if (uri == null || !shouldImport) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("Could not open the selected file.")
                }
                val fileName = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                }
                val backup = withContext(Dispatchers.Default) { parseFullAppDataExport(raw, fileName) }
                withContext(Dispatchers.IO) {
                    // Imports are additive/upserts: categories absent from legacy exports are untouched.
                    backup.providers?.let { configurationViewModel.importProviders(it, backup.providerGroups) }
                    backup.mcpServers?.let { configurationViewModel.importMcpServers(it) }
                    backup.speechServices?.let { configurationViewModel.importSpeechServices(it) }
                    backup.conversations?.let { viewModel.importConversations(it) }
                }
                val count = backup.providers.orEmpty().size + backup.providerGroups.orEmpty().size +
                    backup.mcpServers.orEmpty().size + backup.speechServices.orEmpty().size +
                    backup.conversations.orEmpty().size
                Toast.makeText(context, "$count item(s) imported", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Import failed: ${error.message.orEmpty()}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun importJson() {
        pendingImport = true
        importLauncher.launch(arrayOf("application/json", "text/*"))
    }
    // The app preference is the default for new chats. Existing chats keep showing
    // the model they last used, independently of that default.
    val conversationModel =
        currentConv?.takeIf { appPrefs.persistSelectedModel && it.modelId.isNotBlank() }?.let { conversation ->
            providers.firstOrNull { provider ->
                provider.id == conversation.providerId &&
                        provider.config.modelIds?.contains(conversation.modelId) != false &&
                        (provider.config.modelConfigs[conversation.modelId]?.modelType
                            ?: ModelType.TEXT_GENERATION) == ModelType.TEXT_GENERATION
            }?.let { conversation }
        }
    val chatProviderId = conversationModel?.providerId ?: appPrefs.selectedProviderId
    val chatModelId = conversationModel?.modelId ?: appPrefs.selectedModelId
    LaunchedEffect(Unit) {
        org.starfall.generative.shared.storage.LocalWriteErrors.errors.collect {
            Toast.makeText(context, "Could not save the change. Please try again.", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(importedProviderId) {
        importedProviderId?.let { id ->
            drawerState.close()
            navController.navigate("providers/edit/$id") { launchSingleTop = true }
            configurationViewModel.providerImportOpened(id)
        }
    }
    LaunchedEffect(providerImportError) {
        providerImportError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            configurationViewModel.clearProviderImportError()
        }
    }
    LaunchedEffect(chatOnTop) {
        if (!chatOnTop) drawerState.close()
    }


    CompositionLocalProvider(
        org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences provides appPrefs,
        LocalChatAttachmentSelection provides chatAttachments,
        LocalToolControls provides ToolControls(
            servers = mcpServers,
            machines = remoteMachineState.machines,
            settings = toolSettings,
            providers = providers,
            setSystem = viewModel::setSystemTool,
            setMcp = viewModel::setQuickMcp,
            setMachines = viewModel::setMachinesEnabled,
            setMachine = { machineId, enabled ->
                viewModel.setMachineEnabled(machineId, enabled, remoteMachineState.machines.map { it.id })
            },
            setMachineTool = viewModel::setMachineToolEnabled,
            setSendFile = viewModel::setSendFileEnabled
        )
    ) {
        val drawerScrim = modalScrimColor(DrawerDefaults.scrimColor)
        val drawerBackdrop = rememberDrawerBackdrop(drawerState, drawerScrim)
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = chatOnTop,
            scrimColor = drawerScrim,
            drawerContent = {
                LeftSideDrawer(
                    backdrop = drawerBackdrop,
                    conversations = conversations,
                    providers = providers,
                    currentConversationId = currentConv?.id,
                    isOpen = drawerState.isOpen,
                    generatingConversationIds = generatingConversationIds,
                    organization = appPrefs.sidebar,
                    onUpdateOrganization = settingsViewModel::updateSidebar,
                    onDeleteConversations = { ids ->
                        viewModel.deleteConversations(ids, chatAttachments.attachments.value)
                    },
                    defaultSystemPrompt = appPrefs.defaultSystemPrompt,
                    promptLibrary = appPrefs.promptLibrary,
                    onPromptLibraryChange = viewModel::setPromptLibrary,
                    onSelectConversation = {
                        viewModel.selectConversation(it.id)
                        navigate(AppDestination.CHAT)
                    },
                    onNewChat = {
                        startNewChat()
                    },
                    onRenameConversation = { id, newTitle ->
                        viewModel.renameConversation(id, newTitle)
                    },
                    onDeleteConversation = {
                        viewModel.deleteConversations(setOf(it), chatAttachments.attachments.value)
                    },
                    onUpdateDefaultSystemPrompt = { prompt ->
                        viewModel.setDefaultSystemPrompt(prompt)
                    },
                    onNavigateToSettings = {
                        navigate(AppDestination.FEATURES)
                    },
                    featuresLogoImage = appPrefs.featuresLogoImage,
                    onCloseDrawer = {
                        coroutineScope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            // The chat screen stays composed whatever page is open: NavHost disposes a destination's
            // composition when another page is on top, which used to wipe the scroll position, the typed
            // draft, open sheets and every other piece of chat UI state. Other pages draw over it.
            val focusManager = LocalFocusManager.current
            LaunchedEffect(chatOnTop) {
                if (!chatOnTop) focusManager.clearFocus(force = true)
            }
            Box(modifier = Modifier.fillMaxSize().recordDrawerBackdrop(drawerBackdrop)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (chatOnTop) Modifier else Modifier.pointerInput(Unit) {
                                // Hidden underneath another page: swallow touches so nothing reaches it.
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            }
                        )
                ) {
                    ChatScreen(
                        conversation = currentConv,
                        isGenerating = isGenerating,
                        generatingConversationId = generatingConversationId,
                        chatError = chatError,
                        providers = providers,
                        providerGroups = providerGroups,
                        modelPickerCollapsedGroups = appPrefs.modelPickerCollapsedGroups,
                        modelPickerCollapsedProviders = appPrefs.modelPickerCollapsedProviders,
                        onModelPickerCollapsedGroupsChange = settingsViewModel::setModelPickerCollapsedGroups,
                        onModelPickerCollapsedProvidersChange = settingsViewModel::setModelPickerCollapsedProviders,
                        modelPickerSelectedTab = appPrefs.modelPickerSelectedTab,
                        onModelPickerSelectedTabChange = settingsViewModel::setModelPickerSelectedTab,
                        selectedProviderId = chatProviderId,
                         selectedModelName = chatModelId,
                         featuresLogoImage = appPrefs.featuresLogoImage,
                        autoScroll = appPrefs.autoScroll,
                        onSendMedia = viewModel::sendMedia,
                        onResendUserMessage = viewModel::resendUserMessage,
                        onSendMessage = { text, files ->
                            viewModel.sendMessage(text, files)
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
                        onDeleteMessageVersion = { id ->
                            viewModel.deleteMessageVersion(id)
                        },
                        queuedMessages = queuedMessages,
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
                        summaryProgress = summaryProgress,
                        contextWindowStatus = contextWindowStatus,
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
                                }"
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onEditProvider = { providerId ->
                            navController.navigate("providers/edit/${Uri.encode(providerId)}") {
                                launchSingleTop = true
                            }
                        },
                        onStartConversationSummary = viewModel::startConversationSummary,
                        onCancelConversationSummary = viewModel::cancelConversationSummary,
                        onSummaryRoleChange = viewModel::setSummaryRole,
                        onDeleteSummary = viewModel::deleteConversationSummary,
                        speakingMessageId = speakingMessageId,
                        onReadMessage = { messageId, text ->
                            viewModel.speakText(text, messageId)
                        },
                        onFetchOllamaModels = { url ->
                            configurationViewModel.fetchOllamaModels(url)
                        }
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = AppDestination.CHAT.route,
                    enterTransition = {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300))
                    },
                    exitTransition = {
                        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300))
                    },
                    popEnterTransition = {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300))
                    },
                    popExitTransition = {
                        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300))
                    }
                ) {
                    composable(AppDestination.CHAT.route) {
                        // The chat lives outside the NavHost (see above) so it is never disposed.
                        Box(Modifier.fillMaxSize())
                    }

                    composable("providers/new") {
                        val page = pendingNewProvider
                        if (page == null) {
                            LaunchedEffect(Unit) { navController.popBackStack() }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            // Clear the pending page only once this screen has left composition. Clearing it
                            // inside onDismiss made the exiting screen recompose into the `page == null`
                            // branch above, whose popBackStack() ran a second time and also popped Providers.
                            DisposableEffect(Unit) { onDispose { pendingNewProvider = null } }
                            key(page.provider.id) {
                                ProviderEditorScreen(
                                    initialProvider = page.provider,
                                    isNew = page.isNew,
                                    onSave = { saved ->
                                        configurationViewModel.saveProvider(saved)
                                        pendingNewProvider = ProviderEditorPage(saved, false)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.provider_saved),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onDismiss = { navController.popBackStack() },
                                    onSaveModels = configurationViewModel::saveProviderModels,
                                    onReorderModels = configurationViewModel::reorderProviderModels,
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
                        route = "providers/edit/{providerId}?modelId={modelId}",
                        arguments = listOf(navArgument("modelId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        })
                    ) { entry ->
                        val providerId = entry.arguments?.getString("providerId").orEmpty()
                        val modelId = entry.arguments?.getString("modelId")
                        TargetedEditorContent(
                            recordId = providerId,
                            title = "Provider editor",
                            loadingMessage = "Loading provider and saved credentials…",
                            onBack = { navController.popBackStack() },
                            load = { configurationViewModel.loadProviderForEditor(providerId) }
                        ) { provider ->
                            key(provider.id) {
                                ProviderEditorScreen(
                                    initialProvider = provider,
                                    isNew = false,
                                    initialEditingModelId = modelId,
                                    onSave = { saved ->
                                        configurationViewModel.saveProvider(saved)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.provider_saved),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onDismiss = { navController.popBackStack() },
                                    onSaveModels = configurationViewModel::saveProviderModels,
                                    onReorderModels = configurationViewModel::reorderProviderModels,
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
                            onDeleteGroup = { groupId ->
                                configurationViewModel.deleteProviderGroup(groupId)
                                settingsViewModel.setProvidersCollapsedSections(appPrefs.providersCollapsedSections - groupId)
                                settingsViewModel.setModelPickerCollapsedGroups(appPrefs.modelPickerCollapsedGroups - groupId)
                            },
                            onMoveProviderToGroup = configurationViewModel::moveProviderToGroup,
                            onPlaceProviderImmediately = configurationViewModel::queueProviderPlacement,
                            onDeleteProvider = { providerId ->
                                configurationViewModel.deleteProvider(providerId)
                                settingsViewModel.setModelPickerCollapsedProviders(appPrefs.modelPickerCollapsedProviders - providerId)
                            },
                            onReorderProviders = configurationViewModel::reorderProviders,
                            onReorderRootItems = configurationViewModel::reorderProviderRootItems,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AppDestination.MCP.route) {
                        SlideScreenContent(editor = mcpEditor, label = stringResource(R.string.mcp_editor)) { page ->
                            if (page != null) {
                                TargetedEditorContent(
                                    recordId = page.first.id,
                                    title = stringResource(R.string.configure_mcp_server_title),
                                    loadingMessage = "Loading MCP server and saved credentials…",
                                    onBack = { mcpEditor = null },
                                    load = { configurationViewModel.loadMcpServerForEditor(page.first.id) },
                                    draft = page.first.takeIf { page.second }
                                ) { server ->
                                    key(server.id) {
                                        ServerEditorScreen(
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
                                            onSave = { configurationViewModel.saveMcpServer(it) }
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
                            onSelectService = viewModel::selectSpeechService,
                            onSaveService = { configurationViewModel.saveSpeechService(it) },
                            onDeleteService = { configurationViewModel.deleteSpeechService(it) },
                            onReorderServices = configurationViewModel::reorderSpeechServices,
                            onTestVoice = viewModel::testVoice,
                            onBack = { navController.popBackStack() },
                            activeTestServiceId = activeTestSpeechServiceId,
                            onStopPlayback = viewModel::stopSpeaking
                        )
                    }

                    composable(AppDestination.CHAT_OPTIONS.route) {
                        ChatOptionsScreen(
                            providers = providers,
                            settings = toolSettings,
                            providerGroups = providerGroups,
                            collapsedGroupIds = appPrefs.modelPickerCollapsedGroups,
                            collapsedProviderIds = appPrefs.modelPickerCollapsedProviders,
                            onCollapsedGroupIdsChange = settingsViewModel::setModelPickerCollapsedGroups,
                            onCollapsedProviderIdsChange = settingsViewModel::setModelPickerCollapsedProviders,
                            onSave = viewModel::setSystemTool,
                            selectedTabKey = appPrefs.modelPickerSelectedTab,
                            onSelectedTabKeyChange = settingsViewModel::setModelPickerSelectedTab,
                            onEditProvider = { providerId ->
                                navController.navigate("providers/edit/${Uri.encode(providerId)}") {
                                    launchSingleTop = true
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(AppDestination.TOOLS_EDITOR.route) {
                        ToolsEditorScreen(
                            providers = providers,
                            settings = toolSettings,
                            onSave = viewModel::setSystemTool,
                            onSetSendFile = viewModel::setSendFileEnabled,
                            providerGroups = providerGroups,
                            collapsedGroupIds = appPrefs.modelPickerCollapsedGroups,
                            collapsedProviderIds = appPrefs.modelPickerCollapsedProviders,
                            onCollapsedGroupIdsChange = settingsViewModel::setModelPickerCollapsedGroups,
                            onCollapsedProviderIdsChange = settingsViewModel::setModelPickerCollapsedProviders,
                            onSaveModelConfiguration = configurationViewModel::saveModelConfiguration,
                            selectedTabKey = appPrefs.modelPickerSelectedTab,
                            onSelectedTabKeyChange = settingsViewModel::setModelPickerSelectedTab,
                            onEditProvider = { providerId ->
                                navController.navigate("providers/edit/${Uri.encode(providerId)}") {
                                    launchSingleTop = true
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(AppDestination.STORAGE.route) { StorageScreen(toolFiles) { navController.popBackStack() } }
                    composable(AppDestination.REMOTE_MACHINES.route) {
                        SlideScreenContent(
                            editor = remoteMachinePage,
                            label = stringResource(R.string.remote_machines_title)
                        ) { selected ->
                            when {
                                selected is RemoteMachine -> RemoteTerminalScreen(
                                    machine = selected,
                                    viewModel = remoteMachinesViewModel,
                                    onBack = { remoteMachinePage = null }
                                )

                                selected is MachineEditorPage -> MachineDetailsScreen(
                                    initial = selected.machine,
                                    isNew = selected.isNew,
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
                                    binaries = remoteMachineBinaries,
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
                                    onOpenTerminal = { machine -> remoteMachinePage = machine }
                                )
                            }
                        }
                    }
                    composable(AppDestination.FEATURES.route) {
                        Scaffold { padding ->
                            FeaturesScreen(
                                modifier = Modifier.padding(padding),
                                logoImage = appPrefs.featuresLogoImage,
                                onLogoImageChange = settingsViewModel::setFeaturesLogoImage,
                                onBack = { navController.popBackStack() },
                                onNavigateToChatOptions = { openConfiguration(AppDestination.CHAT_OPTIONS) },
                                onNavigateToToolsEditor = { openConfiguration(AppDestination.TOOLS_EDITOR) },
                                onNavigateToProviders = { openConfiguration(AppDestination.PROVIDERS) },
                                onNavigateToMcp = { openConfiguration(AppDestination.MCP) },
                                onNavigateToSpeech = { openConfiguration(AppDestination.SPEECH) },
                                onNavigateToRemoteMachines = { openConfiguration(AppDestination.REMOTE_MACHINES) },
                                onNavigateToStorage = { openConfiguration(AppDestination.STORAGE) },
                                onNavigateToAppSettings = { openConfiguration(AppDestination.APP_SETTINGS) },
                                onNavigateToIcons = { openConfiguration(AppDestination.ICONS) },
                                onNavigateToAppData = { openConfiguration(AppDestination.APP_DATA) },
                                onNavigateToAbout = { openConfiguration(AppDestination.ABOUT) }
                            )
                        }
                    }

                    composable(AppDestination.APP_SETTINGS.route) {
                        Scaffold(
                            topBar = {
                                MainAppbar(
                                    title = stringResource(R.string.settings_category_app_settings),
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        ) { padding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                AppSettingsScreen(
                                    appPreferences = appPrefs,
                                    onThemeChange = { mode ->
                                        settingsViewModel.setThemeMode(mode)
                                    },
                                    onAmoledChange = { use ->
                                        settingsViewModel.setUseAmoled(use)
                                    },
                                    onDynamicColorChange = { use ->
                                        settingsViewModel.setUseDynamicColor(use)
                                    },
                                    onColorSchemeChange = { scheme ->
                                        settingsViewModel.setColorSchemeName(scheme)
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
                                    onAutoScrollChange = settingsViewModel::setAutoScroll,
                                    onEnableVibrationChange = { value ->
                                        settingsViewModel.setEnableVibration(value)
                                    },
                                    onDebugModeChange = { value ->
                                        settingsViewModel.setDebugMode(value)
                                    },
                                    onDebugLogCategoriesChange = settingsViewModel::setDebugLogCategories,
                                    onLatexModeChange = { mode ->
                                        settingsViewModel.setLatexMode(mode)
                                    },
                                    onTtsReadCodeBlocksChange = settingsViewModel::setTtsReadCodeBlocks,
                                    onToolRoundLimitEnabledChange = settingsViewModel::setToolRoundLimitEnabled,
                                    onToolRoundLimitChange = settingsViewModel::setToolRoundLimit,
                                    onToolCallStrategyChange = settingsViewModel::setToolCallStrategy,
                                    onToolCallConcurrentLimitChange = settingsViewModel::setToolCallConcurrentLimit,
                                    onScreenWakelockModeChange = settingsViewModel::setScreenWakelockMode,
                                    onAppLanguageChange = settingsViewModel::setAppLanguage,
                                    onAutoSummaryEnabledChange = settingsViewModel::setAutoSummaryEnabled,
                                    onSummarySuggestionEnabledChange = settingsViewModel::setSummarySuggestionEnabled,
                                    onAutoSummaryPercentChange = settingsViewModel::setAutoSummaryPercent
                                )
                            }
                        }
                    }

                    composable(AppDestination.ICONS.route) {
                        Scaffold(
                            topBar = {
                                MainAppbar(
                                    title = stringResource(R.string.icon_settings_title),
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        ) { padding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                IconsScreen()
                            }
                        }
                    }

                    composable(AppDestination.APP_DATA.route) {
                        AppDataScreen(
                            conversationCount = conversations.size,
                            providerCount = providers.size,
                            onClearAllConversations = {
                                viewModel.clearAllConversations(chatAttachments.attachments.value)
                            },
                            onResetAllData = {
                                viewModel.deleteAllUserData(chatAttachments.attachments.value)
                            },
                            onExportJson = ::exportJson,
                            onImportJson = ::importJson,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AppDestination.ABOUT.route) {
                        Scaffold(
                            topBar = {
                                MainAppbar(
                                    title = stringResource(R.string.settings_category_about),
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        ) { padding ->
                            AboutScreen(
                                modifier = Modifier
                                    .padding(padding)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // Restore old saved menu routes into the unified settings destination.
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
    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }
}
