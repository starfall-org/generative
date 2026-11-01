@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.starfall.generative.features.machines.list.ui

import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.saveable.rememberSaveable
import org.starfall.generative.shared.ui.lists.ReorderableCollection
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.ui.lists.FadeGridListContent
import org.starfall.generative.shared.ui.lists.ListViewToggle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.features.machines.list.model.MachineIpUpdateNotice
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.features.machines.shared.ui.RemoteIconTile
import org.starfall.generative.features.machines.shared.ui.RemoteMachinesTopBar
import org.starfall.generative.features.machines.shared.ui.RemoteStatusDot
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineType
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.machines.MachineToolsDialog

@Composable
fun RemoteMachinesScreen(
    viewModel: RemoteMachinesViewModel,
    onBack: () -> Unit,
    onOpenEditor: (RemoteMachine, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: (RemoteMachine) -> Unit,
    onOpenFiles: (RemoteMachine) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connectedIds by viewModel.connectedMachineIds.collectAsStateWithLifecycle()
    val forwardedPorts by viewModel.forwardedPorts.collectAsStateWithLifecycle()
    var isGrid by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RemoteMachine?>(null) }
    var portForwardMachine by remember { mutableStateOf<RemoteMachine?>(null) }
    var toolsMachine by remember { mutableStateOf<RemoteMachine?>(null) }
    var ipUpdateNotice by remember { mutableStateOf<MachineIpUpdateNotice?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localizedResources = LocalResources.current

    Scaffold(

        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            RemoteMachinesTopBar(
                title = stringResource(R.string.remote_ssh),
                onBack = onBack,
                actions = {
                    ListViewToggle(isGrid = isGrid, onToggle = { isGrid = !isGrid })
                    IconButton(onClick = { onOpenEditor(viewModel.newMachine(), true) }) {
                        SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
                    }
                    IconButton(onClick = onOpenSettings) {
                        SharedIcon(Icons.Outlined.Settings, stringResource(R.string.remote_ssh_google_oauth))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (state.machines.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SharedIcon(Icons.Outlined.Computer, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.remote_servers_empty), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.remote_server_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { onOpenEditor(viewModel.newMachine(), true) }) {
                        SharedIcon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.common_add))
                    }
                }
            } else {
                FadeGridListContent(isGrid = isGrid, modifier = Modifier.fillMaxSize()) { gridMode ->
                  ReorderableCollection(
                    items = state.machines,
                    key = { it.id },
                    columns = GridCells.Fixed(if (gridMode) 2 else 1),
                    contentPadding = scrollableScaffoldPadding(padding, horizontal = 16.dp, top = 8.dp, bottom = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                    onReorder = viewModel::reorderMachines,
                    preview = { machine ->
                        RemoteMachineCard(machine, gridMode, machine.id in connectedIds,
                            onDisconnect = {}, onStartPortForward = {}, onMachineTools = {},
                            onTerminal = {}, onFiles = {}, onEdit = {}, onDelete = {}, onUpdateIp = {})
                    }
                  ) { machine, dragModifier ->
                        RemoteMachineCard(
                            machine = machine,
                            modifier = dragModifier,
                            isGrid = gridMode,
                            connected = machine.id in connectedIds,
                            onDisconnect = { viewModel.disconnect(machine.id) },
                            onStartPortForward = { portForwardMachine = machine },
                            onMachineTools = { toolsMachine = machine },
                            onTerminal = { onOpenTerminal(machine) },
                            onFiles = { onOpenFiles(machine) },
                            onEdit = { onOpenEditor(machine, false) },
                            onDelete = { deleting = machine },
                            onUpdateIp = {
                                scope.launch {
                                    viewModel.refreshIp(machine).fold(
                                        onSuccess = { updated ->
                                            ipUpdateNotice = MachineIpUpdateNotice(machine.name, "${updated.host}:${updated.port}")
                                        },
                                        onFailure = { error ->
                                            ipUpdateNotice = MachineIpUpdateNotice(machine.name, error.message ?: error.toString())
                                        }
                                    )
                                }
                            }
                        )
                  }
                }
            }
        }
    }

    portForwardMachine?.let { machine ->
        PortForwardDialog(
            machine = machine,
            activeForwards = forwardedPorts[machine.id].orEmpty(),
            onDismiss = { portForwardMachine = null },
            onStop = {
                portForwardMachine = null
                viewModel.stopPortForward(machine.id) { result ->
                    val message = result.fold(
                        onSuccess = { localizedResources.getString(R.string.remote_server_port_forward_stopped) },
                        onFailure = { it.message ?: it.toString() }
                    )
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
            onSaveAndStart = { updated ->
                portForwardMachine = null
                viewModel.saveMachineAndStartPortForward(updated) { result ->
                    val message = result.fold(
                        onSuccess = { localizedResources.getString(R.string.remote_server_port_forward_started) },
                        onFailure = { it.message ?: it.toString() }
                    )
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    toolsMachine?.let { machine ->
        MachineToolsDialog(
            machine = machine,
            onDismiss = { toolsMachine = null },
            onSave = { updated ->
                viewModel.saveMachineTools(updated)
                toolsMachine = null
            }
        )
    }

    deleting?.let { machine ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.dialog_delete_server_title)) },
            text = { Text(stringResource(R.string.remote_server_delete_message, machine.name)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteMachine(machine.id)
                    deleting = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_close)) }
            }
        )
    }

    ipUpdateNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { ipUpdateNotice = null },
            title = { Text(stringResource(R.string.remote_server_update_ip_title, notice.machineName)) },
            text = { Text(notice.message) },
            confirmButton = {
                TextButton(onClick = { ipUpdateNotice = null }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }
}

@Composable
private fun RemoteMachineCard(
    machine: RemoteMachine,
    isGrid: Boolean,
    connected: Boolean,
    onDisconnect: () -> Unit,
    onStartPortForward: () -> Unit,
    onMachineTools: () -> Unit,
    onTerminal: () -> Unit,
    onFiles: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUpdateIp: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf(false) }
    val gcloud = machine.type == RemoteMachineType.GCLOUD_SHELL
    ItemCard(
        isGrid = isGrid,
        modifier = modifier,
        onClick = onEdit,
        icon = {
            RemoteIconTile(
                icon = if (gcloud) Icons.Outlined.Cloud else Icons.Outlined.Computer,
                containerColor = if (gcloud) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (gcloud) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
            )
        },
        actions = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (connected && !isGrid) {
                IconButton(onClick = onDisconnect) {
                    SharedIcon(Icons.Outlined.LinkOff, stringResource(R.string.remote_server_disconnect))
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    SharedIcon(Icons.Default.MoreVert, stringResource(R.string.common_more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_server_open_terminal)) },
                        leadingIcon = { SharedIcon(Icons.Outlined.Terminal, null) },
                        onClick = { menu = false; onTerminal() }
                    )
                    if (connected && isGrid) DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_server_disconnect)) },
                        leadingIcon = { SharedIcon(Icons.Outlined.LinkOff, null) },
                        onClick = { menu = false; onDisconnect() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_port_forward)) },
                        leadingIcon = { SharedIcon(Icons.Outlined.Hub, null) },
                        onClick = { menu = false; onStartPortForward() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_ai_tools)) },
                        leadingIcon = { SharedIcon(Icons.Outlined.Build, null) },
                        onClick = { menu = false; onMachineTools() }
                    )
                    if (gcloud) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remote_server_update_ip)) },
                            leadingIcon = { SharedIcon(Icons.Outlined.Refresh, null) },
                            onClick = { menu = false; onUpdateIp() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_files)) },
                        leadingIcon = { SharedIcon(Icons.Outlined.Folder, null) },
                        onClick = { menu = false; onFiles() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        leadingIcon = { SharedIcon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { SharedIcon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
          }
        },
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        machine.name.ifBlank { stringResource(R.string.remote_server_unnamed) },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    RemoteStatusDot(connected)
                }
                Text(
                    stringResource(if (gcloud) R.string.remote_server_type_gcloud_shell else R.string.remote_server_type_ssh),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${machine.username.ifBlank { "?" }}@${machine.displayHost}:${machine.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (connected) {
                    Text(
                        stringResource(R.string.remote_server_terminal_connected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    )
}
