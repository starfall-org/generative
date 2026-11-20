package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.ui.mcp.McpUnifiedCard
import org.starfall.generative.shared.ui.screens.CatalogGrid
import org.starfall.generative.shared.ui.screens.CatalogKind
import org.starfall.generative.shared.ui.screens.CatalogScreen

data class McpCatalogEntry(val id: String, val name: String, val status: String)

/** Server catalog only; no local reorder state, repositories or tools cache. */
@Composable
fun RemoteMcpScreen(catalog: List<McpCatalogEntry>, busy: Boolean, onToggle: ((String, Boolean) -> Unit)? = null,
    onBack: () -> Unit, header: (@Composable () -> Unit)? = null, onAdd: (() -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null, status: (@Composable ColumnScope.() -> Unit)? = null,
    onImport: (() -> Unit)? = null) {
    var grid by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = catalog.filter { item ->
        item.name.contains(search.trim(), ignoreCase = true) || item.id.contains(search.trim(), ignoreCase = true)
    }
    CatalogScreen(
        kind = CatalogKind.Mcp,
        onBack = onBack,
        searchQuery = search,
        onSearchQueryChange = { search = it },
        isGrid = grid,
        onGridChange = { grid = it },
        isEmpty = filtered.isEmpty(),
        hasSearchQuery = catalog.isNotEmpty(),
        header = header,
        status = status,
        busy = busy,
        onAdd = onAdd,
        onImport = onImport
    ) { gridMode, padding ->
        CatalogGrid(kind = CatalogKind.Mcp, isGrid = gridMode, contentPadding = padding) {
            items(filtered, key = { it.id }) { item ->
                val connected = item.status == "connected"
                val detail = when (item.status) {
                    "connected" -> R.string.server_mcp_connected
                    "disabled" -> R.string.server_mcp_disabled
                    "pending" -> R.string.server_mcp_pending
                    "failed" -> R.string.server_mcp_failed
                    "needs_auth" -> R.string.server_mcp_needs_auth
                    else -> R.string.server_mcp_unknown
                }
                McpUnifiedCard(name = item.name, detail = stringResource(detail), isGrid = gridMode, modifier = Modifier.animateItem(),
                    firstActionText = stringResource(if (connected) R.string.server_mcp_disconnect else R.string.server_mcp_connect),
                    firstActionIcon = if (connected) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked,
                    onFirstAction = onToggle?.let { toggle -> { toggle(item.id, !connected) } }, onDelete = onDelete?.let { callback -> { callback(item.id) } },
                    actionsEnabled = !busy)
            }
        }
    }
}
