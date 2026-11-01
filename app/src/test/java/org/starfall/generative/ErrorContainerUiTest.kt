package org.starfall.generative

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.ui.AlertSeverity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ErrorContainerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun detailsExpandCollapseAndResetOnNewError() {
        var error by mutableStateOf("First\nSecond\nThird\nFourth")
        compose.setContent { MaterialTheme {
            ErrorContainer(message = error, title = "Generation error")
        } }
        compose.onNodeWithText("Generation error").assertIsDisplayed()
        compose.onNodeWithTag("error-container-toggle").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Collapse error").assertIsDisplayed()
        compose.runOnIdle { error = "Different failure" }
        compose.onNodeWithContentDescription("Expand error").assertIsDisplayed()
        compose.onNodeWithText("Different failure").assertIsDisplayed()
    }

    @Test fun copyUsesFullTextAndRetryAndDismissAreIndependent() {
        val detail = (1..8).joinToString("\n") { "frame $it" }
        var retries = 0
        var dismissals = 0
        compose.setContent { MaterialTheme {
            ErrorContainer(
                message = detail,
                copyText = "Root cause\n$detail",
                onRetry = { retries++ },
                onDismiss = { dismissals++ }
            )
        } }
        compose.onNodeWithTag("error-container-copy").performClick()
        compose.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("Root cause\n$detail", clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithTag("error-container-dismiss").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, dismissals)
        }
    }

    @Test fun warningSeverityKeepsCopyAndActionsWithoutChangingErrorDefault() {
        var severity by mutableStateOf(AlertSeverity.WARNING)
        val detail = "Output token limit reached."
        var retries = 0
        compose.setContent {
            MaterialTheme {
                ErrorContainer(
                    message = detail,
                    severity = severity,
                    onRetry = { retries++ }
                )
            }
        }

        compose.onNodeWithText("Warning").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.onNodeWithContentDescription("Collapse").assertExists()
        compose.onNodeWithTag("error-container-copy").performClick()
        compose.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(detail, clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }

        compose.runOnIdle { severity = AlertSeverity.ERROR }
        compose.onNodeWithText("Error").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand error").assertExists()
    }

    @Test fun inlineErrorDoesNotShowExpandAction() {
        compose.setContent { MaterialTheme {
            ErrorContainer(message = "Unable to save", expandable = false)
        } }
        compose.onNodeWithText("Unable to save").assertIsDisplayed()
        compose.onNodeWithTag("error-container-toggle").assertDoesNotExist()
    }
}
