package org.starfall.generative.features.mcp.list.ui

import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import org.starfall.generative.shared.ui.text.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.starfall.generative.extensions.launcher.Launcher
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.layout.FixedAppBarTitle
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import java.util.UUID
import org.starfall.generative.features.mcp.shared.ui.McpUnifiedCard
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.McpProtocol
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.mcpServerEnabled
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.lists.FadeGridListContent
import org.starfall.generative.shared.ui.lists.ListViewToggle
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.lists.moved
import org.starfall.generative.shared.ui.layout.windowHeightIn

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
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,topBar = {
            TopAppBar(
                colors = edgeToEdgeTopAppBarColors(),
            title = {
                FixedAppBarTitle(stringResource(R.string.app_menu_model_context_protocol))
            }, navigationIcon = { IconButton(onClick = onBack) { SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    ListViewToggle(isGridView, onToggle = { onToggleGridView?.invoke(!isGridView) })
                    IconButton(onClick = {
                        onOpenEditor(McpInfo(id = UUID.randomUUID().toString(), name = "", protocol = McpProtocol.STREAMABLE_HTTP,
                            url = null, headers = emptyMap()), true)
                    }) { SharedIcon(Icons.Default.Add, stringResource(R.string.common_add)) }
                })
        }) { padding ->
            Box(Modifier.fillMaxSize()) {
                if (mcpServers.isEmpty()) Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SharedIcon(Icons.Outlined.Extension, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.no_mcp_servers), style = MaterialTheme.typography.titleMedium)
                    }
                } else FadeGridListContent(isGrid = isGridView, modifier = Modifier.fillMaxSize()) { gridMode ->
                    val gridState = rememberLazyGridState()
                    val dndState = Launcher.rememberState<String>()
                    var dragSnapshot by remember { mutableStateOf<List<McpInfo>?>(null) }
                    Launcher.Host(state = dndState, modifier = Modifier.fillMaxSize(), preview = { drag ->
                        orderedServers.firstOrNull { it.id == drag.item.data }?.let { server ->
                            McpUnifiedCard(server = server, isGrid = gridMode,
                                modifier = Modifier.testTag("dragged_mcp"),
                                toolCount = (toolsCache[server.id] ?: server.cachedTools)?.size,
                                toolError = toolErrors[server.id], loading = server.id in toolsLoading,
                                isEnabled = mcpServerEnabled(toolSettings, server.id),
                                onToggleEnabled = {}, managedSettings = toolSettings,
                                onToolErrorClick = {}, onEdit = {}, onDelete = {})
                        }
                    }) {
                    LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(if (gridMode) 2 else 1),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = scrollableScaffoldPadding(padding, horizontal = 16.dp, top = 8.dp, bottom = 8.dp),
                        modifier = Modifier.fillMaxSize().autoScroll(dndState, gridState)) {
                        itemsIndexed(orderedServers, key = { _, item -> item.id }) { _, server ->
                            McpUnifiedCard(server = server, isGrid = gridMode,
                                    modifier = Modifier.animateItem()
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
                                            onDrop = { dragSnapshot != orderedServers }),
                                    toolCount = (toolsCache[server.id] ?: server.cachedTools)?.size, toolError = toolErrors[server.id], loading = server.id in toolsLoading,
                                    isEnabled = mcpServerEnabled(toolSettings, server.id),
                                    onToggleEnabled = { onSetMcpEnabled(server.id, !mcpServerEnabled(toolSettings, server.id)) },
                                    managedSettings = toolSettings,
                                    onToolErrorClick = { selectedToolError = server.name to it },
                                    onEdit = { onOpenEditor(server, false) }, onDelete = { deletingServerId = server.id })
                        }
                    }
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
                Box(Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.6f).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(error, style = MaterialTheme.typography.bodySmall) }
                }
            }, confirmButton = { TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(localizedResources.getString(R.string.mcp_tool_discovery_error_clipboard), error))
            }) { Text(stringResource(R.string.common_copy)) } },
            dismissButton = { TextButton(onClick = { selectedToolError = null }) { Text(stringResource(R.string.common_close)) } })
    }
}
