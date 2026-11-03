package org.starfall.generative

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.ui.markdown.StreamGrowthViewport

/** Measured layout behavior, not text opacity or a simulated animated overlay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class StreamGrowthViewportTest {
    @get:Rule val compose = createComposeRule()

    private fun height(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height

    @Test fun settledViewportNeverShrinksWhenMarkdownReflows() {
        var textHeight by mutableStateOf(40.dp)
        compose.setContent { MaterialTheme {
            StreamGrowthViewport(Modifier.testTag("growth-viewport")) {
                Box(Modifier.fillMaxWidth().height(textHeight).testTag("natural-text"))
            }
        } }
        compose.waitForIdle()
        val initial = height("growth-viewport")
        assertTrue(initial > 0f)

        compose.runOnIdle { textHeight = 310.dp }
        compose.waitForIdle()
        val grown = height("growth-viewport")
        assertTrue("Actual measured content must expand the viewport", grown > initial)
        assertEquals(height("natural-text"), grown, 1f)

        // Robolectric does not reliably advance the intermediate spring frames; the
        // real-device test checks those frames and the compositor's actual clipping.
        compose.runOnIdle { textHeight = 70.dp }
        compose.waitForIdle()
        assertEquals("A shorter child must not move the streaming edge back up",
            grown, height("growth-viewport"), 1f)
    }

    @Test fun finishingReleasesClipAndAllExtraHeightWithoutRemountingTheContent() {
        var streaming by mutableStateOf(true)
        var bodyHeight by mutableStateOf(250.dp)
        var mounts = 0
        var disposes = 0
        compose.setContent { MaterialTheme {
            Column {
                StreamGrowthViewport(Modifier.testTag("growth-viewport"), active = streaming) {
                    DisposableEffect(Unit) {
                        mounts++
                        onDispose { disposes++ }
                    }
                    Box(Modifier.fillMaxWidth().height(bodyHeight).testTag("actual-body"))
                }
                Box(Modifier.fillMaxWidth().height(30.dp).testTag("message-toolbar"))
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle {
            streaming = false
            bodyHeight = 70.dp
        }
        compose.waitForIdle()
        assertEquals("Finished content must have its exact natural height, with no stale clip space",
            height("actual-body"), height("growth-viewport"), 1f)
        val bodyBottom = compose.onNodeWithTag("growth-viewport")
            .fetchSemanticsNode().boundsInRoot.bottom
        val toolbarTop = compose.onNodeWithTag("message-toolbar")
            .fetchSemanticsNode().boundsInRoot.top
        assertEquals("The message toolbar must begin after all finished text", bodyBottom, toolbarTop, 1f)
        assertEquals("Do not remount stable content to remove its clipping boundary", 1, mounts)
        assertEquals(0, disposes)
    }

    @Test fun changingContentHeightDoesNotDisposeExistingBlockComposition() {
        var tailHeight by mutableStateOf(16.dp)
        var mounts = 0
        var disposes = 0
        compose.setContent { MaterialTheme {
            StreamGrowthViewport {
                Column {
                    DisposableEffect(Unit) {
                        mounts++
                        onDispose { disposes++ }
                    }
                    Box(Modifier.fillMaxWidth().height(50.dp))
                    Box(Modifier.fillMaxWidth().height(tailHeight))
                }
            }
        } }
        compose.waitForIdle()
        repeat(6) { index ->
            compose.runOnIdle { tailHeight = (20 + index * 28).dp }
            compose.waitForIdle()
        }
        assertEquals("Already composed content must remain mounted during streaming", 1, mounts)
        assertEquals(0, disposes)
    }
}
