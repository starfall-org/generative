package org.starfall.generative.features.serverconnection.ui

import org.starfall.generative.shared.ui.lists.FadingLazyVerticalGrid
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.database.repositories.ServerConnectionProfile
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.ui.lists.FadeGridListContent
import org.starfall.generative.shared.ui.screens.ListScreenTemplate
import org.starfall.generative.shared.ui.screens.ScreenDestination
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.model.RemoteMachineType

private class ConnectionEditorPage(
    val profile: ServerConnectionProfile? = null,
    val repairing: Boolean = false,
)

@Composable
fun ServerConnectionsScreen(service: ServerConnections, onProviders: (String) -> Unit,
    onMcp: (String) -> Unit, onBack: () -> Unit) {
    val profiles by service.profiles.collectAsState(initial = emptyList())
    val machineState = service.sshMachines?.collectAsState()
    val sshMachines = machineState?.value?.machines.orEmpty().filter { it.type == RemoteMachineType.SSH }
    var editorPage by remember { mutableStateOf<ConnectionEditorPage?>(null) }
    var deleting by remember { mutableStateOf<ServerConnectionProfile?>(null) }
    var revoking by remember { mutableStateOf<ServerConnectionProfile?>(null) }
    var testing by remember { mutableStateOf<ServerConnectionProfile?>(null) }
    var result by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun launchOperation(
        failureMessage: Int = R.string.server_connection_failed,
        successMessage: Int = R.string.server_connection_success,
        block: suspend () -> Unit,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            result = null
            try { block(); result = successMessage }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { result = failureMessage }
            finally { busy = false }
        }
    }
    ScreenDestination(page = editorPage, onClosed = { editorPage = null }) { page ->
        if (page == null) return@ScreenDestination
        ServerConnectionEditor(page.profile, sshMachines, busy, page.repairing, onBack = { editorPage = null },
            onSave = { draft ->
                val failure = if (draft.transport == "opencode") R.string.server_connection_opencode_failed
                    else R.string.server_connection_failed
                launchOperation(failure) {
                    service.saveConnection(draft, page.profile, page.repairing)
                    if (editorPage === page) editorPage = null
                }
            },
            error = result?.takeIf { it == R.string.server_connection_failed || it == R.string.server_connection_opencode_failed },
            catalogContent = {
                page.profile?.let { profile ->
                    ServerCatalogLink(
                        label = stringResource(R.string.server_connection_open_catalog, stringResource(R.string.model_providers)),
                        icon = Icons.Outlined.Hub,
                        enabled = !busy,
                        onClick = { onProviders(profile.id) },
                    )
                    ServerCatalogLink(
                        label = stringResource(R.string.server_connection_open_catalog, stringResource(R.string.mcp)),
                        icon = Icons.Outlined.Mcp,
                        enabled = !busy,
                        onClick = { onMcp(profile.id) },
                    )
                    ServerConnectionCatalogImport(service, profile, busy, onBusyChange = { busy = it })
                }
            }
        )
    }
    var search by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    val matching = profiles.filter { it.name.contains(search, true) || it.endpoint.contains(search, true) }
    ListScreenTemplate(title = stringResource(R.string.server_connection), onBack = onBack,
        searchQuery = search, onSearchQueryChange = { search = it }, isGrid = grid, onGridChange = { grid = it },
        primaryActions = { IconButton(onClick = { result = null; editorPage = ConnectionEditorPage() }) {
            SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (testing == null) result?.let { Text(stringResource(it), Modifier.padding(12.dp)) }
            if (matching.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.server_connection_empty))
            } else FadeGridListContent(isGrid = grid, modifier = Modifier.fillMaxSize()) { gridMode ->
                FadingLazyVerticalGrid(columns = GridCells.Fixed(if (gridMode) 2 else 1),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(matching, key = { it.id }) { profile ->
                        ItemCard(isGrid = gridMode, onClick = { result = null; editorPage = ConnectionEditorPage(profile) },
                            icon = { SharedIcon(Icons.Outlined.Computer, null) }, content = {
                                Column { Text(profile.name); Text("${profile.transport.uppercase()} · ${profile.endpoint}", style = MaterialTheme.typography.bodySmall)
                                    if (profile.selected) Text(stringResource(R.string.server_connection_selected), style = MaterialTheme.typography.labelSmall) }
                            }, actions = {
                                var menu by remember { mutableStateOf(false) }
                                IconButton(onClick = { menu = true }) { SharedIcon(Icons.Default.MoreVert, stringResource(R.string.common_more_options)) }
                                org.starfall.generative.shared.ui.menus.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.model_providers)) }, onClick = { menu = false; onProviders(profile.id) }, leadingIcon = { SharedIcon(Icons.Outlined.Computer, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.mcp)) }, onClick = { menu = false; onMcp(profile.id) }, leadingIcon = { SharedIcon(Icons.Outlined.Mcp, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.server_connection_test)) },
                                        enabled = !busy,
                                        onClick = {
                                            menu = false
                                            testing = profile
                                            launchOperation(R.string.server_connection_test_failed, R.string.server_connection_test_success) {
                                                service.test(profile.id)
                                            }
                                        },
                                        leadingIcon = { SharedIcon(Icons.Outlined.Refresh, null) },
                                    )
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.server_connection_repair)) }, onClick = { menu = false; result = null; editorPage = ConnectionEditorPage(profile, repairing = true) }, leadingIcon = { SharedIcon(Icons.Outlined.Edit, null) })
                                    if (profile.transport != "opencode") org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.server_connection_revoke)) }, onClick = { menu = false; revoking = profile }, leadingIcon = { SharedIcon(Icons.Outlined.LinkOff, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; deleting = profile }, leadingIcon = { SharedIcon(Icons.Outlined.Delete, null) })
                                }
                            })
                    }
                }
            }
        }
    }
    testing?.let { profile ->
        val close = {
            testing = null
            result = null
        }
        AlertDialog(
            onDismissRequest = { if (!busy) close() },
            title = { Text(stringResource(R.string.server_connection_test)) },
            text = {
                Text(stringResource(
                    if (busy) R.string.server_connection_testing else result ?: R.string.server_connection_test_failed,
                    profile.name,
                ))
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = close) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
    revoking?.let { profile -> AlertDialog(onDismissRequest = { revoking = null },
        title = { Text(stringResource(R.string.server_connection_revoke)) },
        text = { Text(stringResource(R.string.server_connection_revoke_confirm, profile.name)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { revoking = null; launchOperation(R.string.server_connection_revoke_failed) { service.revoke(profile.id) } }) {
            Text(stringResource(R.string.server_connection_revoke), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.common_close)) } }) }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.common_delete)) },
        text = { Text(stringResource(R.string.server_connection_delete_confirm, profile.name)) },
        confirmButton = { TextButton(onClick = { deleting = null; launchOperation { service.delete(profile.id) } }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_close)) } }) }
}

@Composable
private fun ServerCatalogLink(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SharedIcon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        SharedIcon(Icons.AutoMirrored.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
