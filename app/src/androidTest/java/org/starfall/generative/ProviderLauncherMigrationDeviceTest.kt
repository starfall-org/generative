package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** Full ProvidersScreen on a physical device; no database or network. */
@RunWith(AndroidJUnit4::class)
class ProviderLauncherMigrationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val writes = mutableListOf<ProviderPlacement>()
    private val rootWrites = mutableListOf<List<ProviderRootOrderItem>>()
    private val group = ProviderGroup("group-a", "Alpha folder", sortOrder = 12)
    private val other = ProviderGroup("group-b", "Beta folder", sortOrder = 13)

    private fun provider(id: String, folder: String? = null, index: Int = 0) =
        ModelProvider(id, id, ModelApiType.OPENAI, baseUrl = "", groupId = folder, sortOrder = index)

    private fun show(entries: List<ModelProvider>, groups: List<ProviderGroup> = emptyList(), grid: Boolean = true) {
        writes.clear()
        rootWrites.clear()
        compose.setContent {
            MaterialTheme {
                ProvidersScreen(
                    providers = entries, providerGroups = groups, isGridView = grid,
                    onOpenProviderEditor = { _, _ -> }, onDeleteProvider = {},
                    onReorderProviders = {}, onReorderRootItems = { rootWrites += it },
                    onPlaceProviderImmediately = { writes += it }, onBack = {}
                )
            }
        }
        compose.waitForIdle()
    }

    private fun found(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun center(tag: String): Offset = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.center -
        compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft

    private fun start(from: String) {
        compose.onRoot().performTouchInput { down(center(from)) }
        compose.mainClock.advanceTimeBy(750)
        compose.waitForIdle()
    }

    private fun hover(destination: Offset, delay: Long = 240) {
        compose.onRoot().performTouchInput { moveTo(destination) }
        compose.mainClock.advanceTimeBy(delay)
        compose.waitForIdle()
    }

    private fun release() {
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun cancel() {
        compose.onRoot().performTouchInput { cancel() }
        compose.waitForIdle()
    }

    private val rootProviders = listOf(provider("a", index = 0), provider("b", index = 1),
        provider("c", index = 2))
    private val members = listOf(provider("m1", "group-a", 0), provider("m2", "group-a", 1))

    @Test fun stationaryLongPressAtMiddleIndexDoesNotReorderOrScroll() {
        val entries = (0 until 48).map { provider("p" + it, index = it) }
        show(entries)
        compose.onNodeWithTag("provider_list").performScrollToIndex(22)
        compose.waitForIdle()
        val before = center("provider_p22")
        start("provider_p22")
        assertEquals("A stationary pickup must not scroll its list", before, center("provider_p22"))
        assertTrue(found("dragged_provider"))
        cancel()
        assertTrue(writes.isEmpty())
    }

    @Test fun deepGridReorderCommitsExactlyOnceAndKeepsTheCorrectSlot() {
        val entries = (0 until 48).map { provider("p" + it, index = it) }
        show(entries)
        compose.onNodeWithTag("provider_list").performScrollToIndex(44)
        compose.waitForIdle()
        start("provider_p44")
        assertTrue("Drag preview should track the picked provider", found("dragged_provider"))
        hover(center("provider_p46"))
        assertTrue("No write during a hover", writes.isEmpty())
        release()
        assertEquals(1, writes.size)
        val expected = entries.map { it.id }.toMutableList().apply { add(46, removeAt(44)) }
        assertEquals(expected, writes.single().rootOrder.map { it.id })
        assertTrue(found("provider_p44"))
    }

    @Test fun deepListReorderCommitsOnce() {
        val entries = (0 until 48).map { provider("p" + it, index = it) }
        show(entries, grid = false)
        compose.onNodeWithTag("provider_list").performScrollToIndex(45)
        compose.waitForIdle()
        start("provider_p45")
        hover(center("provider_p46"))
        release()
        assertEquals(1, writes.size)
        assertEquals("p45", writes.single().providerId)
    }

    @Test fun rootGridCanDropAtTheFirstSlotAfterAutoScrollingFromTheEnd() =
        rootCanDropAtTheFirstSlotAfterAutoScrollingFromTheEnd(grid = true)

    @Test fun rootListCanDropAtTheFirstSlotAfterAutoScrollingFromTheEnd() =
        rootCanDropAtTheFirstSlotAfterAutoScrollingFromTheEnd(grid = false)

    private fun rootCanDropAtTheFirstSlotAfterAutoScrollingFromTheEnd(grid: Boolean) {
        val entries = (0 until 48).map { provider("p$it", index = it) }
        show(entries, grid = grid)
        val list = compose.onNodeWithTag("provider_list", useUnmergedTree = true)
        list.performScrollToIndex(42)
        compose.waitForIdle()
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        val nearTop = Offset(
            bounds.left + bounds.width * 0.25f,
            bounds.top + 12f * compose.activity.resources.displayMetrics.density
        ) - rootOrigin

        compose.mainClock.autoAdvance = false
        try {
            start("provider_p42")
            compose.onRoot().performTouchInput { moveTo(nearTop) }
            var reachedTop = false
            for (frame in 0 until 200) {
                compose.mainClock.advanceTimeBy(80)
                compose.waitForIdle()
                if (found("provider_p0")) {
                    reachedTop = true
                    break
                }
            }
            assertTrue("Dragging toward the start must auto-scroll all the way to index zero", reachedTop)
            // Correcting the finger without leaving the top edge must not
            // cause LazyGrid's key anchoring to jump back to the source's old row.
            compose.onRoot().performTouchInput {
                moveTo(nearTop + Offset(0f, 8f * compose.activity.resources.displayMetrics.density))
            }
            compose.mainClock.advanceTimeBy(480)
            compose.waitForIdle()
            assertTrue("First item must remain visible during the final insertion", found("provider_p0"))
            assertTrue("The drag session must remain active", found("dragged_provider"))
            assertTrue("Hovering must not write before release", writes.isEmpty())
            compose.onRoot().performTouchInput { up() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("A top-edge release persists exactly once", 1, writes.size)
            val order = writes.single().rootOrder
            assertEquals("The dragged item must occupy the first root slot", "p42", order.first().id)
            assertEquals(entries.size, order.size)
            assertEquals(entries.size, order.map { it.id }.toSet().size)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun cancellationAfterHoverNeverWritesAndRestoresOriginalPosition() {
        show(rootProviders)
        val original = center("provider_a")
        start("provider_a")
        hover(center("provider_c"))
        cancel()
        assertTrue(writes.isEmpty())
        assertEquals(original, center("provider_a"))
    }

    @Test fun draggingProviderOverFolderJoinsOnlyOnRelease() {
        show(rootProviders + members, listOf(group))
        start("provider_c")
        hover(center("provider_group_group-a"))
        assertTrue("A folder hover must not persist", writes.isEmpty())
        release()
        assertEquals(1, writes.size)
        assertEquals("group-a", writes.single().groupId)
        assertFalse(found("provider_c"))
        assertFalse(found("provider_folder_screen_group-a"))
    }

    @Test fun draggingFolderAcrossProvidersOnlyChangesRootOrder() {
        show(rootProviders + members, listOf(group))
        start("provider_group_group-a")
        assertTrue(found("dragged_group"))
        hover(center("provider_a"))
        release()
        assertTrue(writes.isEmpty())
        assertEquals(1, rootWrites.size)
        assertEquals("group-a", rootWrites.single().first().id)
    }

    @Test fun reorderingTwoMembersInsideFolderPersistsTheirMembership() {
        show(rootProviders + members, listOf(group))
        compose.onNodeWithTag("provider_group_group-a", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        start("provider_folder_provider_m1")
        hover(center("provider_folder_provider_m2"))
        release()
        assertEquals(1, writes.size)
        assertEquals(listOf("m2", "m1"), writes.single().groupOrders.getValue("group-a"))
        assertTrue(found("provider_folder_screen_group-a"))
    }

    @Test fun listFolderAutoScrollStaysAtTopAfterSmallDownwardCorrection() =
        folderAutoScrollStaysAtTopAfterSmallDownwardCorrection(grid = false)

    @Test fun gridFolderAutoScrollStaysAtTopAfterSmallDownwardCorrection() =
        folderAutoScrollStaysAtTopAfterSmallDownwardCorrection(grid = true)

    @Test fun listFolderCanDropOntoFirstSlotAfterScrollingUpFromEnd() =
        folderAutoScrollStaysAtTopAfterSmallDownwardCorrection(grid = false, commit = true)

    @Test fun gridFolderCanDropOntoFirstSlotAfterScrollingUpFromEnd() =
        folderAutoScrollStaysAtTopAfterSmallDownwardCorrection(grid = true, commit = true)

    private fun folderAutoScrollStaysAtTopAfterSmallDownwardCorrection(
        grid: Boolean,
        commit: Boolean = false
    ) {
        val longFolder = (0 until 48).map { provider("m$it", "group-a", it) }
        show(longFolder, listOf(group), grid = grid)
        compose.onNodeWithTag("provider_group_group-a", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val folderGrid = compose.onNodeWithTag("provider_folder_grid_group-a", useUnmergedTree = true)
        folderGrid.performScrollToIndex(40)
        compose.waitForIdle()
        val gridBounds = folderGrid.fetchSemanticsNode().boundsInRoot
        val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        // The first quarter is inside the first column in both grid and list.
        val nearTop = Offset(gridBounds.left + gridBounds.width * 0.25f, gridBounds.top +
            10f * compose.activity.resources.displayMetrics.density) - rootOrigin

        compose.mainClock.autoAdvance = false
        try {
            start("provider_folder_provider_m41")
            compose.onRoot().performTouchInput { moveTo(nearTop) }
            var reachedTop = false
            for (frame in 0 until 200) {
                compose.mainClock.advanceTimeBy(80)
                compose.waitForIdle()
                if (found("provider_folder_provider_m0")) {
                    reachedTop = true
                    break
                }
            }
            assertTrue("Holding the drag near the folder's top must scroll up to the first item", reachedTop)

            // A small downward finger correction remains in the TOP edge zone.
            // Reordering the newly visible first row must not move the viewport
            // back towards the original dragged item's index near the end.
            val correction = Offset(0f, 8f * compose.activity.resources.displayMetrics.density)
            compose.onRoot().performTouchInput { moveTo(nearTop + correction) }
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            assertTrue("A tiny downward correction must not jump to the folder's end",
                found("provider_folder_provider_m0"))
            assertTrue("The active drag must survive the scroll and correction", found("dragged_provider"))
            assertTrue("Preview reorders must not write to storage", writes.isEmpty())
            if (commit) {
                compose.onRoot().performTouchInput { up() }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertEquals("A drop at the first slot should persist exactly once", 1, writes.size)
                val order = writes.single().groupOrders.getValue("group-a")
                assertEquals("No member must be lost", longFolder.size, order.size)
                assertEquals("No member must be duplicated", longFolder.size, order.toSet().size)
                assertEquals("The first item must yield to the dragged member", "m41", order.first())
            } else {
                compose.onRoot().performTouchInput { cancel() }
                compose.mainClock.advanceTimeByFrame()
                assertTrue("Cancelling the drag must not persist reorders", writes.isEmpty())
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun exitingFolderDuringDragPreservesTheShadowAndCanDropOntoRoot() {
        show(rootProviders + members, listOf(group))
        compose.onNodeWithTag("provider_group_group-a", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val grid = compose.onNodeWithTag("provider_folder_grid_group-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        start("provider_folder_provider_m1")
        val outside = Offset(grid.left + 40f, grid.top - 140f) -
            compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        hover(outside, 420)
        assertTrue("Shadow must remain while changing folder ownership", found("dragged_provider"))
        assertFalse("Folder must close after dragging beyond its grid", found("provider_folder_screen_group-a"))
        release()
        assertEquals(1, writes.size)
        assertNull(writes.single().groupId)
        assertTrue(found("provider_m1"))
    }

    @Test fun cancellationDuringFolderExitNeverPersistsTheEjectedPreview() {
        show(rootProviders + members, listOf(group))
        compose.onNodeWithTag("provider_group_group-a", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val grid = compose.onNodeWithTag("provider_folder_grid_group-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        start("provider_folder_provider_m1")
        val outside = Offset(grid.left + 40f, grid.top - 140f) -
            compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        hover(outside, 420)
        cancel()
        assertTrue(writes.isEmpty())
        assertFalse(found("provider_m1"))
        compose.onNodeWithTag("provider_group_group-a", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue(found("provider_folder_provider_m1"))
    }

    @Test fun folderBesideProviderNeverStealsTheOtherItemsDrag() {
        show(listOf(provider("left", index = 11)) + members, listOf(group))
        start("provider_left")
        assertTrue(found("dragged_provider"))
        assertFalse(found("dragged_group"))
        cancel()
    }

    @Test fun movingMemberBetweenFoldersCannotDuplicateOrLoseIt() {
        show(rootProviders + members + provider("n1", "group-b", 0), listOf(group, other))
        compose.onNodeWithTag("provider_group_group-a", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val grid = compose.onNodeWithTag("provider_folder_grid_group-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        start("provider_folder_provider_m1")
        hover(Offset(grid.left + 40f, grid.top - 140f) -
            compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft, 420)
        assertFalse(found("provider_folder_screen_group-a"))
        hover(center("provider_group_group-b"), 280)
        release()
        assertEquals(1, writes.size)
        val placement = writes.single()
        assertEquals("group-b", placement.groupId)
        assertEquals(listOf("m2"), placement.groupOrders.getValue("group-a"))
        assertEquals(listOf("n1", "m1"), placement.groupOrders.getValue("group-b"))
    }

    private fun openTestFolder() {
        show(rootProviders + members, listOf(group))
        compose.onNodeWithTag("provider_group_group-a", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue(found("provider_folder_screen_group-a"))
    }

    private fun folderEdgePoint(left: Boolean, insetDp: Float = 2f): Offset {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("provider_folder_grid_group-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val inset = insetDp * compose.activity.resources.displayMetrics.density
        return Offset(if (left) root.left + inset else root.right - inset, grid.center.y) -
            root.topLeft
    }

    private fun dismissSwipe(from: Offset, to: Offset) {
        compose.onRoot().performTouchInput {
            down(from)
            advanceEventTime(80)
            moveTo(to)
            advanceEventTime(80)
            up()
        }
        compose.waitForIdle()
        assertFalse("Folder should close after an intentional swipe", found("provider_folder_screen_group-a"))
        assertTrue("Dismissal must never persist a provider placement", writes.isEmpty())
    }

    @Test fun leftEdgeSwipeClosesFolderWithoutDragging() {
        openTestFolder()
        val from = folderEdgePoint(left = true)
        val distance = 100f * compose.activity.resources.displayMetrics.density
        dismissSwipe(from, from + Offset(distance, 0f))
    }

    @Test fun rightEdgeSwipeClosesFolderWithoutDragging() {
        openTestFolder()
        val from = folderEdgePoint(left = false)
        val distance = 100f * compose.activity.resources.displayMetrics.density
        dismissSwipe(from, from - Offset(distance, 0f))
    }

    @Test fun headerDownwardSwipeClosesFolder() {
        openTestFolder()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("provider_folder_header_gesture_group-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val distance = 112f * compose.activity.resources.displayMetrics.density
        val from = header.center - root.topLeft
        dismissSwipe(from, from + Offset(0f, distance))
    }

    @Test fun headerHorizontalSwipeClosesFolder() {
        openTestFolder()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("provider_folder_header_gesture_group-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val distance = 92f * compose.activity.resources.displayMetrics.density
        val from = header.center - root.topLeft
        dismissSwipe(from, from - Offset(distance, 0f))
    }

    @Test fun verticalSwipeInsideGridDoesNotDismissFolder() {
        openTestFolder()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("provider_folder_grid_group-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val from = grid.center - root.topLeft
        compose.onRoot().performTouchInput {
            down(from)
            advanceEventTime(80)
            moveTo(from - Offset(0f, 130f * compose.activity.resources.displayMetrics.density))
            up()
        }
        compose.waitForIdle()
        assertTrue("Ordinary vertical scrolling must keep the folder open",
            found("provider_folder_screen_group-a"))
        assertTrue(writes.isEmpty())
    }

    private fun dragMemberToSideAndAssertExit(left: Boolean) {
        openTestFolder()
        start("provider_folder_provider_m1")
        val atEdge = folderEdgePoint(left)
        val grid = compose.onNodeWithTag("provider_folder_grid_group-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Escape gesture must remain at the grid's vertical interior",
            atEdge.y + root.top > grid.top && atEdge.y + root.top < grid.bottom)
        hover(atEdge, 450)
        assertFalse("Dragging to a horizontal edge must eject the member",
            found("provider_folder_screen_group-a"))
        assertTrue("Closing the folder must not destroy the drag shadow", found("dragged_provider"))
        assertTrue("Escape preview must not persist until release", writes.isEmpty())
        release()
        assertEquals(1, writes.size)
        assertEquals("m1", writes.single().providerId)
        assertNull(writes.single().groupId)
        assertTrue(found("provider_m1"))
    }

    @Test fun draggingMemberToLeftEdgeExitsFolderAndCommitsOnRelease() {
        dragMemberToSideAndAssertExit(left = true)
    }

    @Test fun draggingMemberToRightEdgeExitsFolderAndCommitsOnRelease() {
        dragMemberToSideAndAssertExit(left = false)
    }

    private fun dragMemberNearSideWithoutExit(left: Boolean) {
        openTestFolder()
        start("provider_folder_provider_m1")
        // Side escape is 5dp, not the much wider swipe-to-dismiss region (56dp).
        // At 28dp from the edge the item must remain inside the open folder.
        hover(folderEdgePoint(left, insetDp = 28f), 450)
        assertTrue("Dragging outside the 5dp side band must keep the folder open",
            found("provider_folder_screen_group-a"))
        assertTrue(found("dragged_provider"))
        assertTrue(writes.isEmpty())
        cancel()
        assertTrue("Cancelling an internal folder drag must not persist", writes.isEmpty())
    }

    @Test fun draggingInsideLeftSideDoesNotAccidentallyExitFolder() {
        dragMemberNearSideWithoutExit(left = true)
    }

    @Test fun draggingInsideRightSideDoesNotAccidentallyExitFolder() {
        dragMemberNearSideWithoutExit(left = false)
    }
}
