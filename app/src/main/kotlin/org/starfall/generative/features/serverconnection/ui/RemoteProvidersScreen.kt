package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.providers.ProviderEmptyState
import org.starfall.generative.shared.ui.providers.ProviderDisplayCard
import org.starfall.generative.shared.ui.providers.providerCardCell

data class ProviderCatalogEntry(val id: String, val name: String, val detail: String, val integrationId: String? = null)

/** Generative Server and OpenCode use this screen. Local provider organization is not part of it. */
@Composable
fun RemoteProvidersScreen(catalog: List<ProviderCatalogEntry>, onBack: () -> Unit,
    header: (@Composable () -> Unit)? = null, onConnect: ((String) -> Unit)? = null,
    onAdd: (() -> Unit)? = null, title: String = stringResource(R.string.model_providers),
    status: (@Composable ColumnScope.() -> Unit)? = null,
    onImport: (() -> Unit)? = null, busy: Boolean = false) {
    var search by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    val filtered = catalog.filter { item ->
        item.name.contains(search.trim(), ignoreCase = true) || item.id.contains(search.trim(), ignoreCase = true)
    }
    RemoteCatalogLayout(items = filtered, itemKey = { it.id }, onBack = onBack,
        searchQuery = search, onSearchChange = { search = it }, isGrid = grid,
        onGridChange = { grid = it }, title = title, header = header, onAdd = onAdd, onImport = onImport, actionsEnabled = !busy, status = status,
        listTag = "provider_list", searchPlaceholder = stringResource(R.string.search_providers),
        emptyContent = { ProviderEmptyState(search.isNotBlank(), 0.dp) }) { item, gridMode, modifier ->
        Box(modifier.providerCardCell(gridMode)) {
            ProviderDisplayCard(item.name, item.detail, gridMode,
                onClick = item.integrationId?.let { id -> onConnect?.let { connect -> { connect(id) } } })
        }
    }
}
