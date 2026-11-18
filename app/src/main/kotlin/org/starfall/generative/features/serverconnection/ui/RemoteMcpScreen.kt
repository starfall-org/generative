package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.ui.mcp.McpEmptyState
import org.starfall.generative.R
import org.starfall.generative.shared.ui.mcp.McpUnifiedCard
import org.starfall.generative.shared.ui.icons.Icons

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
    RemoteCatalogLayout(title = stringResource(R.string.mcp), items = filtered, itemKey = { it.id },
        searchQuery = search, onSearchChange = { search = it },
        isGrid = grid, onGridChange = { grid = it }, onBack = onBack, header = header,
        onAdd = onAdd, onImport = onImport, actionsEnabled = !busy, status = status, listTag = "mcp_list", spacing = 12.dp,
        emptyContent = { McpEmptyState(catalog.isNotEmpty()) }) { item, gridMode, modifier ->
        val connected = item.status == "connected"
        val status = when (item.status) {
            "connected" -> R.string.server_mcp_connected
            "disabled" -> R.string.server_mcp_disabled
            "pending" -> R.string.server_mcp_pending
            "failed" -> R.string.server_mcp_failed
            "needs_auth" -> R.string.server_mcp_needs_auth
            else -> R.string.server_mcp_unknown
        }
        McpUnifiedCard(name = item.name, detail = stringResource(status), isGrid = gridMode, modifier = modifier,
            firstActionText = stringResource(if (connected) R.string.server_mcp_disconnect else R.string.server_mcp_connect),
            firstActionImageVector = if (connected) Icons.Outlined.LinkOff else Icons.Outlined.Check,
            onFirstAction = onToggle?.let { toggle -> { toggle(item.id, !connected) } }, onDelete = onDelete?.let { callback -> { callback(item.id) } },
            actionsEnabled = !busy)
    }
}
