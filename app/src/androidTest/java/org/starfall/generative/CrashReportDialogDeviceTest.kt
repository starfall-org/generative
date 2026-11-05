package org.starfall.generative

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashReport
import org.starfall.generative.shared.subfeatures.crashreports.ui.CrashReportDialog

/** Confirms the actual crash dialog exposes complete diagnostics without losing its actions. */
@RunWith(AndroidJUnit4::class)
class CrashReportDialogDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun crashDialogCopiesWholeReportAndKeepsSaveIssueAndDismissActions() {
        val trace = (1..18).joinToString("\n") { "at org.starfall.generative.Screen$it.render(Screen.kt:$it)" }
        val report = CrashReport(
            id = "crash-device-test", timestamp = 1L, appVersion = "1.0",
            device = "Test device", androidVersion = "Android", thread = "main",
            cause = "IllegalStateException: UI failure", stackTrace = trace
        )
        var saved = 0
        var issues = 0
        var dismissed = 0

        compose.setContent {
            MaterialTheme {
                CrashReportDialog(
                    report,
                    onCreateIssue = { issues++ },
                    onSaveLog = { saved++ },
                    onDismiss = { dismissed++ }
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.crash_report_title)).assertExists()
        compose.onNodeWithTag("error-container-copy").assertExists().performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(
                "${report.cause}\n\n${report.stackTrace}",
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            )
        }
        // The report starts expanded, but can still be collapsed for easier navigation.
        compose.onNodeWithContentDescription(context.getString(R.string.message_collapse_error))
            .assertExists().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.message_expand_error))
            .assertExists()

        compose.onNodeWithText(context.getString(R.string.crash_save_log)).performClick()
        compose.onNodeWithText(context.getString(R.string.crash_create_issue)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_close)).performClick()
        compose.runOnIdle {
            assertEquals(1, saved)
            assertEquals(1, issues)
            assertEquals(1, dismissed)
        }
    }
}
