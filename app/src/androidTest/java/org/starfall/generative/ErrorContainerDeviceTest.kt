package org.starfall.generative

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.ui.AlertSeverity

/** Real-Android checks for the common error surface, independent of any screen or network. */
@RunWith(AndroidJUnit4::class)
class ErrorContainerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clipboardText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    private fun renderedLines(text: String): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().lineCount
    }

    @Test fun longErrorCollapsesToThreeLinesExpandsAndResetsWhenMessageChanges() {
        val original = (1..8).joinToString("\n") { "Frame $it" }
        var error by mutableStateOf(original)
        compose.setContent {
            MaterialTheme {
                ErrorContainer(message = error, title = "Generation error")
            }
        }

        compose.onNodeWithText("Generation error").assertIsDisplayed()
        assertEquals(3, renderedLines(original))
        compose.onNodeWithTag("error-container-toggle").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.message_collapse_error))
            .assertExists()
        assertTrue(renderedLines(original) > 3)

        compose.onNodeWithTag("error-container-toggle").performClick()
        assertEquals(3, renderedLines(original))
        compose.runOnIdle { error = "Different failure\nAnother detail" }
        compose.onNodeWithContentDescription(context.getString(R.string.message_expand_error))
            .assertExists()
        compose.onNodeWithText("Different failure\nAnother detail").assertIsDisplayed()
    }

    @Test fun copyingCollapsedErrorCopiesEntirePayloadAndCallbacksAreIndependent() {
        val original = (1..8).joinToString("\n") { "Stack frame $it" }
        val payload = "Root cause\n\n$original"
        var retries = 0
        var dismissals = 0

        compose.setContent {
            MaterialTheme {
                ErrorContainer(
                    message = original,
                    copyText = payload,
                    onRetry = { retries++ },
                    onDismiss = { dismissals++ }
                )
            }
        }

        assertEquals(3, renderedLines(original))
        compose.onNodeWithTag("error-container-copy").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(payload, clipboardText()) }

        compose.onNodeWithText(context.getString(R.string.common_retry)).performClick()
        compose.onNodeWithTag("error-container-dismiss").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, dismissals)
        }
    }

    @Test fun warningUsesSharedActionsWithNeutralAccessibilityLabels() {
        val warning = "Output token limit reached."
        var retries = 0
        compose.setContent {
            MaterialTheme {
                ErrorContainer(
                    message = warning,
                    severity = AlertSeverity.WARNING,
                    onRetry = { retries++ }
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.common_warning)).assertIsDisplayed()
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.expand)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.collapse)).assertExists()
        compose.onNodeWithTag("error-container-copy").performClick()
        compose.runOnIdle { assertEquals(warning, clipboardText()) }
        compose.onNodeWithText(context.getString(R.string.common_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun compactErrorHasNoExpansionOrUnexpectedActions() {
        compose.setContent {
            MaterialTheme { ErrorContainer(message = "Could not save", expandable = false) }
        }

        compose.onNodeWithText("Could not save").assertIsDisplayed()
        compose.onNodeWithTag("error-container-toggle").assertDoesNotExist()
        compose.onNodeWithTag("error-container-dismiss").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_retry)).assertDoesNotExist()
        compose.onNodeWithTag("error-container-copy").assertIsDisplayed()
    }
}
