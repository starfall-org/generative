package org.starfall.generative.features.mcp.list.ui

import org.starfall.generative.shared.ui.layout.fadingVerticalScroll
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import org.starfall.generative.shared.ui.text.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.starfall.generative.extensions.launcher.Launcher
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import java.util.UUID
import org.starfall.generative.shared.ui.mcp.McpUnifiedCard
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.McpProtocol
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.isWebSearchProvider
import org.starfall.generative.shared.model.mcpServerEnabled
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.lists.moved
import org.starfall.generative.shared.ui.layout.windowHeightIn
import org.starfall.generative.shared.ui.screens.CatalogGrid
import org.starfall.generative.shared.ui.screens.CatalogKind
import org.starfall.generative.shared.ui.screens.CatalogScreen
import androidx.compose.runtime.saveable.rememberSaveable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    mcpServers: List<McpInfo>, isGridView: Boolean = false, onToggleGridView: ((Boolean) -> Unit)? = null,
    toolsCache: Map<String, List<ToolDefinition>>, toolErrors: Map<String, String>, toolsLoading: Set<String>,
    toolSettings: ToolSettings, onSetToolEnabled: (String, String, Boolean) -> Unit,
    onSetMcpEnabled: (String, Boolean) -> Unit,
    onDeleteMcpServer: (String) -> Unit, onReorderMcpServers: (List<String>) -> Unit,
    onOpenEditor: (McpInfo, Boolean) -> Unit, onBack: () -> Unit
) = with(Launcher) {
    var deletingServerId by remember { mutableStateOf<String?>(null) }
    var selectedToolError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var orderedServers by remember(mcpServers) { mutableStateOf(mcpServers) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val matchingServers = orderedServers.filter { server ->
        searchQuery.isBlank() || listOf(server.name, server.url.orEmpty()).any {
            it.contains(searchQuery.trim(), ignoreCase = true)
        }
    }
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val card: @Composable (McpInfo, Boolean, Modifier, Boolean) -> Unit = { server, gridMode, modifier, preview ->
        val enabled = mcpServerEnabled(toolSettings, server.id)
        val canToggle = !isWebSearchProvider(toolSettings, server.id)
        McpUnifiedCard(name = server.name, detail = server.url ?: stringResource(R.string.mcp_no_endpoint),
            icon = server.icon, isGrid = gridMode, modifier = modifier,
            toolCount = (toolsCache[server.id] ?: server.cachedTools)?.size, toolError = toolErrors[server.id],
            loading = server.id in toolsLoading, onToolErrorClick = { selectedToolError = server.name to it },
            onEdit = { onOpenEditor(server, false) }, onDelete = { deletingServerId = server.id },
            firstActionText = if (canToggle) stringResource(if (enabled) R.string.mcp_enabled else R.string.mcp_enable) else null,
            firstActionIcon = if (canToggle) (if (enabled) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked) else null,
            onFirstAction = if (canToggle) ({ onSetMcpEnabled(server.id, !enabled) }) else null, actionsEnabled = !preview)
    }
    CatalogScreen(
        kind = CatalogKind.Mcp,
        onBack = onBack,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        isGrid = isGridView,
        onGridChange = { onToggleGridView?.invoke(it) },
        isEmpty = matchingServers.isEmpty(),
        hasSearchQuery = mcpServers.isNotEmpty(),
        onAdd = {
            onOpenEditor(McpInfo(id = UUID.randomUUID().toString(), name = "", protocol = McpProtocol.STREAMABLE_HTTP,
                url = null, headers = emptyMap()), true)
        }
    ) { gridMode, padding ->
        val gridState = rememberLazyGridState()
        val dndState = Launcher.rememberState<String>()
        var dragSnapshot by remember { mutableStateOf<List<McpInfo>?>(null) }
        Launcher.Host(state = dndState, modifier = Modifier.fillMaxSize(), preview = { drag ->
            orderedServers.firstOrNull { it.id == drag.item.data }?.let { server ->
                card(server, gridMode, Modifier.testTag("dragged_mcp"), true)
            }
        }) {
            CatalogGrid(kind = CatalogKind.Mcp, state = gridState, isGrid = gridMode, contentPadding = padding,
                modifier = Modifier.autoScroll(dndState, gridState)) {
                items(matchingServers, key = { it.id }) { server ->
                    card(server, gridMode, Modifier.animateItem()
                                .dragSource(dndState, server.id, server.id,
                                    onDragStart = { dragSnapshot = orderedServers },
                                    onDragEnd = { outcome ->
                                        if (outcome.accepted) onReorderMcpServers(orderedServers.map { it.id })
                                        else dragSnapshot?.let { orderedServers = it }
                                        dragSnapshot = null
                                    })
                                .dropTarget(dndState, "slot:${server.id}",
                                    accepts = { it.key != server.id },
                                    onDragEnter = { dragged ->
                                        val from = orderedServers.indexOfFirst { it.id == dragged.item.data }
                                        val to = orderedServers.indexOfFirst { it.id == server.id }
                                        if (from >= 0 && to >= 0 && from != to)
                                            orderedServers = orderedServers.moved(from, to)
                                    }, onDrop = { true })
                                .dropTarget(dndState, "self:${server.id}",
                                    priority = 5,
                                    accepts = { it.key == server.id },
                                    onDrop = { dragSnapshot != orderedServers }), false)
                }
            }
        }
    }

    if (deletingServerId != null) AlertDialog(onDismissRequest = { deletingServerId = null },
        title = { Text(stringResource(R.string.dialog_delete_connector_title)) },
        text = { Text(stringResource(R.string.delete_mcp_server_message)) },
        confirmButton = { Button(onClick = { deletingServerId?.let(onDeleteMcpServer); deletingServerId = null },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deletingServerId = null }) { Text(stringResource(R.string.common_close)) } })
    selectedToolError?.let { (serverName, error) ->
        AlertDialog(onDismissRequest = { selectedToolError = null }, title = { Text(stringResource(R.string.mcp_tool_discovery_error_title, serverName)) },
            scrollableContent = false,
            text = {
                Box(Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.6f).fadingVerticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(error, style = MaterialTheme.typography.bodySmall) }
                }
            }, confirmButton = { TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(localizedResources.getString(R.string.mcp_tool_discovery_error_clipboard), error))
            }) { Text(stringResource(R.string.common_copy)) } },
            dismissButton = { TextButton(onClick = { selectedToolError = null }) { Text(stringResource(R.string.common_close)) } })
    }
}
