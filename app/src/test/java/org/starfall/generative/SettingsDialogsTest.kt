package org.starfall.generative

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.settings.ui.McpAttachmentsPreferences
import org.starfall.generative.features.settings.ui.PreferencesSettingsView
import org.starfall.generative.features.settings.ui.DeveloperOptionsScreen
import org.starfall.generative.features.settings.ui.SettingsChoice
import org.starfall.generative.features.settings.ui.SettingsChoicePreference
import org.starfall.generative.features.settings.ui.SettingsNumberPreference
import org.starfall.generative.shared.model.preferences.McpAttachmentSettings
import org.starfall.generative.shared.model.preferences.AppPreferences
import org.starfall.generative.shared.model.preferences.InstructionSelectionMode
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class SettingsDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choiceDialogHasNoFooterAndSelectsImmediately() {
        var value by mutableStateOf("one")
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                SettingsChoicePreference("Choices", value, listOf(
                    SettingsChoice("one", "First"), SettingsChoice("two", "Second")
                ), onSelected = { value = it })
            }
        }
        compose.onNodeWithText("Choices").performClick()
        compose.onNodeWithTag("app-dialog-content").assertExists()
        compose.onNodeWithTag("app-dialog-buttons").assertDoesNotExist()
        compose.onNodeWithText("Second").performClick()
        compose.runOnIdle { assertEquals("two", value) }
        compose.onNodeWithTag("app-dialog").assertDoesNotExist()
    }

    @Test fun numberDialogHasCancelAndSaveAndDoesNotCommitDraftBeforeSave() {
        var value by mutableStateOf(5)
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                SettingsNumberPreference("Maximum count", value, defaultValue = 5,
                    range = 1..20, tag = "max-count", onValueChange = { value = it })
            }
        }
        compose.onNodeWithTag("max-count-open").performClick()
        compose.onNodeWithTag("app-dialog-buttons").assertExists()
        compose.onNodeWithTag("max-count").performTextReplacement("9")
        compose.runOnIdle { assertEquals(5, value) }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(5, value) }
        compose.onNodeWithTag("max-count-open").performClick()
        compose.onNodeWithTag("max-count").performTextReplacement("0")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithTag("max-count").performTextReplacement("15")
        compose.runOnIdle { assertEquals(5, value) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(15, value) }
    }

    @Test fun developerOptionsIsNavigationRatherThanAnInlineExpandableSection() {
        var navigations = 0
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                PreferencesSettingsView(
                    appPreferences = AppPreferences(),
                    onContinueLastConversationChange = {},
                    onPersistSelectedModelChange = {},
                    onAutoScrollChange = {},
                    onEnableVibrationChange = {},
                    onLatexModeChange = {},
                    onOpenDeveloperOptions = { navigations++ }
                )
            }
        }
        compose.onNodeWithText("Enable debug logging").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Developer Options"))
        compose.onNodeWithText("Developer Options").performClick()
        compose.runOnIdle { assertEquals(1, navigations) }
        compose.onNodeWithText("Enable debug logging").assertDoesNotExist()
    }

    @Test fun instructionsScopeSettingOffersAllFiveModesAndChangesSelection() {
        var settings by mutableStateOf(AppPreferences())
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                PreferencesSettingsView(
                    appPreferences = settings,
                    onContinueLastConversationChange = {},
                    onPersistSelectedModelChange = {},
                    onInstructionSelectionModeChange = { settings = settings.copy(instructionSelectionMode = it) },
                    onAutoScrollChange = {},
                    onEnableVibrationChange = {},
                    onLatexModeChange = {}
                )
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("instruction-selection-mode"))
        compose.onNodeWithTag("instruction-selection-mode").performClick()
        compose.onNodeWithText("Per conversation").assertExists()
        compose.onNodeWithText("Global").assertExists()
        compose.onNodeWithText("Per folder").assertExists()
        compose.onNodeWithText("Per conversation, remember globally").assertExists()
        compose.onAllNodesWithText("Per conversation or folder").onFirst().assertExists()
        compose.onNodeWithText("Per folder").performClick()
        compose.runOnIdle { assertEquals(InstructionSelectionMode.PER_FOLDER, settings.instructionSelectionMode) }
    }

    @Test fun developerScreenKeepsSwitchAndShowsFooterlessCategoryChoices() {
        var preferences by mutableStateOf(AppPreferences())
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                DeveloperOptionsScreen(
                    preferences = preferences,
                    onDebugModeChange = { preferences = preferences.copy(debugMode = it) },
                    onDebugLogCategoriesChange = { preferences = preferences.copy(debugLogCategories = it) },
                    onDebugLogLevelChange = { preferences = preferences.copy(debugLogLevel = it) },
                    onOpenLauncherDragTest = {},
                    onBack = {}
                )
            }
        }
        compose.onNodeWithText("Enable debug logging").performClick()
        compose.onNodeWithText("Log sources").performClick()
        compose.onNodeWithText("Network").assertExists()
        compose.onNodeWithText("System").assertExists()
        compose.onNodeWithTag("app-dialog-buttons").assertDoesNotExist()
    }

    @Test fun developerOptionsSelectNativeLogSeverityFromTraceThroughError() {
        var settings by mutableStateOf(AppPreferences(debugMode = true))
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                DeveloperOptionsScreen(
                    preferences = settings,
                    onDebugModeChange = { settings = settings.copy(debugMode = it) },
                    onDebugLogCategoriesChange = { settings = settings.copy(debugLogCategories = it) },
                    onDebugLogLevelChange = { settings = settings.copy(debugLogLevel = it) },
                    onOpenLauncherDragTest = {}, onBack = {}
                )
            }
        }
        compose.onNodeWithText("Minimum log level").performClick()
        compose.onNodeWithText("TRACE").performClick()
        compose.runOnIdle {
            assertEquals(org.starfall.generative.shared.model.logging.DebugLogLevel.TRACE,
                settings.debugLogLevel)
        }
        compose.onNodeWithText("Minimum log level").performClick()
        compose.onNodeWithText("ERROR").performClick()
        compose.runOnIdle {
            assertEquals(org.starfall.generative.shared.model.logging.DebugLogLevel.ERROR,
                settings.debugLogLevel)
        }
    }

    @Test fun developerOptionsKeepsLogControlsTogetherAndLauncherTestSeparate() {
        var settings by mutableStateOf(AppPreferences(debugMode = true))
        var launcherOpens = 0
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                DeveloperOptionsScreen(
                    preferences = settings,
                    onDebugModeChange = { settings = settings.copy(debugMode = it) },
                    onDebugLogCategoriesChange = { settings = settings.copy(debugLogCategories = it) },
                    onDebugLogLevelChange = { settings = settings.copy(debugLogLevel = it) },
                    onOpenLauncherDragTest = { launcherOpens++ },
                    onBack = {}
                )
            }
        }
        val enableBounds = compose.onNodeWithText("Enable debug logging").fetchSemanticsNode().boundsInRoot
        val categoriesBounds = compose.onNodeWithText("Log sources").fetchSemanticsNode().boundsInRoot
        val launcherBounds = compose.onNodeWithText("Launcher drag test").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Log scope should directly follow the debug toggle",
            enableBounds.top < categoriesBounds.top)
        org.junit.Assert.assertTrue("Launcher test belongs below the log settings",
            categoriesBounds.bottom < launcherBounds.top)
        compose.onNodeWithText("Launcher drag test").performClick()
        compose.runOnIdle { assertEquals(1, launcherOpens) }
    }

    @Test fun mcpLimitsDialogHasFourFieldsAndCommitsTogetherOnlyOnSave() {
        var settings by mutableStateOf(McpAttachmentSettings())
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                McpAttachmentsPreferences(settings, onChange = { settings = it })
            }
        }
        compose.onNodeWithText("On-demand limits").performClick()
        compose.onNodeWithTag("app-dialog-buttons").assertExists()
        for (tag in listOf("min-mcps", "min-tools", "list-limit", "load-limit")) {
            compose.onNodeWithTag("mcp-attachment-$tag").assertExists()
        }
        compose.onNodeWithTag("mcp-attachment-min-mcps").performTextReplacement("17")
        compose.runOnIdle { assertEquals(5, settings.minMcps) }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(5, settings.minMcps) }
        compose.onNodeWithText("On-demand limits").performClick()
        compose.onNodeWithTag("mcp-attachment-min-mcps").performTextReplacement("17")
        compose.onNodeWithTag("mcp-attachment-min-tools").performTextReplacement("8")
        compose.onNodeWithTag("mcp-attachment-list-limit").performTextReplacement("72")
        compose.onNodeWithTag("mcp-attachment-load-limit").performTextReplacement("4")
        compose.runOnIdle { assertEquals(5, settings.minMcps) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(17, settings.minMcps)
            assertEquals(8, settings.minTools)
            assertEquals(72, settings.listLimit)
            assertEquals(4, settings.loadLimit)
        }
    }
}
