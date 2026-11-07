package org.starfall.generative.navigation

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.features.appdata.logic.AppDataTransferService
import org.starfall.generative.features.appdata.ui.AppDataScreen
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.features.settings.logic.SettingsViewModel
import org.starfall.generative.features.storage.ui.MediaStorageSheet
import org.starfall.generative.features.storage.ui.StorageScreen
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.home.chat.logic.contextWindowStatus
import org.starfall.generative.features.home.chat.ui.ChatScreen
import org.starfall.generative.features.home.chat.ui.RetainedChatConversations
import org.starfall.generative.features.home.chat.ui.RetainedChatPages
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.shared.ui.theme.modalScrimColor
import org.starfall.generative.features.home.shared.ui.InstructionControls
import org.starfall.generative.features.home.shared.ui.LocalInstructionControls
import org.starfall.generative.features.home.shared.ui.LocalToolControls
import org.starfall.generative.features.home.shared.ui.ToolControls
import org.starfall.generative.features.home.chat.ui.LocalChatAttachmentSelection

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
) {
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
    val mcpEditor = remember { mutableStateOf<Pair<McpInfo, Boolean>?>(null) }
    var pendingNewProvider by remember { mutableStateOf<ProviderEditorPage?>(null) }
    val isGenerating: Boolean by viewModel.isGenerating.collectAsStateWithLifecycle()
    val generatingConversationIds by viewModel.generatingConversationIds.collectAsStateWithLifecycle()
    val generatingConversationId = currentConvId.takeIf { isGenerating }
    val serversidePending by viewModel.serverside.pending.collectAsStateWithLifecycle()
    val serversideFailure by viewModel.serverside.failure.collectAsStateWithLifecycle()
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
    val remoteMachinePage = remember { mutableStateOf<Any?>(null) }
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

    val queuedMessages by viewModel.queuedMessages.collectAsStateWithLifecycle()
    val importedProviderId by configurationViewModel.importedProviderId.collectAsStateWithLifecycle()
    val providerImportFailed by configurationViewModel.providerImportFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val appDataActions = rememberAppDataActions(viewModel, appDataTransfer)
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
    HandleExternalNavigationRequests(
        pendingAppDeepLink, onAppDeepLinkHandled, pendingExternalShare, onExternalShareHandled,
        viewModel, configurationViewModel, toolFiles, retainedChats, drawerState,
        navigate = ::navigate, clearMcpEditor = { mcpEditor.value = null }
    )

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
            machines = remoteMachineState.machines,
            settings = toolSettings,
            providers = providers,
            setSystem = viewModel::setSystemTool,
            setMcp = viewModel::setQuickMcp,
            setMachines = viewModel::setMachinesEnabled,
            setServerside = viewModel.serverside::setEnabled,
            serversidePending = serversidePending,
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
        serversideFailure?.let { failure ->
            org.starfall.generative.shared.ui.dialogs.ErrorDialog(
                title = stringResource(R.string.serverside_cli),
                message = stringResource(
                    if (failure.missing) R.string.serverside_cli_missing else R.string.serverside_handshake_failed,
                    failure.machineName
                ) + "\n\n" + failure.detail,
                onDismissRequest = viewModel.serverside::dismissError,
                severity = if (failure.missing) org.starfall.generative.shared.ui.AlertSeverity.WARNING
                    else org.starfall.generative.shared.ui.AlertSeverity.ERROR
            )
        }
        if (showMediaSheet) MediaStorageSheet(toolFiles) { showMediaSheet = false }
        val drawerScrim = modalScrimColor(DrawerDefaults.scrimColor)
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = chatOnTop,
            scrimColor = drawerScrim,
            drawerContent = {
                NavigationDrawerContent(
                    viewModel = viewModel, settingsViewModel = settingsViewModel,
                    remoteMachinesViewModel = remoteMachinesViewModel, drawerState = drawerState,
                    coroutineScope = coroutineScope, conversations = conversations, providers = providers,
                    currentConvId = currentConvId, generatingConversationIds = generatingConversationIds,
                    appPrefs = appPrefs, chatAttachments = chatAttachments, retainedChats = retainedChats,
                    navigate = ::navigate, startNewChat = ::startNewChat,
                    openConfiguration = ::openConfiguration, openTerminal = ::openTerminal,
                    onOpenMediaStorage = { showMediaSheet = true },
                    onPortForward = { drawerPortForwardMachine = it }
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
                    remoteMachineRoutes(navController, remoteMachinesViewModel,
                        remoteMachineState = { remoteMachineState }, remoteMachinePage = remoteMachinePage,
                        openTerminal = ::openTerminal)

                    providerEditorRoutes(
                        navController = navController,
                        configurationViewModel = configurationViewModel,
                        toolSettings = toolSettings,
                        pendingNewProvider = { pendingNewProvider },
                        onPendingNewProviderChange = { pendingNewProvider = it },
                        onShowChat = ::showChat
                    )

                    configurationRoutes(navController, viewModel, configurationViewModel, settingsViewModel,
                        appPrefs = { appPrefs }, providers = { providers }, providerGroups = { providerGroups },
                        mcpServers = { mcpServers }, speechServices = { speechServices }, toolSettings = { toolSettings },
                        mcpToolsCache = { mcpToolsCache }, mcpToolErrors = { mcpToolErrors },
                        mcpToolsLoading = { mcpToolsLoading }, activeTestSpeechServiceId = { activeTestSpeechServiceId },
                        mcpEditor = mcpEditor, onNewProvider = { pendingNewProvider = ProviderEditorPage(it, true) })

                    featureToolRoutes(navController, viewModel, configurationViewModel, settingsViewModel,
                        appPrefs = { appPrefs }, providers = { providers }, providerGroups = { providerGroups },
                        speechServices = { speechServices }, mcpServers = { mcpServers }, toolSettings = { toolSettings })

                    composable(AppDestination.STORAGE.route) {
                        StorageScreen(
                            store = toolFiles,
                            onBack = { navController.popBackStack() },
                            onBrowseFiles = { ExplorerActivity.open(context) }
                        )
                    }
                    appSettingsRoutes(navController, viewModel, settingsViewModel,
                        appPrefs = { appPrefs }, openConfiguration = ::openConfiguration, showChat = ::showChat)

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
                            onExportZip = appDataActions.exportData,
                            onImportZip = appDataActions.importData,
                            onRepairProviders = configurationViewModel::repairCorruptProviders,
                            exportInProgress = appDataActions.exportInProgress,
                            importInProgress = appDataActions.importInProgress,
                            onBack = { navController.popBackStack() }
                        )
                    }

                }
            }
        }
    }
    NavigationPortForwardDialog(
        selectedMachine = drawerPortForwardMachine,
        remoteMachineState = remoteMachineState,
        remoteMachinesViewModel = remoteMachinesViewModel,
        onDismiss = { drawerPortForwardMachine = null }
    )
    BackHandler(enabled = drawerState.isOpen && drawerPortForwardMachine == null) {
        coroutineScope.launch { drawerState.close() }
    }
}
