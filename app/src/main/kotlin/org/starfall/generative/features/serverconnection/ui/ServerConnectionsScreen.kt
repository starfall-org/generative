package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate
import org.starfall.generative.shared.ui.screens.ListScreenTemplate
import org.starfall.generative.shared.ui.dialogs.AlertDialog

@Composable
fun ServerConnectionsScreen(service: ServerConnections, onBack: () -> Unit) {
    val profiles by service.profiles.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf<ServerConnectionProfile?>(null) }
    var deleting by remember { mutableStateOf<ServerConnectionProfile?>(null) }
    var revoking by remember { mutableStateOf<ServerConnectionProfile?>(null) }
    var result by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun launchOperation(failureMessage: Int = R.string.server_connection_failed, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            result = null
            try { block(); result = R.string.server_connection_success }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { result = failureMessage }
            finally { busy = false }
        }
    }
    if (editing) {
        ServerConnectionEditor(edited, busy, onBack = { editing = false }, onSave = { name, invitation ->
            launchOperation {
                if (edited == null) service.pair(name, invitation)
                else if (invitation.isNotBlank()) service.rePair(requireNotNull(edited), name, invitation)
                else service.rename(requireNotNull(edited), name)
                editing = false
            }
        }, error = result == R.string.server_connection_failed)
        return
    }
    var search by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    val matching = profiles.filter { it.name.contains(search, true) || it.endpoint.contains(search, true) }
    ListScreenTemplate(title = stringResource(R.string.server_connection), onBack = onBack,
        searchQuery = search, onSearchQueryChange = { search = it }, isGrid = grid, onGridChange = { grid = it },
        primaryActions = { IconButton(onClick = { edited = null; result = null; editing = true }) {
            SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            result?.let { Text(stringResource(it), Modifier.padding(12.dp)) }
            if (matching.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.server_connection_empty))
            } else FadeGridListContent(isGrid = grid, modifier = Modifier.fillMaxSize()) { gridMode ->
                LazyVerticalGrid(columns = GridCells.Fixed(if (gridMode) 2 else 1),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(matching, key = { it.id }) { profile ->
                        ItemCard(isGrid = gridMode, onClick = { edited = profile; result = null; editing = true },
                            icon = { SharedIcon(Icons.Outlined.Computer, null) }, content = {
                                Column { Text(profile.name); Text(profile.endpoint, style = MaterialTheme.typography.bodySmall)
                                    if (profile.selected) Text(stringResource(R.string.server_connection_selected), style = MaterialTheme.typography.labelSmall) }
                            }, actions = {
                                var menu by remember { mutableStateOf(false) }
                                IconButton(onClick = { menu = true }) { SharedIcon(Icons.Default.MoreVert, stringResource(R.string.common_more_options)) }
                                org.starfall.generative.shared.ui.menus.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.server_connection_test)) }, onClick = { menu = false; launchOperation { service.test(profile.id) } }, leadingIcon = { SharedIcon(Icons.Outlined.Refresh, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.server_connection_repair)) }, onClick = { menu = false; edited = profile; result = null; editing = true }, leadingIcon = { SharedIcon(Icons.Outlined.Edit, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.server_connection_select)) }, onClick = { menu = false; launchOperation { service.select(profile.id) } }, leadingIcon = { SharedIcon(Icons.Outlined.Check, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.server_connection_revoke)) }, onClick = { menu = false; revoking = profile }, leadingIcon = { SharedIcon(Icons.Outlined.LinkOff, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; deleting = profile }, leadingIcon = { SharedIcon(Icons.Outlined.Delete, null) })
                                }
                            })
                    }
                }
            }
        }
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
private fun ServerConnectionEditor(profile: ServerConnectionProfile?, busy: Boolean, onBack: () -> Unit,
    onSave: (String, String) -> Unit, error: Boolean) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    // Invitations contain one-time secrets and must not enter saved instance state.
    var invitation by remember { mutableStateOf("") }
    EditorScreenTemplate(title = stringResource(R.string.server_connection), onBack = onBack, actions = {
        IconButton(enabled = !busy && name.isNotBlank() && (profile != null || invitation.isNotBlank()),
            onClick = { onSave(name, invitation) }) { SharedIcon(Icons.Outlined.Check, stringResource(R.string.common_save)) }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error) Text(stringResource(R.string.server_connection_failed), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.server_connection_name)) }, singleLine = true)
            if (profile != null) {
                Text(profile.endpoint)
                Text(profile.pin, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(if (profile == null) R.string.server_connection_pair_hint else R.string.server_connection_repair_hint))
            OutlinedTextField(invitation, { invitation = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (profile == null) R.string.server_connection_invitation else R.string.server_connection_repair)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
    }
}
