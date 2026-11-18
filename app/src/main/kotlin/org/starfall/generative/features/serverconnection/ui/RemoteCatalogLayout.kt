package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.lists.EntityGrid
import org.starfall.generative.shared.ui.lists.FadeGridListContent
import org.starfall.generative.shared.ui.screens.ListScreenTemplate

/** Catalog presentation; the owner supplies the already filtered rows and all state/actions. */
@Composable
internal fun <T> RemoteCatalogLayout(
    title: String, items: List<T>, itemKey: (T) -> String,
    onBack: () -> Unit, searchQuery: String, onSearchChange: (String) -> Unit,
    isGrid: Boolean, onGridChange: (Boolean) -> Unit, listTag: String,
    searchPlaceholder: String = stringResource(R.string.shared_search),
    onAdd: (() -> Unit)? = null, onImport: (() -> Unit)? = null, actionsEnabled: Boolean = true,
    header: (@Composable () -> Unit)? = null, status: (@Composable ColumnScope.() -> Unit)? = null,
    spacing: Dp = 0.dp, emptyContent: @Composable () -> Unit,
    card: @Composable (T, Boolean, Modifier) -> Unit
) {
    ListScreenTemplate(
        title = title, onBack = onBack, searchQuery = searchQuery.takeIf { status == null },
        onSearchQueryChange = onSearchChange, searchPlaceholder = searchPlaceholder,
        isGrid = isGrid.takeIf { status == null }, onGridChange = onGridChange, bottomPadding = 8.dp,
        primaryActions = {
            if (onAdd != null && status == null) IconButton(onClick = onAdd, enabled = actionsEnabled) {
                SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
            }
            if (onImport != null) IconButton(onClick = onImport, enabled = actionsEnabled) {
                SharedIcon(Icons.Outlined.UploadFile, stringResource(R.string.server_import_local))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            if (header != null) Box(Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding())) { header() }
            val direction = LocalLayoutDirection.current
            val listPadding = if (header == null) padding else PaddingValues(
                start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding()
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (status != null) {
                    Column(Modifier.fillMaxSize().padding(listPadding), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center, content = status)
                } else if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(listPadding)) { emptyContent() }
                } else FadeGridListContent(isGrid = isGrid, modifier = Modifier.fillMaxSize()) { gridMode ->
                    EntityGrid(isGrid = gridMode, spacing = spacing, contentPadding = listPadding,
                        modifier = Modifier.fillMaxSize().testTag(listTag)) {
                        items(items, key = itemKey) { item -> card(item, gridMode, Modifier.animateItem()) }
                    }
                }
            }
        }
    }
}
