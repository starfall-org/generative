package org.starfall.generative

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
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
    fun downwardDragFromContentHeightDismissesAfterRelease() {
        checkDownwardDismissal(fullHeight = false)
    }

    @Test
    fun downwardDragFromFullHeightDismissesAfterRelease() {
        checkDownwardDismissal(fullHeight = true)
    }

    @Test
    fun downwardDragThroughListAtStartDismissesAfterRelease() {
        checkDownwardDismissal(fullHeight = false, throughList = true)
    }

    @Test
    fun draggingDownDuringEntranceStillDismissesExactlyOnce() {
        var dismissals = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = { dismissals++ }) {
                    Spacer(Modifier.height(320.dp))
                }
            }
        }
        val handle = compose.onNodeWithTag("bottom-sheet-drag-handle")
        val height = with(compose.density) { 800.dp.toPx() }
        val handleHeight = with(compose.density) { 48.dp.toPx() }
        // Stop as soon as the handle enters the window, before show() settles.
        var frames = 0
        while (with(compose.density) { handle.getUnclippedBoundsInRoot().top.toPx() } >=
            height - handleHeight && frames++ < 120
        ) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertTrue("The handle must be reachable during entrance", frames < 120)
        assertEquals(0, dismissals)
        handle.performTouchInput {
            down(center)
            moveBy(Offset(0f, height), delayMillis = 200)
            moveBy(Offset.Zero, delayMillis = 200)
            up()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Interrupted entrance must still dismiss once", 1, dismissals) }
    }

    private fun checkDownwardDismissal(fullHeight: Boolean, throughList: Boolean = false) {
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = { dismissals++ }) {
                    when {
                        throughList -> LazyColumn(Modifier.fillMaxWidth().height(320.dp).testTag("dismiss-list")) {
                            items(60) { Text("Row $it", Modifier.fillMaxWidth().height(48.dp)) }
                        }
                        fullHeight -> Box(Modifier.fillMaxSize())
                        else -> Text("Short sheet")
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("Entrance must not dismiss the sheet", 0, dismissals)
        val sheet = compose.onNodeWithTag("bottom-sheet-window")
        val height = with(compose.density) { sheet.getUnclippedBoundsInRoot().height.toPx() }
        compose.onNodeWithTag(if (throughList) "dismiss-list" else "bottom-sheet-drag-handle").performTouchInput {
            down(center)
            moveBy(Offset(0f, height * 0.8f), delayMillis = 1000)
            moveBy(Offset.Zero, delayMillis = 200)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("A downward drag must dismiss once", 1, dismissals) }
    }

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
    fun shortContentRemainsContentSizedWhenDraggedUp() {
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
        assertEquals("Dragging up must not add empty space to short content", partialTop.value, expandedTop.value, 2f)
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
        assertTrue("490dp of content must not be clipped to a 50% peek (top=$topPx)",
            topPx < with(compose.density) { 390.dp.toPx() })
        assertTrue("Medium content should still leave room for full-height expansion", topPx > 100f)
        compose.onNodeWithTag("bottom-sheet-drag-handle").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val fullTop = compose.onNodeWithTag("bottom-sheet-window")
            .getUnclippedBoundsInRoot().top
        assertEquals("Medium content already opens at its natural height", defaultTop.value, fullTop.value, 2f)
    }

    @Test
    fun fixedFractionSheetsKeepTheirConfiguredHeight() {
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
        assertEquals("Dragging up keeps the configured fraction", partialTop.value, expandedTop.value, 2f)
    }
    @Test
    fun downwardDragFromExpandedPresentationDismisses() {
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = { dismissals++ }, expandOnOpen = true,
                    fixedHeightFraction = 0.7f) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("bottom-sheet-drag-handle").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

}
