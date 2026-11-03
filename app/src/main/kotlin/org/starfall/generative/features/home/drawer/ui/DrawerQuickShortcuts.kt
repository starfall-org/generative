package org.starfall.generative.features.home.drawer.ui

import org.starfall.generative.shared.ui.sheets.ContextMenuAction
import org.starfall.generative.shared.ui.sheets.ContextMenuSheet
import org.starfall.generative.shared.ui.sheets.ItemListSheet
import org.starfall.generative.shared.ui.sheets.ItemListSheetItem
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.remoteMachineEnabled
import org.starfall.generative.shared.model.selectedRemoteMachine
import org.starfall.generative.features.home.shared.ui.LocalToolControls
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.FluentUi
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.machines.MachineToolsDialog

/**
 * Single combined card at the top of the left drawer.
 * - Remote: single selected machine with a BottomSheet picker, title is "Remote Services"
 * - Media: opens the media grid sheet, title is "Media"
 * Both rows share the same [prominentContainerColor] block.
 */
@Composable
internal fun DrawerQuickShortcuts(
    onOpenMediaStorage: () -> Unit,
    onOpenRemoteMachines: () -> Unit,
    onOpenTerminal: (RemoteMachine) -> Unit = {},
    onPortForward: (RemoteMachine) -> Unit = {},
    onSaveMachineTools: (RemoteMachine) -> Unit,
    modifier: Modifier = Modifier,
    maxExpandedHeight: Dp = 280.dp
) {
    val controls = LocalToolControls.current
    val machines = controls.machines
    val selectedMachine = selectedRemoteMachine(controls.settings, machines)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var machineMenu by remember { mutableStateOf(false) }
    var toolsMachine by remember { mutableStateOf<RemoteMachine?>(null) }
    val subtitle = selectedMachine?.let { it.name.ifBlank { it.displayHost } }
        ?: stringResource(if (machines.isEmpty()) R.string.remote_servers_none_configured
            else R.string.remote_server_none_selected)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = prominentContainerColor(),
        border = prominentContainerBorder(),
        modifier = modifier.fillMaxWidth().heightIn(max = if (expanded) maxExpandedHeight else Dp.Unspecified)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .clickable { expanded = !expanded }
                    .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        SharedIcon(FluentUi.Regular.Desktop, null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.drawer_remote_services),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onOpenRemoteMachines, modifier = Modifier.size(36.dp)) {
                    SharedIcon(FluentUi.Regular.Settings,
                        stringResource(R.string.tool_settings_accessibility,
                            stringResource(R.string.drawer_remote_services_settings_label)),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                    SharedIcon(if (expanded) FluentUi.Regular.ChevronUp else FluentUi.Regular.ChevronDown,
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }
            AnimatedVisibility(visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 14.dp))
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
                            Text(
                                selectedMachine?.let { it.name.ifBlank { it.displayHost } }
                                    ?: stringResource(R.string.remote_select_server),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            SharedIcon(FluentUi.Regular.ChevronRight, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp))
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
                                    ContextMenuSheet(
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
                                                icon = Icons.Outlined.Terminal
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
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                modifier = Modifier.padding(horizontal = 14.dp))
            // Media row — opens the media grid sheet
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenMediaStorage)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        SharedIcon(
                            imageVector = FluentUi.Regular.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.drawer_media_short_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.heightIn(min = 2.dp))
                    Text(
                        text = stringResource(R.string.settings_storage_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                SharedIcon(
                    imageVector = FluentUi.Regular.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
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
