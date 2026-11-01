package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.ui.ProcessingDropdown
import org.starfall.generative.features.home.chat.ui.ProcessingDropdownItem
import org.starfall.generative.features.home.chat.ui.AssistantMessageCard
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ProcessingDropdownRetentionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun streamCompletionDoesNotCollapseOpenedActionsOrManuallyExpandedThinking() {
        var streaming by mutableStateOf(true)
        val items = listOf(ProcessingDropdownItem.Thinking((1..30).joinToString("\n") { "Thinking line $it" }))
        compose.setContent { MaterialTheme {
            ProcessingDropdown(items, durationMillis = null, startNumber = 1, isStreaming = streaming)
        } }
        val toggle = compose.onNodeWithTag("processing-dropdown-toggle")
        toggle.performClick() // Open Actions explicitly; streaming must not force it.
        val preview = compose.onNodeWithTag("live-thinking-preview")
        preview.assert(hasScrollAction())
        preview.performClick() // Expand Thinking manually.
        preview.assert(!hasScrollAction())
        compose.runOnIdle { streaming = false }
        compose.waitForIdle()
        preview.assertExists()
        preview.assert(!hasScrollAction()) // Same instance, not replaced by a collapsed renderer.
        compose.onNodeWithTag("processing-dropdown-toggle").performClick() // Explicitly close Actions.
        compose.waitForIdle()
        compose.onNodeWithTag("processing-dropdown-toggle").performClick() // Reopen at any time.
        compose.waitForIdle()
        compose.onNodeWithText("Thinking line 30", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun userCanOpenFinishedActionsAtAnyTime() {
        val items = listOf(ProcessingDropdownItem.Thinking("Completed thinking"))
        compose.setContent { MaterialTheme {
            ProcessingDropdown(items, durationMillis = null, startNumber = 1, isStreaming = false)
        } }
        compose.onNodeWithTag("processing-dropdown-toggle").assertExists().performClick()
        compose.onNodeWithText("Completed thinking", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun tappingActionsLeftPaddingClosesOnlyTheOpenTimeline() {
        val items = listOf(ProcessingDropdownItem.Thinking("Completed thinking"))
        compose.setContent { MaterialTheme {
            ProcessingDropdown(items, durationMillis = null, startNumber = 1, isStreaming = false)
        } }
        val header = compose.onNodeWithTag("processing-dropdown-toggle")
        val padding = compose.onNodeWithTag("processing-dropdown-padding")
        padding.assertDoesNotExist() // No invisible hit target while closed.
        header.performClick()
        compose.onNodeWithText("Completed thinking", substring = true, useUnmergedTree = true).assertExists()
        padding.assertExists().performClick()
        compose.waitForIdle()
        padding.assertDoesNotExist()
        compose.onNodeWithText("Completed thinking", substring = true, useUnmergedTree = true).assertDoesNotExist()
        header.performClick() // Normal header behavior remains available.
        compose.onNodeWithText("Completed thinking", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun tappingThinkingOrToolStepNumberClosesActions() {
        val items = listOf(
            ProcessingDropdownItem.Thinking("Completed thinking"),
            ProcessingDropdownItem.Tool(ToolActivity("tool-1", "web_search", status = "success"))
        )
        compose.setContent { MaterialTheme {
            ProcessingDropdown(items, durationMillis = null, startNumber = 1, isStreaming = false)
        } }
        val header = compose.onNodeWithTag("processing-dropdown-toggle")

        // Each badge handles its own tap without widening the original padding strip.
        header.performClick()
        compose.onNodeWithTag("processing-dropdown-padding").assertWidthIsEqualTo(8.dp)
        compose.onNodeWithText("Completed thinking", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("processing-step-number-0").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("processing-dropdown-padding").assertDoesNotExist()
        compose.onNodeWithText("Completed thinking", substring = true, useUnmergedTree = true).assertDoesNotExist()

        header.performClick()
        compose.onNodeWithText("web search", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("processing-step-number-1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("processing-dropdown-padding").assertDoesNotExist()
        compose.onNodeWithText("web search", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun longPressPaddingOpensDeleteMenuWithoutClosingActions() {
        var deletes = 0
        val items = listOf(ProcessingDropdownItem.Thinking("Completed thinking"))
        compose.setContent { MaterialTheme {
            ProcessingDropdown(items, durationMillis = null, startNumber = 1,
                isStreaming = false, onDelete = { deletes++ })
        } }
        val padding = compose.onNodeWithTag("processing-dropdown-padding")
        padding.assertExists().performTouchInput { longClick() }
        compose.onNodeWithText("Delete").assertExists().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, deletes) }
        compose.onNodeWithTag("processing-dropdown-toggle").assertExists()
    }

    @Test fun closedActionsShowRunningToolThenRetractItOnCompletion() {
        var status by mutableStateOf("running")
        compose.setContent { MaterialTheme {
            ProcessingDropdown(
                listOf(ProcessingDropdownItem.Tool(ToolActivity(
                    id = "tool-1", name = "web_search", status = status
                ))), durationMillis = null, startNumber = 1, isStreaming = status == "running"
            )
        } }
        // Running tools appear beneath the collapsed header without clicking it.
        compose.onNodeWithText("web search", substring = true, useUnmergedTree = true).assertExists()
        compose.runOnIdle { status = "success" }
        compose.waitForIdle()
        compose.onNodeWithText("web search", substring = true, useUnmergedTree = true).assertDoesNotExist()
        // Finished tools are still retained in the dropdown and can be opened any time.
        compose.onNodeWithTag("processing-dropdown-toggle").performClick()
        compose.onNodeWithText("web search", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun manuallyExpandedThinkingRemainsExpandedAfterRetractingIntoClosedActions() {
        var streaming by mutableStateOf(true)
        val thinking = ProcessingDropdownItem.Thinking((1..30).joinToString("\n") { "Line $it" })
        compose.setContent { MaterialTheme {
            ProcessingDropdown(listOf(thinking), durationMillis = null,
                startNumber = 1, isStreaming = streaming)
        } }
        val preview = compose.onNodeWithTag("live-thinking-preview")
        preview.assert(hasScrollAction())
        preview.performClick()
        preview.assert(!hasScrollAction())
        compose.runOnIdle { streaming = false }
        compose.waitForIdle()
        preview.assertDoesNotExist() // Completed step retracts into the closed timeline.
        compose.onNodeWithTag("processing-dropdown-toggle").performClick()
        compose.waitForIdle()
        preview.assertExists().assert(!hasScrollAction()) // Manual expansion survived the fold.
    }

    @Test fun successfulImageGenerationKeepsActionsAvailable() {
        val reply = StoredMessage("media-reply", ChatRole.MODEL,
            listOf(MessageVersion(toolActivity = listOf(
                ToolActivity(id = "image-action", name = "generate_image", status = "success",
                    files = listOf("tool-file:generated.png"))
            ))))
        compose.setContent { MaterialTheme {
            AssistantMessageCard(message = reply, isStreaming = false, onCopy = {}, onRegenerate = {},
                onEdit = {}, onDelete = {}, onRead = {}, onSwitchVersion = {})
        } }
        compose.onNodeWithTag("processing-dropdown-toggle").assertExists().performClick()
    }
}
