package org.starfall.generative.features.httptools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.CustomHttpTool
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.ui.lists.FadeGridListContent
import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.screens.ListScreenTemplate
import org.starfall.generative.shared.ui.dialogs.AlertDialog

@Composable
fun CustomHttpToolsScreen(
    tools: List<CustomHttpTool>,
    onOpenEditor: (CustomHttpTool) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomHttpTool?>(null) }
    val matching = tools.filter { it.name.contains(search, true) || it.description.contains(search, true) }
    ListScreenTemplate(
        title = stringResource(R.string.custom_http_tools), onBack = onBack,
        searchQuery = search, onSearchQueryChange = { search = it }, isGrid = grid, onGridChange = { grid = it },
        primaryActions = {
            IconButton(onClick = { onOpenEditor(CustomHttpTool()) }) {
                SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
            }
        }
    ) { padding ->
        if (matching.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(stringResource(if (tools.isEmpty()) R.string.http_tools_empty else R.string.content_list_no_results))
        } else FadeGridListContent(isGrid = grid, modifier = Modifier.fillMaxSize()) { gridMode ->
            LazyVerticalGrid(columns = GridCells.Fixed(if (gridMode) 2 else 1), contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(matching, key = { it.id }) { tool ->
                    var menu by remember { mutableStateOf(false) }
                    ItemCard(isGrid = gridMode, onClick = { onOpenEditor(tool) }, onLongClick = { menu = true },
                        icon = { SharedIcon(Icons.Outlined.Build, null) },
                        content = {
                            Column {
                                Text(tool.name, style = MaterialTheme.typography.titleMedium)
                                Text(tool.description, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(if (tool.enabled) R.string.http_tool_enabled else R.string.http_tool_disabled),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }, actions = {
                            IconButton(onClick = { menu = true }) { SharedIcon(Icons.Default.MoreVert, stringResource(R.string.common_more_options)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(if (tool.enabled) R.string.http_tool_disable else R.string.http_tool_enable)) },
                                    leadingIcon = { SharedIcon(Icons.Outlined.Check, null) },
                                    onClick = { menu = false; onSetEnabled(tool.id, !tool.enabled) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) },
                                    leadingIcon = { SharedIcon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onOpenEditor(tool) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) },
                                    leadingIcon = { SharedIcon(Icons.Outlined.Delete, null) }, onClick = { menu = false; deleting = tool })
                            }
                        })
                }
            }
        }
    }
    deleting?.let { tool ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.common_delete)) },
            text = { Text(tool.name) }, confirmButton = {
                TextButton(onClick = { onDelete(tool.id); deleting = null }) { Text(stringResource(R.string.common_delete)) }
            }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_close)) } })
    }
}
