package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.ContextMenuAction
import org.starfall.generative.shared.ui.providers.ProviderCardMenu
import org.starfall.generative.shared.ui.providers.ProviderSearchFilter
import org.starfall.generative.shared.ui.screens.CatalogGrid
import org.starfall.generative.shared.ui.screens.CatalogKind
import org.starfall.generative.shared.ui.screens.CatalogScreen
import org.starfall.generative.shared.ui.providers.ProviderDisplayCard
import org.starfall.generative.shared.ui.providers.providerCardCell

data class ProviderCatalogEntry(val id: String, val name: String, val detail: String, val integrationId: String? = null)

private enum class CatalogSearchField { NAME, ID }
@Composable
fun RemoteProvidersScreen(catalog: List<ProviderCatalogEntry>, onBack: () -> Unit,
    header: (@Composable () -> Unit)? = null, onConnect: ((String) -> Unit)? = null,
    onAdd: (() -> Unit)? = null, title: String = stringResource(R.string.model_providers),
    status: (@Composable ColumnScope.() -> Unit)? = null,
    onImport: (() -> Unit)? = null, busy: Boolean = false) {
    var search by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var fields by remember { mutableStateOf(setOf(CatalogSearchField.NAME)) }
    val query = search.trim()
    val filtered = catalog.filter { item ->
        fields.any { field ->
            val value = when (field) {
                CatalogSearchField.NAME -> item.name
                CatalogSearchField.ID -> item.id
            }
            value.contains(query, ignoreCase = true)
        }
    }
    CatalogScreen(
        kind = CatalogKind.Providers,
        isEmpty = filtered.isEmpty(),
        hasSearchQuery = query.isNotEmpty(),
        title = title,
        onBack = onBack,
        searchQuery = search,
        onSearchQueryChange = { search = it },
        searchExpanded = searching,
        onSearchExpandedChange = {
            searching = it
            if (!it) filterOpen = false
        },
        searchPopupOpen = filterOpen,
        isGrid = grid,
        onGridChange = { grid = it },
        header = header,
        status = status,
        busy = busy,
        onAdd = onAdd,
        onImport = onImport,
        searchActions = {
            ProviderSearchFilter(
                fields = listOf(
                    CatalogSearchField.NAME to R.string.provider_search_provider_name,
                    CatalogSearchField.ID to R.string.server_provider_id
                ),
                defaultField = CatalogSearchField.NAME,
                searchFields = fields,
                searchFilterOpen = filterOpen,
                onSearchFieldsChange = { fields = it },
                onSearchFilterOpenChange = { filterOpen = it },
                onDismiss = { searching = false }
            )
        }
    ) { gridMode, padding ->
        CatalogGrid(kind = CatalogKind.Providers, isGrid = gridMode, contentPadding = padding) {
            items(filtered, key = { "provider_${it.id}" }) { item ->
                val connect = item.integrationId?.let { id -> onConnect?.let { action -> { action(id) } } }
                Box(Modifier.animateItem().providerCardCell(gridMode)) {
                    ProviderDisplayCard(
                        name = item.name, detail = item.detail, isGrid = gridMode,
                        modifier = Modifier.testTag("provider_${item.id}"),
                        onClick = connect.takeUnless { busy },
                        actions = {
                            if (connect != null) ProviderCardMenu(
                                actions = listOf(ContextMenuAction(
                                    stringResource(R.string.server_provider_link_account), connect, Icons.Outlined.Key
                                )),
                                enabled = !busy
                            )
                        }
                    )
                }
            }
        }
    }
}
