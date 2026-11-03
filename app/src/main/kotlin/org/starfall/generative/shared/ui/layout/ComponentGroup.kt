package org.starfall.generative.shared.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.HorizontalDivider

/** A feature-owned child rendered with the group's calculated position and shape. */
class ComponentGroupSlot(
    val key: Any,
    val content: @Composable (modifier: Modifier, shape: Shape) -> Unit
)

/**
 * Shared grouped layout for menus and settings. Item shape, spacing, and optional header layout
 * are owned here; the slot content remains feature-specific.
 */
@Composable
fun ComponentGroup(
    items: List<ComponentGroupSlot>,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    description: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    itemSpacing: androidx.compose.ui.unit.Dp = 2.dp,
    showSeparators: Boolean = false
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
        if (title != null || description != null || actions != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    title?.invoke()
                    description?.invoke()
                }
                actions?.invoke(this)
            }
        }
        items.forEachIndexed { index, item ->
            if (showSeparators && index > 0) HorizontalDivider()
            val shape = componentGroupItemShape(index, items.size)
            key(item.key) { item.content(Modifier.fillMaxWidth(), shape) }
        }
    }
}

/** Small helper for callers that already own a ColumnScope and need a grouped shape. */
@Composable
fun ComponentGroupItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier, Shape) -> Unit
) {
    content(modifier, componentGroupItemShape(index, count))
}
