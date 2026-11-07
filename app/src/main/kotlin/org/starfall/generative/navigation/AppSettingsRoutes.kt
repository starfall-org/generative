package org.starfall.generative.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.starfall.generative.R
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.features.about.ui.AboutScreen
import org.starfall.generative.features.customization.ui.CustomizationScreen
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.icons.ui.IconsScreen
import org.starfall.generative.features.menu.ui.AppMenuScreen
import org.starfall.generative.features.providers.list.ui.ProvidersDemoScreen
import org.starfall.generative.features.settings.logic.SettingsViewModel
import org.starfall.generative.features.settings.ui.AppSettings
import org.starfall.generative.features.settings.ui.DeveloperOptionsScreen
import org.starfall.generative.features.settings.ui.LauncherDragTestScreen
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate
import org.starfall.generative.shared.ui.screens.PreviewScreenTemplate

/** Read root state at use time so retained screens and save callbacks receive the latest values. */
internal fun RetainedNavGraphBuilder.appSettingsRoutes(
    navController: NavHostController,
    viewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
    appPrefs: () -> AppPreferences,
    openConfiguration: (AppDestination) -> Unit,
    showChat: () -> Unit
) {
    composable(AppDestination.CUSTOMIZATION.route) {
        CustomizationScreen(
            profile = appPrefs().userProfile,
            appearance = appPrefs(),
            customizationEnabled = appPrefs().customizationEnabled,
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
            logoImage = appPrefs().featuresLogoImage,
            onLogoImageChange = settingsViewModel::setFeaturesLogoImage,
            onNavigateToCustomization = { openConfiguration(AppDestination.CUSTOMIZATION) },
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
                    appPreferences = appPrefs(),
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
            preferences = appPrefs(),
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
