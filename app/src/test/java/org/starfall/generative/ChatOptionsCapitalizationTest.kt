package org.starfall.generative

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.chatoptions.ui.ChatOptionsScreen
import org.starfall.generative.features.chatoptions.ui.HelperSettingsSheet
import org.starfall.generative.features.chatoptions.ui.InstructionEditor
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.preferences.AppPreferences
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ChatOptionsCapitalizationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chatOptionsTextFields_requestSentenceCapitalizationFromIme() {
        var lastInputType = 0
        var showHelperSheet by mutableStateOf(false)

        compose.setScreenContent {
            InterceptPlatformTextInput(
                interceptor = { request, _ ->
                    val editorInfo = EditorInfo()
                    request.createInputConnection(editorInfo)
                    lastInputType = editorInfo.inputType
                    awaitCancellation()
                }
            ) {
                GenerativeTheme(darkTheme = false, amoledMode = false) {
                    if (!showHelperSheet) {
                        InstructionEditor(
                            instruction = null,
                            onPreview = {},
                            onBack = {}
                        )
                    } else {
                        HelperSettingsSheet(
                            name = "title_generation",
                            config = SystemToolConfig(),
                            appPreferences = AppPreferences(),
                            onSave = {},
                            onAutoSummaryEnabledChange = {},
                            onSummarySuggestionEnabledChange = {},
                            onAutoSummaryPercentChange = {},
                            onDismiss = {}
                        )
                    }
                }
            }
        }

        // 1. Instruction editor: instruction name field in TopAppBar
        compose.onNodeWithTag("instruction_name_input").performClick()
        compose.waitForIdle()
        assertNotEquals(
            "Instruction name input should request sentence capitalization",
            0,
            lastInputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        )

        // 2. Instruction editor: instruction content field
        lastInputType = 0
        compose.onNodeWithTag("instruction_content_input").performClick()
        compose.waitForIdle()
        assertNotEquals(
            "Instruction content input should request sentence capitalization",
            0,
            lastInputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        )

        // 3. Helper Settings Sheet: helper instruction prompt field
        lastInputType = 0
        showHelperSheet = true
        compose.waitForIdle()
        compose.onNodeWithTag("helper_instruction_input").performClick()
        compose.waitForIdle()
        assertNotEquals(
            "Helper instruction prompt input should request sentence capitalization",
            0,
            lastInputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        )
    }

    @Test
    fun instructionsTab_placesAddInstructionIconButtonInTopAppBar() {
        var activeTab by mutableStateOf("helpers")

        compose.setScreenContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                ChatOptionsScreen(
                    providers = emptyList(),
                    settings = ToolSettings(),
                    initialTab = activeTab,
                    onSave = { _, _ -> },
                    onBack = {}
                )
            }
        }

        // Options tab does not show the Add action in the TopAppBar.
        compose.onNodeWithContentDescription("Add").assertDoesNotExist()

        // Instructions tab exposes Add as a TopAppBar IconButton (not a body text Button)
        // and opens InstructionEditor when clicked.
        activeTab = "instructions"
        compose.waitForIdle()
        compose.onNodeWithText("Add").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add")
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("instruction_name_input").assertIsDisplayed()
    }
}
