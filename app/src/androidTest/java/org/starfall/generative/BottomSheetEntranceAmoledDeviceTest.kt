package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.theme.LocalAmoledMode
import org.starfall.generative.shared.ui.sheets.BottomSheet
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean

/** Device regressions for the content-sized scaffold sheet's entry motion and visible edge. */
class BottomSheetEntranceAmoledDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun entryAnimatesFromHiddenInsteadOfAppearingAtThePeekAnchor() {
        compose.mainClock.autoAdvance = false
        val dismissed = AtomicBoolean(false)
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = { dismissed.set(true) }) {
                    Column {
                        Text("Animated sheet")
                        Spacer(Modifier.fillMaxWidth().height(240.dp))
                    }
                }
            }
        }

        val topPositions = mutableListOf<Float>()
        repeat(64) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            topPositions += compose.onNodeWithTag("bottom-sheet-window")
                .fetchSemanticsNode().boundsInRoot.top
        }
        val start = topPositions.first()
        val finish = topPositions.last()
        val motionThreshold = with(compose.density) { 12.dp.toPx() }
        assertFalse("The initial Hidden state must not dismiss the dialog", dismissed.get())
        assertTrue("Sheet should enter upward from its Hidden anchor", start > finish + motionThreshold)
        assertTrue("Entry should have intermediate animated frames instead of snapping",
            topPositions.any { it < start - motionThreshold && it > finish + motionThreshold })
    }

    @Test
    fun amoledOutlineStaysAtTheVisibleTopWhenPeekedAndExpanded() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(outlineVariant = Color.Magenta)) {
                CompositionLocalProvider(LocalAmoledMode provides true) {
                    BottomSheet(onDismissRequest = {}) {
                        Text("AMOLED sheet", Modifier.fillMaxWidth().height(220.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTopBorderIsMagenta()
        compose.onNodeWithTag("bottom-sheet-drag-handle").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTopBorderIsMagenta()
    }

    private fun assertTopBorderIsMagenta() {
        val rootNode = compose.onNodeWithTag("app-bottom-sheet").fetchSemanticsNode()
        val sheetNode = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode()
        val top = (sheetNode.boundsInRoot.top - rootNode.boundsInRoot.top).roundToInt()
        val pixels = compose.onNodeWithTag("app-bottom-sheet").captureToImage().toPixelMap()
        val x = pixels.width / 2
        val first = (top - 2).coerceAtLeast(0)
        val last = (top + with(compose.density) { 4.dp.toPx() }.roundToInt())
            .coerceAtMost(pixels.height - 1)
        assertTrue("AMOLED border should follow the visible sheet top at y=$top",
            first <= last && (first..last).any { y ->
                val color = pixels[x, y]
                color.red > 0.4f && color.blue > 0.4f &&
                    color.green < color.red * 0.6f
            })
    }
}
