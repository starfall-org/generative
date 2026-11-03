package org.starfall.generative.shared.ui.lists

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.starfall.generative.extensions.launcher.LauncherDragDefaults
import org.starfall.generative.extensions.launcher.LauncherDragDropState
import org.starfall.generative.extensions.launcher.launcherDragAutoScroll
import org.starfall.generative.extensions.launcher.LauncherFolderGeometry

/**
 * Optional shared UI layout for demo and production folder consumers.
 *
 * Launcher owns the gesture, drop-slot geometry and edge-scroll behavior, but
 * does not render any UI. This is a host-side Compose lazy list/grid adapter.
 * The host owns the actual item drawing, membership, persistence,
 * folder window/header and gesture-specific accept/exit policies. `columns = null`
 * selects a list; any positive value selects a grid (including a one-column grid).
 * A caller may hoist its scroll state without adding its own scroll controller.
 */
@Composable
fun <Item : Any, Drag : Any> LauncherCollection(
    items: List<Item>,
    key: (Item) -> String,
    state: LauncherDragDropState<Drag>,
    modifier: Modifier = Modifier,
    columns: Int? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalSpacing: Dp = 10.dp,
    verticalSpacing: Dp = 10.dp,
    enabled: Boolean = true,
    gridState: LazyGridState? = null,
    listState: LazyListState? = null,
    autoScrollEdge: Dp = 48.dp,
    autoScrollSpeed: Dp = 640.dp,
    placementDurationMillis: Int? = null,
    content: @Composable (Item, Modifier, Rect?) -> Unit
) {
    require(columns == null || columns > 0) { "Grid columns must be positive" }
    require(placementDurationMillis == null || placementDurationMillis >= 0) {
        "Placement duration must be non-negative"
    }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val leftInset = with(density) { contentPadding.calculateLeftPadding(layoutDirection).toPx() }
    val topInset = with(density) { contentPadding.calculateTopPadding().toPx() }
    val rightInset = with(density) { contentPadding.calculateRightPadding(layoutDirection).toPx() }
    val placement = placementDurationMillis?.let { tween<IntOffset>(it) }

    fun slot(x: Float, y: Float, width: Float, height: Float): Rect? {
        if (viewport.width <= 0f || viewport.height <= 0f) return null
        // The item offset is the lazy layout's logical position, not the
        // animated drawing position. RTL offsets are already physical.
        return LauncherFolderGeometry.slot(viewport, x, y, width, height, leftInset, topInset)
    }

    if (columns == null) {
        val listScroll = listState ?: rememberLazyListState()
        LazyColumn(
            state = listScroll,
            modifier = modifier.onGloballyPositioned { viewport = it.boundsInWindow() }
                .then(if (enabled) Modifier.launcherDragAutoScroll(state, listScroll,
                    edge = autoScrollEdge, maxSpeed = autoScrollSpeed) else Modifier),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(verticalSpacing)
        ) {
            items(items, key = key) { item ->
                val itemKey = key(item)
                val logicalSlot by remember(listScroll, itemKey, viewport, leftInset, topInset, rightInset) {
                    derivedStateOf {
                        listScroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }?.let {
                            slot(0f, it.offset.toFloat(),
                                (viewport.width - leftInset - rightInset).coerceAtLeast(0f), it.size.toFloat())
                        }
                    }
                }
                val animation = if (placement != null) Modifier.animateItem(placementSpec = placement)
                    else Modifier.animateItem()
                content(item, animation.fillMaxWidth(), logicalSlot)
            }
        }
    } else {
        val gridScroll = gridState ?: rememberLazyGridState()
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridScroll,
            modifier = modifier.onGloballyPositioned { viewport = it.boundsInWindow() }
                .then(if (enabled) Modifier.launcherDragAutoScroll(state, gridScroll,
                    edge = autoScrollEdge, maxSpeed = autoScrollSpeed) else Modifier),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing)
        ) {
            items(items, key = key) { item ->
                val itemKey = key(item)
                val logicalSlot by remember(gridScroll, itemKey, viewport, leftInset, topInset) {
                    derivedStateOf {
                        gridScroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }?.let {
                            slot(it.offset.x.toFloat(), it.offset.y.toFloat(),
                                it.size.width.toFloat(), it.size.height.toFloat())
                        }
                    }
                }
                val animation = if (placement != null) Modifier.animateItem(placementSpec = placement)
                    else Modifier.animateItem()
                content(item, animation, logicalSlot)
            }
        }
    }
}
