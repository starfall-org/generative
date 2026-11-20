package org.starfall.generative.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.lists.EntityCollectionViewport
import org.starfall.generative.shared.ui.lists.EntityGrid
import org.starfall.generative.shared.ui.mcp.McpEmptyState
import org.starfall.generative.shared.ui.providers.ProviderEmptyState

internal enum class CatalogKind(val title: Int, val listTag: String) {
    Providers(R.string.model_providers, "provider_list"),
    Mcp(R.string.mcp, "mcp_list")
}

/** Presentation shared by local and server catalogs. The caller owns rows, state, and operations. */
@Composable
internal fun CatalogScreen(
    kind: CatalogKind,
    onBack: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isGrid: Boolean,
    onGridChange: (Boolean) -> Unit,
    isEmpty: Boolean,
    hasSearchQuery: Boolean,
    onAdd: (() -> Unit)? = null,
    onAddFolder: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    busy: Boolean = false,
    status: (@Composable ColumnScope.() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    title: String = stringResource(kind.title),
    handleBack: Boolean = true,
    modifier: Modifier = Modifier,
    appBarModifier: Modifier = Modifier,
    searchExpanded: Boolean? = null,
    onSearchExpandedChange: (Boolean) -> Unit = {},
    searchPopupOpen: Boolean = false,
    searchActions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Boolean, PaddingValues) -> Unit
) {
    val providers = kind == CatalogKind.Providers
    ListScreenTemplate(
        title = title,
        onBack = onBack,
        handleBack = handleBack,
        modifier = modifier,
        searchQuery = searchQuery.takeIf { status == null },
        onSearchQueryChange = onSearchQueryChange,
        searchPlaceholder = stringResource(if (providers) R.string.search_providers else R.string.shared_search),
        searchExpanded = searchExpanded,
        onSearchExpandedChange = onSearchExpandedChange,
        searchPopupOpen = searchPopupOpen,
        searchTag = if (providers) "provider_search" else "list-screen-search",
        searchButtonTag = if (providers) "provider_search_expand" else "list-screen-search-button",
        searchClearButtonTag = if (providers) "provider_search_clear" else "list-screen-search-clear",
        searchActions = searchActions,
        isGrid = isGrid.takeIf { status == null },
        onGridChange = onGridChange,
        appBarModifier = appBarModifier,
        header = header,
        bottomPadding = 8.dp,
        primaryActions = {
            if (onAdd != null && status == null) {
                IconButton(onClick = onAdd, enabled = !busy,
                    modifier = if (providers) Modifier.testTag("add_provider") else Modifier) {
                    SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
                }
            }
            if (onAddFolder != null) {
                IconButton(onClick = onAddFolder, enabled = !busy, modifier = Modifier.testTag("add_provider_group")) {
                    SharedIcon(Icons.Outlined.CreateNewFolder, stringResource(R.string.common_add))
                }
            }
            if (onImport != null) {
                IconButton(onClick = onImport, enabled = !busy) {
                    SharedIcon(Icons.Outlined.UploadFile, stringResource(R.string.server_import_local))
                }
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
            Box(Modifier.fillMaxSize()) {
                EntityCollectionViewport(
                    isGrid = isGrid,
                    isEmpty = isEmpty,
                    contentPadding = padding,
                    emptyContent = {
                        when (kind) {
                            CatalogKind.Providers -> ProviderEmptyState(hasSearchQuery, 0.dp)
                            CatalogKind.Mcp -> McpEmptyState(hasSearchQuery)
                        }
                    }
                ) { gridMode -> content(gridMode, padding) }
            }
        }
    }
}

@Composable
internal fun CatalogGrid(
    kind: CatalogKind,
    isGrid: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit
) = EntityGrid(
    isGrid = isGrid,
    contentPadding = contentPadding,
    modifier = Modifier.fillMaxSize().then(modifier).testTag(kind.listTag),
    state = state,
    spacing = if (kind == CatalogKind.Mcp) 12.dp else 0.dp,
    content = content
)
