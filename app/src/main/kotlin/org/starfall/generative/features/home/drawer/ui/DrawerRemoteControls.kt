package org.starfall.generative.features.home.drawer.ui

import org.starfall.generative.shared.ui.menus.ContextMenuAction
import org.starfall.generative.shared.ui.menus.ContextDropdownMenu
import org.starfall.generative.shared.ui.sheets.ItemListSheet
import org.starfall.generative.shared.ui.sheets.ItemListSheetItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.database.repositories.ServerConnectionProfile
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.remoteMachineEnabled
import org.starfall.generative.shared.model.selectedRemoteMachine
import org.starfall.generative.features.home.shared.ui.LocalToolControls
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.FluentUi
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.machines.MachineToolsDialog

@Composable
internal fun DrawerRemoteControls(
    onOpenRemoteMachines: () -> Unit,
    serverProfiles: List<ServerConnectionProfile> = emptyList(),
    remoteEnabled: Boolean = false,
    remoteConnectionId: String? = null,
    runtimeSelecting: Boolean = false,
    remoteError: Boolean = false,
    onReconnectServer: () -> Unit = {},
    onSelectRuntime: (String?) -> Unit = {},
    onOpenServerConnections: () -> Unit = {},
    onOpenTerminal: (RemoteMachine) -> Unit = {},
    onPortForward: (RemoteMachine) -> Unit = {},
    onSaveMachineTools: (RemoteMachine) -> Unit
) {
    val controls = LocalToolControls.current
    val machines = controls.machines
    val selectedMachine = selectedRemoteMachine(controls.settings, machines)
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var serverPickerOpen by rememberSaveable { mutableStateOf(false) }
    val selectedServer = serverProfiles.firstOrNull { remoteEnabled && it.id == remoteConnectionId }
    var machineMenu by remember { mutableStateOf(false) }
    var toolsMachine by remember { mutableStateOf<RemoteMachine?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("drawer_server_row"), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !runtimeSelecting) { serverPickerOpen = true }.padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("drawer_server_picker_trigger"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SharedIcon(Icons.Outlined.Computer, null, modifier = Modifier.size(21.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.drawer_runtime), style = MaterialTheme.typography.labelSmall)
                    Text(
                        if (!remoteEnabled) stringResource(R.string.drawer_runtime_local)
                        else selectedServer?.name ?: stringResource(R.string.server_remote_select),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (remoteEnabled && remoteError) Text(stringResource(R.string.server_connection_offline),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                SharedIcon(FluentUi.Regular.ChevronRight, null, modifier = Modifier.size(16.dp))
            }
            if (remoteEnabled && remoteError && selectedServer != null) {
                IconButton(onClick = onReconnectServer, enabled = !runtimeSelecting, modifier = Modifier.testTag("drawer_server_reconnect")) {
                    SharedIcon(FluentUi.Regular.ArrowClockwise, stringResource(R.string.server_connection_reconnect))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("drawer_remote_selected_machine"),
            verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f)
                    .testTag("drawer_machine_picker_trigger")
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { pickerOpen = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SharedIcon(if (selectedMachine?.type?.name == "GCLOUD_SHELL")
                    Icons.Outlined.Cloud else Icons.Outlined.Computer,
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.drawer_remote_access), style = MaterialTheme.typography.labelSmall)
                    Text(
                        selectedMachine?.let { it.name.ifBlank { it.displayHost } }
                            ?: stringResource(R.string.remote_select_server),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SharedIcon(FluentUi.Regular.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onOpenRemoteMachines) {
                SharedIcon(
                    FluentUi.Regular.Settings,
                    stringResource(R.string.tool_settings_accessibility, stringResource(R.string.drawer_remote_access))
                )
            }
            if (selectedMachine != null) {
                Box {
                    IconButton(onClick = { machineMenu = true },
                        modifier = Modifier.size(40.dp)
                            .testTag("drawer_machine_options_${selectedMachine.id}")) {
                        SharedIcon(Icons.Default.MoreVert,
                            stringResource(R.string.common_more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (machineMenu) {
                        val checked = remoteMachineEnabled(controls.settings, selectedMachine)
                        val toggleLabel = stringResource(
                            if (checked) R.string.remote_server_disable_action
                            else R.string.remote_server_enable_action
                        )
                        ContextDropdownMenu(
                            expanded = true,
                            actions = listOf(
                                ContextMenuAction(
                                    label = toggleLabel,
                                    onClick = { controls.setMachine(selectedMachine.id, !checked) },
                                    icon = Icons.Outlined.Computer,
                                    trailingContent = {
                                        Switch(
                                            checked = checked,
                                            onCheckedChange = { controls.setMachine(selectedMachine.id, it) },
                                            modifier = Modifier.testTag("drawer_machine_switch_" + selectedMachine.id)
                                                .semantics { contentDescription = toggleLabel }
                                        )
                                    }
                                ),
                                ContextMenuAction(
                                    label = stringResource(R.string.remote_server_open_terminal),
                                    onClick = { onOpenTerminal(selectedMachine) },
                                    iconRes = Icons.Outlined.Terminal
                                ),
                                ContextMenuAction(
                                    label = stringResource(R.string.remote_port_forward),
                                    onClick = { onPortForward(selectedMachine) },
                                    icon = Icons.Outlined.Hub
                                ),
                                ContextMenuAction(
                                    label = stringResource(R.string.common_tools),
                                    onClick = { toolsMachine = selectedMachine },
                                    icon = FluentUi.Regular.WrenchScrewdriver
                                ),
                                ContextMenuAction(
                                    label = stringResource(R.string.remote_server_unset_action),
                                    onClick = { controls.selectMachine(null) },
                                    icon = Icons.Outlined.LinkOff
                                )
                            ),
                            onDismissRequest = { machineMenu = false }
                        )
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        ItemListSheet(
            title = stringResource(R.string.remote_server_selection_title),
            onDismissRequest = { pickerOpen = false },
            items = machines.map { machine ->
                val selected = selectedMachine?.id == machine.id
                ItemListSheetItem(
                    key = machine.id,
                    value = machine,
                    onClick = {
                        controls.selectMachine(machine.id)
                        pickerOpen = false
                    },
                    selected = selected,
                    modifier = Modifier.testTag("drawer_machine_picker_item_${machine.id}"),
                    icon = {
                        SharedIcon(
                            if (machine.type.name == "GCLOUD_SHELL") Icons.Outlined.Cloud else Icons.Outlined.Computer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    },
                    actions = {
                        if (selected) SharedIcon(Icons.Outlined.Check, null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    },
                    content = {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(machine.name.ifBlank { stringResource(R.string.remote_server_unnamed) },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(machine.displayHost, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                )
            },
            listMaxHeight = 420.dp,
            emptyMessage = stringResource(R.string.remote_servers_none_configured)
        )
    }

    if (serverPickerOpen) {
        ItemListSheet(
            title = stringResource(R.string.drawer_runtime),
            onDismissRequest = { serverPickerOpen = false },
            items = listOf(
                ItemListSheetItem<String?>(
                    key = "local", value = null, selected = !remoteEnabled,
                    onClick = { serverPickerOpen = false; onSelectRuntime(null) },
                    modifier = Modifier.testTag("drawer_runtime_picker_local"),
                    icon = { SharedIcon(Icons.Outlined.Computer, null, modifier = Modifier.size(40.dp)) },
                    content = { Text(stringResource(R.string.drawer_runtime_local)) }
                )
            ) + serverProfiles.map { profile ->
                ItemListSheetItem<String?>(
                    key = "server:${profile.id}", value = profile.id, selected = selectedServer?.id == profile.id,
                    onClick = { serverPickerOpen = false; onSelectRuntime(profile.id) },
                    modifier = Modifier.testTag("drawer_runtime_picker_server_${profile.id}"),
                    icon = { SharedIcon(Icons.Outlined.Computer, null, modifier = Modifier.size(40.dp)) },
                    actions = {
                        if (profile.transport == "opencode") {
                            Text(stringResource(R.string.drawer_runtime_opencode),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    content = {
                        Column {
                            Text(profile.name)
                            Text(profile.endpoint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                )
            },
            listMaxHeight = 420.dp,
            footer = { TextButton(onClick = { serverPickerOpen = false; onOpenServerConnections() }) {
                Text(stringResource(R.string.server_connection))
            } }
        )
    }

    toolsMachine?.let { machine ->
        MachineToolsDialog(
            machine = machine,
            onDismiss = { toolsMachine = null },
            onSave = { updated ->
                onSaveMachineTools(updated)
                toolsMachine = null
            }
        )
    }
}
