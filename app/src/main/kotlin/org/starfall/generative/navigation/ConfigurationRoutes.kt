package org.starfall.generative.navigation

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import org.starfall.generative.R
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.mcp.editor.ui.ServerEditor
import org.starfall.generative.features.mcp.list.ui.ServerListScreen
import org.starfall.generative.features.providers.list.ui.ProvidersScreen
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.features.settings.logic.SettingsViewModel
import org.starfall.generative.features.speech.ui.SpeechServicesScreen
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.screens.RetainedScreenContent

/** Read root state at use time so retained screens and save callbacks receive the latest values. */
internal fun RetainedNavGraphBuilder.configurationRoutes(
    navController: NavHostController,
    viewModel: ChatViewModel,
    configurationViewModel: ConfigurationViewModel,
    settingsViewModel: SettingsViewModel,
    appPrefs: () -> AppPreferences,
    providers: () -> List<ModelProvider>,
    providerGroups: () -> List<ProviderGroup>,
    mcpServers: () -> List<McpInfo>,
    speechServices: () -> List<SpeechService>,
    toolSettings: () -> ToolSettings,
    mcpToolsCache: () -> Map<String, List<ToolDefinition>>,
    mcpToolErrors: () -> Map<String, String>,
    mcpToolsLoading: () -> Set<String>,
    activeTestSpeechServiceId: () -> String?,
    mcpEditor: MutableState<Pair<McpInfo, Boolean>?>,
    onNewProvider: (ModelProvider) -> Unit
) {
    composable(AppDestination.PROVIDERS.route) {
        ProvidersScreen(
            providers = providers(),
            providerGroups = providerGroups(),
            collapsedSectionsState = appPrefs().providersCollapsedSections,
            onCollapsedSectionsChange = settingsViewModel::setProvidersCollapsedSections,
            isGridView = appPrefs().showProvidersAsGrid,
            onToggleGridView = { configurationViewModel.setShowProvidersAsGrid(it) },
            onOpenProviderEditor = { provider, isNew ->
                if (isNew) {
                    onNewProvider(provider)
                    navController.navigate("providers/new")
                } else {
                    navController.navigate("providers/edit/${Uri.encode(provider.id)}")
                }
            },
            onSaveGroup = configurationViewModel::saveProviderGroup,
            onSaveGroupIcon = configurationViewModel::saveProviderGroupIcon,
            onDeleteGroup = { groupId ->
                configurationViewModel.deleteProviderGroup(groupId)
                settingsViewModel.setProvidersCollapsedSections(appPrefs().providersCollapsedSections - groupId)
                settingsViewModel.setModelPickerCollapsedGroups(appPrefs().modelPickerCollapsedGroups - groupId)
            },
            onMoveProviderToGroup = configurationViewModel::moveProviderToGroup,
            onPlaceProviderImmediately = configurationViewModel::queueProviderPlacement,
            onPlaceProviderTracked = configurationViewModel::queueProviderPlacementTracked,
            onReorderRootItemsTracked = configurationViewModel::reorderProviderRootItemsTracked,
            onDeleteProvider = { providerId ->
                configurationViewModel.deleteProvider(providerId)
                settingsViewModel.setModelPickerCollapsedProviders(appPrefs().modelPickerCollapsedProviders - providerId)
            },
            onSetProviderHidden = configurationViewModel::setProviderHidden,
            onReorderProviders = configurationViewModel::reorderProviders,
            onReorderRootItems = configurationViewModel::reorderProviderRootItems,
            onBack = { navController.popBackStack() }
        )
    }

    composable(AppDestination.MCP.route) {
        DisposableEffect(Unit) { onDispose { mcpEditor.value = null } }
        RetainedScreenContent(editor = mcpEditor.value) { page ->
            if (page != null) {
                TargetedEditorContent(
                    recordId = page.first.id,
                    title = stringResource(R.string.mcp_editor),
                    loadingMessage = stringResource(R.string.loading_mcp_server_editor),
                    onBack = { mcpEditor.value = null },
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
                            cachedTools = mcpToolsCache()[server.id] ?: server.cachedTools,
                            cachedError = mcpToolErrors()[server.id],
                            cachedLoading = server.id in mcpToolsLoading(),
                            toolSettings = toolSettings(),
                            onSetToolEnabled = viewModel::setMcpToolEnabled,
                            onSetToolParameter = viewModel::setMcpToolParameter,
                            onDismiss = { mcpEditor.value = null },
                            onSave = { configurationViewModel.saveMcpServer(it) },
                            onSaveIcon = configurationViewModel::saveMcpServerIcon
                        )
                    }
                }
            } else {
                ServerListScreen(
                    mcpServers = mcpServers(),
                    isGridView = appPrefs().showMcpAsGrid,
                    onToggleGridView = { configurationViewModel.setShowMcpAsGrid(it) },
                    toolsCache = mcpToolsCache(),
                    toolErrors = mcpToolErrors(),
                    toolsLoading = mcpToolsLoading(),
                    toolSettings = toolSettings(),
                    onSetToolEnabled = viewModel::setMcpToolEnabled,
                    onSetMcpEnabled = viewModel::setQuickMcp,
                    onDeleteMcpServer = { configurationViewModel.deleteMcpServer(it) },
                    onReorderMcpServers = configurationViewModel::reorderMcpServers,
                    onOpenEditor = { server, isNew -> mcpEditor.value = server to isNew },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    composable(AppDestination.SPEECH.route) {
        SpeechServicesScreen(
            speechServices = speechServices(),
            isGridView = appPrefs().showSpeechAsGrid,
            onToggleGridView = { configurationViewModel.setShowSpeechAsGrid(it) },
            providers = providers(),
            selectedSpeechServiceId = appPrefs().selectedSpeechServiceId,
            onSetTtsProvider = viewModel::selectSpeechService,
            onSaveService = { configurationViewModel.saveSpeechService(it) },
            onSaveServiceIcon = configurationViewModel::saveSpeechServiceIcon,
            onDeleteService = { configurationViewModel.deleteSpeechService(it) },
            onReorderServices = configurationViewModel::reorderSpeechServices,
            onTestVoice = viewModel::testVoice,
            onBack = { navController.popBackStack() },
            activeTestServiceId = activeTestSpeechServiceId(),
            onStopPlayback = viewModel::stopSpeaking
        )
    }
}
