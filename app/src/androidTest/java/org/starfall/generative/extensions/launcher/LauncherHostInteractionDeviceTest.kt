package org.starfall.generative.extensions.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class LauncherHostInteractionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disposingGestureOwnerCancelsExactlyOnceWithoutLeakingShadow() {
        val state = LauncherDragDropState<String>()
        var mounted by mutableStateOf(true)
        var completions = 0
        compose.setContent {
            if (mounted) LauncherDragDropHost(state, Modifier.fillMaxSize(), preview = {}) {
                Box(Modifier.size(100.dp).testTag("source").launcherDragSource(
                    state, "item", "item", onDragEnd = { completions++ }))
            }
        }
        val point = compose.onNodeWithTag("source").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(point - compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft) }
        compose.mainClock.advanceTimeBy(750)
        compose.runOnIdle { assertNotNull(state.session); mounted = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(state.session)
            assertNull(state.settlement)
            assertEquals(1, completions)
        }
    }

    @Test fun stationaryPointerDoesNotCascadeReordersWhenTargetsRelayout() {
        val state = LauncherDragDropState<String>()
        val first = Any()
        val second = Any()
        val area = Rect(0f, 0f, 100f, 100f)
        var hovers = 0
        state.putSource(LauncherSource(Any(), LauncherDragItem("item", "item"), 0, true,
            area, {}, {}))
        fun target(token: Any, name: String, bounds: Rect) = LauncherTarget(token, name, 0, true,
            bounds, { _: LauncherDragItem<String> -> true }, {}, {}, {}, { true })
        state.putTarget(target(first, "first", area))
        compose.setContent {
            LauncherHoverEffect(state, delayMillis = 250) { _, _ ->
                hovers++
                if (hovers == 1) {
                    state.targetBounds(first, Rect(200f, 0f, 300f, 100f))
                    state.putTarget(target(second, "second", area))
                }
            }
        }
        compose.runOnIdle { state.start(Offset(30f, 30f)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(1, hovers); state.move(Offset(70f, 30f)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(2, hovers); state.cancel() }
    }

    @Test fun edgeAutoScrollContinuesWhilePointerMovesAndStopsOnCancellation() {
        val state = LauncherDragDropState<String>()
        lateinit var scroll: ScrollState
        compose.setContent {
            scroll = rememberScrollState()
            Column(Modifier.size(200.dp).testTag("viewport")
                .launcherDragAutoScroll(state, scroll).verticalScroll(scroll)) {
                Box(Modifier.height(1600.dp).fillMaxWidth())
            }
        }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        state.putSource(LauncherSource(Any(), LauncherDragItem("item", "item"), 0, true,
            viewport, {}, {}))
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                state.start(Offset(viewport.center.x, viewport.center.y))
                state.move(Offset(viewport.center.x, viewport.bottom - 10f))
            }
            repeat(12) { step ->
                compose.runOnIdle { state.move(Offset(viewport.center.x, viewport.bottom - 10f - step % 3)) }
                compose.mainClock.advanceTimeBy(32)
            }
            compose.runOnIdle { assertTrue("moving the pointer must not reset the frame clock", scroll.value > 30) }
            compose.runOnIdle { state.cancel() }
            compose.mainClock.advanceTimeByFrame()
            val stopped = scroll.value
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertEquals(stopped, scroll.value) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
    @Test fun reorderingFirstVisibleLazyGridKeyDoesNotJumpToItsNewIndex() {
        val state = LauncherDragDropState<String>()
        var items by mutableStateOf((0 until 90).map { "item-$it" })
        lateinit var grid: LazyGridState
        compose.setContent {
            grid = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Fixed(1), state = grid,
                modifier = Modifier.size(240.dp).testTag("reorder-viewport")
                    .launcherDragAutoScroll(state, grid)
            ) {
                items(items, key = { it }) {
                    Box(Modifier.fillMaxWidth().height(48.dp))
                }
            }
        }
        compose.onNodeWithTag("reorder-viewport").performScrollToIndex(40)
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("reorder-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertEquals(40, grid.firstVisibleItemIndex)
            state.putSource(LauncherSource(
                Any(), LauncherDragItem("moving", "moving"), 0, true, viewport, {}, {}
            ))
            state.putTarget(LauncherTarget(
                Any(), "reorder", 10, true, viewport,
                { _: LauncherDragItem<String> -> true },
                onEnter = {
                    // Move the visible anchor key to the end of the list.
                    // The viewport must stay on item index 40, not follow that key.
                    items = items.toMutableList().apply { add(removeAt(40)) }
                }, onMove = {}, onExit = {}, onDrop = { true }
            ))
            assertTrue(state.start(viewport.center))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Reorder must preserve the viewport's scroll index", 40, grid.firstVisibleItemIndex)
            assertEquals("item-41", items[40])
            state.cancel()
        }
    }

    @Test fun horizontalRtlAutoScrollMovesForwardAtThePhysicalLeftEdge() {
        val state = LauncherDragDropState<String>()
        lateinit var scroll: ScrollState
        compose.setContent {
            scroll = rememberScrollState()
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Row(Modifier.size(200.dp).testTag("rtl-viewport")
                    .launcherDragAutoScroll(state, scroll, horizontal = true).horizontalScroll(scroll)) {
                    Box(Modifier.width(1600.dp).fillMaxHeight())
                }
            }
        }
        val viewport = compose.onNodeWithTag("rtl-viewport").fetchSemanticsNode().boundsInRoot
        state.putSource(LauncherSource(Any(), LauncherDragItem("item", "item"), 0, true,
            viewport, {}, {}))
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                state.start(Offset(viewport.center.x, viewport.center.y))
                state.move(Offset(viewport.left + 10f, viewport.center.y))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle {
                assertTrue("RTL forward scroll must advance from the physical left edge", scroll.value > 30)
                state.cancel()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
