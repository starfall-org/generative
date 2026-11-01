package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.providers.list.ui.ProvidersScreen
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.model.provider.ProviderPlacement
import org.starfall.generative.shared.model.provider.ProviderRootOrderItem

/**
 * Device regressions for the real ProvidersScreen, not a synthetic drag/drop demo.
 *
 * A long press begins several screens below the top. The same finger stays down
 * while the LazyVerticalGrid auto-scrolls upwards and its source is recycled.
 * The first row must yield before release; a drop near the top must never be
 * interpreted as a request to append the source to the end of the root grid.
 */
@RunWith(AndroidJUnit4::class)
class ProviderUpwardAutoScrollDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val providers = (0 until 48).map { index ->
        ModelProvider(
            id = "p$index",
            name = "Provider $index",
            type = ModelApiType.OPENAI,
            baseUrl = "",
            sortOrder = index
        )
    }
    private val placements = CopyOnWriteArrayList<ProviderPlacement>()
    private val rootOrders = CopyOnWriteArrayList<List<ProviderRootOrderItem>>()
    private val folder = ProviderGroup("deep-folder", "Deep folder", sortOrder = 42)
    private var initialFirstCenter: Offset = Offset.Zero

    private fun show(grid: Boolean, withFolder: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                ProvidersScreen(
                    providers = providers,
                    providerGroups = if (withFolder) listOf(folder) else emptyList(),
                    isGridView = grid,
                    onOpenProviderEditor = { _, _ -> },
                    onDeleteProvider = {},
                    onReorderProviders = {},
                    onReorderRootItems = { rootOrders.add(it) },
                    onPlaceProviderImmediately = { placements.add(it) },
                    onBack = {}
                )
            }
        }
        compose.waitForIdle()
        // ActivityScenario can report idle before the first test frame has
        // registered its semantics tree on a device waking from Doze.
        compose.waitUntil(15_000) {
            runCatching { visible("provider_p0") }.getOrDefault(false)
        }
        initialFirstCenter = center("provider_p0")
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun visible(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun rootBounds(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot
    private fun local(point: Offset) = point - rootBounds().topLeft
    private fun center(tag: String): Offset = local(node(tag).fetchSemanticsNode().boundsInRoot.center)

    private fun upperEdge(): Offset {
        val list = node("provider_list").fetchSemanticsNode().boundsInRoot
        val inset = with(compose.density) { 5.dp.toPx() }
        return local(Offset(list.center.x, list.top + inset))
    }

    private fun begin(sourceTag: String) {
        val start = center(sourceTag)
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(750)
        compose.waitForIdle()
        assertTrue("The original drag must keep its shadow", visible(
            if (sourceTag.startsWith("provider_group_")) "dragged_group" else "dragged_provider"
        ))
    }

    /**
     * Keep the Compose clock manually driven during auto-scroll: a continuous
     * frame loop cannot become idle with autoAdvance enabled. Do not use a
     * performScrollToIndex() shortcut while the drag is active.
     */
    private fun scrollUpWhileHolding(sourceTag: String) {
        compose.mainClock.autoAdvance = false
        begin(sourceTag)
        val edge = upperEdge()
        compose.onRoot().performTouchInput { moveTo(edge) }
        // At 640dp/s, this traverses the 48-cell fixture on normal devices.
        var foundHead = false
        for (frame in 0 until 240) {
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            if (visible("provider_p0")) {
                foundHead = true
                break
            }
        }
        assertTrue("The held drag must auto-scroll all the way to the first row", foundHead)
        // First visible is not necessarily scroll offset zero; keep holding until
        // the first row has reached the top of the lazy grid.
        compose.mainClock.advanceTimeBy(3_500)
        compose.waitForIdle()
        assertTrue(visible("provider_p0"))
        assertTrue("The drag must survive scrolling off its original source", visible(
            if (sourceTag.startsWith("provider_group_")) "dragged_group" else "dragged_provider"
        ))
    }

    private fun reachFirstRowAndHover(sourceTag: String) {
        scrollUpWhileHolding(sourceTag)
        val firstAfter = center("provider_p0")
        val minimumDisplacement = with(compose.density) { 24.dp.toPx() }
        assertTrue(
            "The first item must yield while the finger stays at the scroll edge: initial=$initialFirstCenter after=$firstAfter",
            (firstAfter - initialFirstCenter).getDistance() > minimumDisplacement
        )
        assertTrue("Hover must not persist anything", placements.isEmpty() && rootOrders.isEmpty())
    }

    private fun finish() {
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse("The drag shadow must disappear after drop", visible("dragged_provider") || visible("dragged_group"))
    }

    private fun expectedFront(source: String): List<String> =
        providers.map { it.id }.toMutableList().apply {
            remove(source)
            add(0, source)
        }

    private fun assertProviderFirst(id: String) {
        assertEquals("The drop must commit exactly once", 1, placements.size)
        val root = placements.single().rootOrder.map { it.id }
        assertEquals(expectedFront(id), root)
        assertEquals("No provider may be lost or duplicated", 48, root.toSet().size)
        assertNull(placements.single().groupId)
    }

    @Test
    fun gridDeepProviderAutoScrollsToTopAndDisplacesFirstCellBeforeDrop() {
        show(grid = true)
        node("provider_list").performScrollToIndex(34)
        compose.waitForIdle()
        try {
            reachFirstRowAndHover("provider_p34")
            finish()
            assertProviderFirst("p34")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun listDeepProviderAutoScrollsToTopWithoutJumpingToEnd() {
        show(grid = false)
        node("provider_list").performScrollToIndex(30)
        compose.waitForIdle()
        try {
            reachFirstRowAndHover("provider_p30")
            finish()
            assertProviderFirst("p30")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun overshootingAboveFirstCellMustNotAppendTheProviderToTheEnd() {
        show(grid = true)
        node("provider_list").performScrollToIndex(34)
        compose.waitForIdle()
        try {
            reachFirstRowAndHover("provider_p34")
            // Briefly hover another head item, then overshoot the first cell
            // into the empty top gutter. The final intent is the beginning,
            // never an append to the end of the grid.
            val otherHeadItem = center("provider_p1")
            compose.onRoot().performTouchInput { moveTo(otherHeadItem) }
            compose.mainClock.advanceTimeBy(300)
            val beyondFirst = upperEdge()
            compose.onRoot().performTouchInput { moveTo(beyondFirst) }
            compose.mainClock.advanceTimeBy(350)
            compose.waitForIdle()
            assertTrue("An edge overshoot must not write before release", placements.isEmpty())
            finish()
            assertProviderFirst("p34")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun holdingAtTopEdgeMakesFirstGridCellYieldWithoutAnExtraPointerMove() {
        show(grid = true)
        node("provider_list").performScrollToIndex(34)
        compose.waitForIdle()
        try {
            scrollUpWhileHolding("provider_p34")
            // After reaching the beginning, the held pointer stays in the top
            // edge zone instead of moving back over the first provider card.
            val first = center("provider_p0")
            val list = node("provider_list").fetchSemanticsNode().boundsInRoot
            assertTrue(
                "The first provider must already have yielded to the held drag at the top edge",
                first.x > list.center.x
            )
            assertTrue(placements.isEmpty())
            finish()
            assertProviderFirst("p34")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun cancellingAfterAutoScrollRestoresTheOriginalTopRowWithoutWriting() {
        show(grid = true)
        node("provider_list").performScrollToIndex(34)
        compose.waitForIdle()
        try {
            reachFirstRowAndHover("provider_p34")
            compose.onRoot().performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val restored = center("provider_p0")
            val tolerance = with(compose.density) { 5.dp.toPx() }
            assertTrue(
                "Cancel must restore the first card to its original slot: expected=$initialFirstCenter actual=$restored",
                (restored - initialFirstCenter).getDistance() < tolerance
            )
            assertTrue("Cancel must not persist a partial drag", placements.isEmpty() && rootOrders.isEmpty())
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun deepFolderAlsoDisplacesFirstRootCellWhenAutoScrollingUp() {
        show(grid = true, withFolder = true)
        node("provider_list").performScrollToIndex(42)
        compose.waitForIdle()
        try {
            reachFirstRowAndHover("provider_group_deep-folder")
            finish()
            assertTrue("A folder drag must not write a provider placement", placements.isEmpty())
            assertEquals("A folder drag must commit once", 1, rootOrders.size)
            val root = rootOrders.single()
            assertEquals("deep-folder", root.first().id)
            assertTrue(root.first().isGroup)
            assertEquals("p0", root[1].id)
            assertEquals(49, root.map { it.id }.toSet().size)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
