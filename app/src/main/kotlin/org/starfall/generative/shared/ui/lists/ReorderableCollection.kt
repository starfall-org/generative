package org.starfall.generative.shared.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.starfall.generative.extensions.launcher.Launcher

/** Reorder user-managed collections with the same gesture/shadow as Providers and MCP.
 * Only an accepted, changed order is persisted. Other updates never reset an active drag.
 */
@Composable
fun <T : Any> ReorderableCollection(
    items: List<T>,
    key: (T) -> String,
    columns: GridCells,
    canReorder: (T) -> Boolean = { true },
    onReorder: (List<String>) -> Unit,
    preview: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 12.dp,
    content: @Composable LazyGridItemScope.(T, Modifier) -> Unit
) = with(Launcher) {
    val ids = items.map(key)
    val byId = items.associateBy(key)
    var orderedIds by remember(ids) { mutableStateOf(ids) }
    var beforeDrag by remember { mutableStateOf<List<String>?>(null) }
    var lastReorderTarget by remember { mutableStateOf<String?>(null) }
    val drag = rememberState<String>()
    val grid = rememberLazyGridState()

    fun moveItem(draggedId: String, targetId: String) {
        val from = orderedIds.indexOf(draggedId)
        val to = orderedIds.indexOf(targetId)
        if (from >= 0 && to >= 0 && from != to) {
            orderedIds = orderedIds.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    Host(drag, modifier, preview = { session ->
        byId[session.item.data]?.let { preview(it) }
    }) {
        FadingLazyVerticalGrid(
            state = grid,
            columns = columns,
            modifier = Modifier.fillMaxSize().autoScroll(drag, grid),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            itemsIndexed(orderedIds, key = { _, id -> id }) { _, id ->
                val item = byId[id]
                if (item != null) {
                    content(item, Modifier.animateItem()
                        .dragSource(drag, id, id, enabled = canReorder(item),
                            onDragStart = { beforeDrag = orderedIds; lastReorderTarget = null },
                            onDragEnd = { outcome ->
                                val initial = beforeDrag
                                if (outcome.accepted && initial != null && orderedIds != initial) {
                                    onReorder(orderedIds)
                                } else if (!outcome.accepted) {
                                    initial?.let { orderedIds = it }
                                }
                                beforeDrag = null
                                lastReorderTarget = null
                            })
                        .dropTarget(drag, "slot:$id", enabled = canReorder(item),
                            accepts = { it.key != id },
                            onDragEnter = { moving ->
                                if (lastReorderTarget != id) {
                                    moveItem(moving.item.data, id)
                                    lastReorderTarget = id
                                }
                            },
                            // A fast release may arrive before hover, but a previewed move must
                            // not be applied a second time after the target itself relayouts.
                            onDrop = { moving ->
                                if (lastReorderTarget != id) moveItem(moving.item.data, id)
                                true
                            })
                        .dropTarget(drag, "self:$id", enabled = canReorder(item),
                            priority = 5,
                            accepts = { it.key == id },
                            onDrop = { beforeDrag != orderedIds }))
                }
            }
        }
    }
}
