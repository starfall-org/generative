package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.starfall.generative.shared.ui.sheets.BottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BottomSheetScrollBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun upwardDragExpandsToStatusBarBeforeScrollingItems() {
        lateinit var listState: LazyListState
        compose.activityRule.scenario.onActivity { WindowCompat.setDecorFitsSystemWindows(it.window, false) }
        compose.setContent { MaterialTheme {
            listState = rememberLazyListState()
            BottomSheet(onDismissRequest = {}, fixedHeightFraction = 0.5f) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().fillMaxHeight().testTag("growing-list")) {
                    items(80) { Text("Row $it", Modifier.fillMaxWidth().height(52.dp)) }
                }
            }
        } }
        compose.waitForIdle()
        val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
        val statusBottom = maxOf(insets.getInsets(WindowInsetsCompat.Type.systemBars()).top,
            insets.displayCutout?.safeInsetTop ?: 0).toFloat()
        val sheet = compose.onNodeWithTag("bottom-sheet-window")
        val list = compose.onNodeWithTag("growing-list")
        val initialTop = sheet.fetchSemanticsNode().boundsInRoot.top
        val firstDistance = minOf(with(compose.density) { 70.dp.toPx() }, (initialTop - statusBottom) / 3f)

        list.performTouchInput {
            down(Offset(width / 2f, height * 0.8f))
            moveBy(Offset(0f, -firstDistance), delayMillis = 200)
        }
        compose.waitForIdle()
        val intermediateTop = sheet.fetchSemanticsNode().boundsInRoot.top
        assertTrue("The first upward drag must expand the sheet", intermediateTop < initialTop - 10f)
        compose.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }

        val remaining = (intermediateTop - statusBottom).coerceAtLeast(0f)
        val extra = with(compose.density) { 120.dp.toPx() }
        list.performTouchInput { moveBy(Offset(0f, -(remaining + extra)), delayMillis = 300) }
        compose.waitForIdle()
        val expandedTop = sheet.fetchSemanticsNode().boundsInRoot.top
        assertTrue("The sheet must stop below the status bar", expandedTop >= statusBottom - 2f)
        assertTrue("The same drag should reach the maximum height", expandedTop <= statusBottom + 4f)
        compose.runOnIdle {
            assertTrue("Remaining drag must scroll the list after expansion",
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        }
        list.performTouchInput { moveBy(Offset.Zero, delayMillis = 200); up() }
    }

    @Test fun draggingDownAtListStartMovesTheSheet() {
        checkDownwardDrag(initialIndex = 0, expectSheetMovement = true)
    }

    @Test fun theSameDragContinuesIntoTheSheetAfterReachingListStart() {
        checkDownwardDrag(initialIndex = 1, expectSheetMovement = true)
    }

    @Test fun draggingDownInTheMiddleScrollsTheListBeforeMovingTheSheet() {
        checkDownwardDrag(initialIndex = 10, expectSheetMovement = false)
    }

    private fun checkDownwardDrag(initialIndex: Int, expectSheetMovement: Boolean) {
        lateinit var listState: LazyListState
        compose.setContent { MaterialTheme {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
            BottomSheet(onDismissRequest = {}) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(320.dp).testTag("edge-list")) {
                    items(60) { Text("Row $it", Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        } }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("bottom-sheet-drag-handle")
        val initialTop = sheet.fetchSemanticsNode().boundsInRoot.top
        val list = compose.onNodeWithTag("edge-list")
        val distance = if (expectSheetMovement) with(compose.density) { (48 * initialIndex).dp.toPx() } + 100f else 80f
        list.performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, distance / 4f), delayMillis = 32) }
        }
        compose.waitForIdle()
        val draggedTop = sheet.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle {
            if (expectSheetMovement) {
                assertTrue("Unused downward drag must move the sheet: before=$initialTop after=$draggedTop list=${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset}", draggedTop > initialTop + 20f)
                assertEquals(0, listState.firstVisibleItemIndex)
                assertEquals(0, listState.firstVisibleItemScrollOffset)
            } else {
                assertEquals("List must consume the drag until it reaches its start", initialTop, draggedTop, 1f)
                assertTrue(listState.firstVisibleItemIndex < initialIndex)
            }
        }
        list.performTouchInput { up() }
    }

    @Test fun repeatedDownwardDragsReachTheStartWithoutAReverseDrag() {
        lateinit var listState: LazyListState
        compose.setContent { MaterialTheme {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 59)
            BottomSheet(onDismissRequest = {}) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(320.dp).testTag("edge-list")) {
                    items(60) { Text("Row $it", Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        } }
        compose.waitForIdle()
        val list = compose.onNodeWithTag("edge-list")
        // First exercise the bottom edge; returning toward the start must not
        // need an extra swipe to release a retained Android stretch effect.
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 500) }; compose.waitForIdle() }
        val distance = with(compose.density) { 96.dp.toPx() }
        repeat(40) {
            if (!listState.canScrollBackward) return@repeat
            val before = listState.firstVisibleItemIndex * 48f +
                with(compose.density) { listState.firstVisibleItemScrollOffset.toDp().value }
            list.performTouchInput {
                down(Offset(width / 2f, height * 0.25f))
                repeat(4) { moveBy(Offset(0f, distance / 4f), delayMillis = 80) }
                // Release at rest so a fling does not dismiss the sheet at the start.
                moveBy(Offset.Zero, delayMillis = 200)
                up()
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val after = listState.firstVisibleItemIndex * 48f +
                    with(compose.density) { listState.firstVisibleItemScrollOffset.toDp().value }
                assertTrue("Each downward drag must advance toward the actual start", after < before)
            }
        }
        compose.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
        val sheet = compose.onNodeWithTag("bottom-sheet-drag-handle")
        val before = sheet.fetchSemanticsNode().boundsInRoot.top
        list.performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, 25f), delayMillis = 32) }
        }
        compose.waitForIdle()
        assertTrue("At the start the next drag must immediately move the sheet",
            sheet.fetchSemanticsNode().boundsInRoot.top > before + 20f)
        list.performTouchInput { moveBy(Offset.Zero, delayMillis = 200); up() }
    }
}
