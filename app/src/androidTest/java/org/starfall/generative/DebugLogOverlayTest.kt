package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLog
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.shared.subfeatures.logging.ui.DebugLogOverlay

class DebugLogOverlayTest {
    @get:Rule val compose = createComposeRule()

    @After fun clearLogs() = DebugLog.configure(false, emptySet())

    @Test fun holdingFloatButtonDisablesLoggingWithoutOpeningPanel() {
        DebugLog.configure(true, DebugLogCategory.all, DebugLogLevel.INFO)
        var enabled by mutableStateOf(true)
        var disableCount = 0
        compose.setContent {
            MaterialTheme {
                if (enabled) DebugLogOverlay(onDisable = {
                    disableCount++
                    enabled = false
                    DebugLog.configure(false, emptySet())
                })
            }
        }
        compose.onNodeWithTag("debug-log-fab").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, disableCount)
            assertFalse(enabled)
            assertTrue(DebugLog.entries.value.isEmpty())
        }
        compose.onNodeWithTag("debug-log-fab").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-detail").assertDoesNotExist()
    }

    @Test fun draggingFloatButtonStillMovesWithoutDisablingLogs() {
        var disableCount = 0
        compose.setContent {
            MaterialTheme { DebugLogOverlay(onDisable = { disableCount++ }) }
        }
        val fab = compose.onNodeWithTag("debug-log-fab")
        val before = fab.fetchSemanticsNode().boundsInRoot
        fab.performTouchInput { swipe(center, center + Offset(-100f, 70f), durationMillis = 300) }
        compose.waitForIdle()
        val after = fab.fetchSemanticsNode().boundsInRoot
        assertTrue("Dragging should move the floating button to the left", after.left < before.left - 20f)
        compose.runOnIdle { assertEquals(0, disableCount) }
        fab.assertIsDisplayed()
    }

    @Test fun requestExpandsAndOutsideTapClosesPanel() {
        DebugLog.configure(true, DebugLogCategory.all, DebugLogLevel.INFO)
        val id = DebugLog.beginRequest("HTTP", "POST https://example.test",
            method = "POST", endpoint = "https://example.test")
        DebugLog.finishRequest(id, "HTTP", "← 500 POST https://example.test", true, statusCode = 500)
        compose.setContent { MaterialTheme { DebugLogOverlay(onDisable = {}) } }
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(R.string.debug_log_toggle)).performClick()
        compose.onNodeWithText("request body", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("debug-log-entry-$id").performClick()
        compose.onNodeWithTag("debug-log-detail").assertExists()
        compose.onNodeWithText("500").assertExists()
        compose.onNodeWithTag("debug-log-detail-close").performClick()
        compose.onNodeWithTag("debug-log-detail").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        compose.onNodeWithText(context.getString(R.string.debug_logs), substring = true).assertDoesNotExist()
    }
}
