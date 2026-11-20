package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.lists.EntityGrid
import org.starfall.generative.shared.ui.lists.EntityCollectionViewport
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
        isGrid = isGrid.takeIf { status == null }, onGridChange = onGridChange, bottomPadding = 8.dp, header = header,
        primaryActions = {
            if (onAdd != null && status == null) IconButton(onClick = onAdd, enabled = actionsEnabled) {
                SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
            }
            if (onImport != null) IconButton(onClick = onImport, enabled = actionsEnabled) {
                SharedIcon(Icons.Outlined.UploadFile, stringResource(R.string.server_import_local))
            }
        }
    ) { padding ->
        if (status != null) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = status
            )
        } else {
            EntityCollectionViewport(
                isGrid = isGrid,
                isEmpty = items.isEmpty(),
                contentPadding = padding,
                emptyContent = emptyContent
            ) { gridMode ->
                EntityGrid(
                    isGrid = gridMode,
                    spacing = spacing,
                    contentPadding = padding,
                    modifier = Modifier.fillMaxSize().testTag(listTag)
                ) {
                    items(items, key = itemKey) { item -> card(item, gridMode, Modifier.animateItem()) }
                }
            }
        }
    }
}
