package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.shared.ui.SelectedModelOverviewContent
import org.starfall.generative.features.home.shared.ui.SelectedModelOverviewSheet
import org.starfall.generative.shared.model.language.ModelConfiguration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class SelectedModelSheetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedModelSheetOpensPickerFromItsAction() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                SelectedModelOverviewSheet(
                    modelId = "alpha",
                    config = ModelConfiguration(supportsThinking = false),
                    conversationReasoningEffort = null, onSetReasoningEffort = {},
                    onOpenModelPicker = { opened++ }, onDismiss = {}
                )
            }
        }
        compose.onNodeWithTag("open-model-picker-button").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun modelCardFollowsEffortAndOpensOptionsRatherThanShowingInlineSettings() {
        val configuration = ModelConfiguration(supportsThinking = true,
            displayName = "Alpha", modelTools = listOf("web_search", "file_search"))
        var optionsOpen = 0
        compose.setContent {
            MaterialTheme {
                SelectedModelOverviewContent(
                    modelId = "alpha",
                    config = configuration, conversationReasoningEffort = null,
                    previewTier = 0, onPreviewTierChange = {}, onSetReasoningEffort = {},
                    onOpenModelPicker = {}, onOpenModelOptions = { optionsOpen++ },
                    contextWindowContent = { androidx.compose.material3.Text("Context window", Modifier.testTag("context-window-indicator")) }
                )
            }
        }
        compose.onNodeWithTag("selected-model-button-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("model-options-toggle").assertDoesNotExist()
        compose.onNodeWithTag("model-options-dropdown").assertDoesNotExist()
        compose.onNodeWithTag("selected-model-card").assertIsDisplayed()
        val contextBottom = compose.onNodeWithTag("context-window-indicator").fetchSemanticsNode().boundsInRoot.bottom
        val effortBounds = compose.onNodeWithTag("reasoning-slider").fetchSemanticsNode().boundsInRoot
        val cardBounds = compose.onNodeWithTag("selected-model-card").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("passback-thinking-mode").assertDoesNotExist()
        compose.onNodeWithTag("model-tool-switch-web_search").assertDoesNotExist()
        compose.runOnIdle {
            org.junit.Assert.assertTrue("Reasoning Effort must follow context", effortBounds.top > contextBottom)
            org.junit.Assert.assertTrue("Model card must follow Reasoning Effort", cardBounds.top >= effortBounds.bottom)
        }
        compose.onNodeWithText("Alpha").performClick()
        compose.runOnIdle { assertEquals(1, optionsOpen) }
    }

    @Test fun originalHeaderButtonOrderAndCompactChangeModelWidthArePreserved() {
        compose.setContent {
            MaterialTheme {
                SelectedModelOverviewContent(
                    modelId = "alpha", config = ModelConfiguration(supportsThinking = false),
                    conversationReasoningEffort = null,
                    previewTier = 0, onPreviewTierChange = {}, onSetReasoningEffort = {},
                    onOpenModelPicker = {}, onSummarize = {}
                )
            }
        }
        val summary = compose.onNodeWithTag("summarize-conversation-button")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val change = compose.onNodeWithTag("open-model-picker-button")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("selected-model-button-icon", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Models").assertIsDisplayed()
        compose.onNodeWithText("Change language model").assertDoesNotExist()
        compose.onNodeWithTag("selected-model-card").assertExists()
        val sheetWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        compose.runOnIdle {
            org.junit.Assert.assertTrue("Summarize must remain left of Change model", summary.right < change.left)
            org.junit.Assert.assertTrue("Change model must wrap its content", change.width < sheetWidth * 0.65f)
        }
    }

}
