package org.starfall.generative

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.sheets.BottomSheet

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
class AppSheetAnchorsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longContentOpensFullyBelowStatusBarWithoutHalfScreenStop() {
        var statusInset = 0f
        compose.setContent {
            val inset = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
            SideEffect { statusInset = inset }
            MaterialTheme {
                BottomSheet(onDismissRequest = {}) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()

        val initialTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("bottom-sheet-drag-handle").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val afterSwipeTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        val initialPx = with(compose.density) { initialTop.toPx() }
        val afterPx = with(compose.density) { afterSwipeTop.toPx() }
        assertTrue("Long content should start at the maximum sheet height", initialPx <= statusInset + 8f)
        assertTrue("The expanded stop remains below the status bar", afterPx >= statusInset - 2f)
        assertTrue("No separate 50% stop for long content", kotlin.math.abs(afterPx - initialPx) < 12f)
    }

    @Test
    fun shortContentAlsoKeepsTheDefaultAndExpandedStops() {
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = {}) { Text("Short sheet") }
            }
        }
        compose.waitForIdle()
        val partialTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("bottom-sheet-drag-handle").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val expandedTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        assertTrue("Short content should start below half screen rather than at 50%",
            with(compose.density) { partialTop.toPx() } > 450f)
        assertTrue("Short content must still have a second full-height stop", expandedTop < partialTop)
    }

    @Test
    fun mediumContentOpensAboveHalfAtItsMeasuredHeight() {
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = {}) {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(490.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        val defaultTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        val topPx = with(compose.density) { defaultTop.toPx() }
        assertTrue("490dp of content must not be clipped to a 50% peek", topPx < 390f)
        assertTrue("Medium content should still leave room for full-height expansion", topPx > 100f)
        compose.onNodeWithTag("bottom-sheet-drag-handle").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val fullTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        assertTrue(fullTop < defaultTop)
    }

    @Test
    fun fixedFractionSheetsStillExposeBothNativeStops() {
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = {}, fixedHeightFraction = 0.82f) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        val partialTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("bottom-sheet-drag-handle").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val expandedTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        assertTrue("Fixed-fraction sheets must not skip PartiallyExpanded",
            expandedTop < partialTop)
    }
    @Test
    fun automaticExpansionStillAllowsNativePartialStopByDraggingDown() {
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = {}, expandOnOpen = true,
                    fixedHeightFraction = 0.7f) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        val expandedTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("bottom-sheet-drag-handle").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val partialTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        assertTrue("Auto-expand must not remove the native partially expanded anchor", partialTop > expandedTop)
    }

}
