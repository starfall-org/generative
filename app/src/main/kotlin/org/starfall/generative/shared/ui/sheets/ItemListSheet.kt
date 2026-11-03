package org.starfall.generative.shared.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.inputs.ExpandableSearchField
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.lists.ItemCard

/** A card that can be rendered by [ItemListSheet]. The sheet never accepts arbitrary row content. */
class ItemListSheetItem<T>(
    val key: Any,
    val value: T,
    val onClick: () -> Unit,
    val icon: @Composable () -> Unit,
    val content: @Composable () -> Unit,
    val actions: @Composable () -> Unit = {},
    val isGrid: Boolean = false,
    val selected: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val modifier: Modifier = Modifier,
    val color: Color? = null,
    val border: BorderStroke? = null
)

class ItemListSheetSection<T>(
    val key: Any,
    val title: String,
    val items: List<ItemListSheetItem<T>>
)

/**
 * Headerless sheet for collections of [ItemCard] entries.
 *
 * Search and filter controls live inside the sheet, while filtering and feature actions remain
 * owned by the caller. Stable keys are required so list updates retain scroll and item identity.
 */
@Composable
fun <T> ItemListSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    items: List<ItemListSheetItem<T>> = emptyList(),
    sections: List<ItemListSheetSection<T>> = emptyList(),
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = stringResource(R.string.shared_search),
    filterContent: (@Composable RowScope.() -> Unit)? = null,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    emptyMessage: String? = null,
    footer: (@Composable () -> Unit)? = null,
    contentBehindNavigationBar: Boolean = false,
    fixedHeightFraction: Float? = null,
    expandOnOpen: Boolean = false
) {
    var searchExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val hasItems = items.isNotEmpty() || sections.any { it.items.isNotEmpty() }

    BottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        contentBehindNavigationBar = contentBehindNavigationBar,
        fixedHeightFraction = fixedHeightFraction,
        expandOnOpen = expandOnOpen
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            if (onSearchQueryChange != null || filterContent != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onSearchQueryChange != null) {
                        ExpandableSearchField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            expanded = searchExpanded,
                            onExpandedChange = { searchExpanded = it },
                            placeholder = searchPlaceholder,
                            modifier = Modifier.weight(1f),
                            testTag = "item-list-sheet-search"
                        )
                    }
                    filterContent?.invoke(this)
                }
            }
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            }
            error?.let { message ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    onRetry?.let { retry ->
                        TextButton(onClick = retry) { Text(stringResource(R.string.common_retry)) }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!loading && error == null && !hasItems) {
                    item(key = "empty") {
                        Text(
                            emptyMessage ?: stringResource(R.string.shared_no_items),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                items(items, key = { Pair("root", it.key) }) { item -> ItemListCard(item) }
                sections.forEach { section ->
                    if (section.items.isNotEmpty()) {
                        item(key = Pair("section-header", section.key)) {
                            Text(
                                section.title,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(section.items, key = { Triple("section-item", section.key, it.key) }) { item -> ItemListCard(item) }
                    }
                }
            }
            footer?.invoke()
        }
    }
}

@Composable
private fun <T> ItemListCard(item: ItemListSheetItem<T>) {
    ItemCard(
        isGrid = item.isGrid,
        onClick = item.onClick,
        modifier = item.modifier,
        onLongClick = item.onLongClick,
        color = item.color ?: prominentContainerColor(opacityBoost = if (item.selected) 0.12f else 0f),
        border = item.border ?: prominentContainerBorder(if (item.selected) 1.5.dp else 1.dp),
        icon = item.icon,
        actions = item.actions,
        content = item.content
    )
}
