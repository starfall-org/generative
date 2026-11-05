package org.starfall.generative

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashReport
import org.starfall.generative.shared.subfeatures.crashreports.ui.CrashReportDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-xhdpi", application = LibraryTestApplication::class)
class CrashReportDialogUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun crashDetailsUseSharedErrorContainerWithCopyAndExistingActions() {
        val report = CrashReport(
            id = "crash-1", timestamp = 1L, appVersion = "1.0", device = "device",
            androidVersion = "Android 16", thread = "main", cause = "Fatal crash",
            stackTrace = "java.lang.IllegalStateException: Fatal crash\n at Main.kt:1"
        )
        compose.setContent { MaterialTheme {
            CrashReportDialog(report, onCreateIssue = {}, onSaveLog = {}, onDismiss = {})
        } }
        compose.onNodeWithText("Fatal crash", substring = true).assertExists()
        compose.onNodeWithTag("error-container-copy").assertExists().performClick()
        compose.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("${report.cause}\n\n${report.stackTrace}", clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
        compose.onNodeWithText("Save as .log file").assertIsDisplayed()
        compose.onNodeWithText("Create GitHub issue").assertExists()
    }
}
