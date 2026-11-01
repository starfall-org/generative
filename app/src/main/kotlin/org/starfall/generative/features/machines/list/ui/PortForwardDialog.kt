package org.starfall.generative.features.machines.list.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.features.machines.shared.ui.RemoteDropdownField
import org.starfall.generative.features.machines.shared.ui.RemoteErrorText
import org.starfall.generative.features.machines.shared.ui.RemoteTextField
import org.starfall.generative.features.machines.shared.ui.remoteLiteralKeyboardOptions
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.machines.MachineDialogTitle

private enum class ForwardType(val labelRes: Int) {
    LOCAL(R.string.remote_server_forward_local),
    REMOTE(R.string.remote_server_forward_remote),
    DYNAMIC(R.string.remote_server_forward_dynamic)
}

private fun String.splitEntries(): List<String> = split('\n', ',').map(String::trim).filter(String::isNotEmpty)

@Composable
internal fun PortForwardDialog(
    machine: RemoteMachine,
    activeForwards: List<String>,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onSaveAndStart: (RemoteMachine) -> Unit
) {
    val initial = machine.portForwarding
    var selectedType by rememberSaveable(machine.id) {
        mutableStateOf(when {
            initial.localEnabled -> ForwardType.LOCAL
            initial.remoteEnabled -> ForwardType.REMOTE
            initial.dynamicEnabled -> ForwardType.DYNAMIC
            else -> ForwardType.LOCAL
        })
    }
    var localText by rememberSaveable(machine.id) { mutableStateOf(initial.localRules.joinToString("\n")) }
    var remoteText by rememberSaveable(machine.id) { mutableStateOf(initial.remoteRules.joinToString("\n")) }
    var dynamicText by rememberSaveable(machine.id) { mutableStateOf(initial.dynamicPorts.joinToString(", ")) }
    var error by remember { mutableStateOf<String?>(null) }
    val dynamicPorts = remember(dynamicText) {
        dynamicText.splitEntries().mapNotNull(String::toIntOrNull)
    }
    val hasPorts = when (selectedType) {
        ForwardType.LOCAL -> localText.splitEntries().isNotEmpty()
        ForwardType.REMOTE -> remoteText.splitEntries().isNotEmpty()
        ForwardType.DYNAMIC -> dynamicPorts.isNotEmpty()
    }

    Dialog(
        onDismissRequest = onDismiss,
        title = { MachineDialogTitle(stringResource(R.string.remote_port_forward), machine) },
        content = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RemoteDropdownField(
                    label = stringResource(R.string.remote_server_forward_type),
                    options = ForwardType.entries.map { it to stringResource(it.labelRes) },
                    selected = selectedType,
                    onSelect = { selectedType = it; error = null }
                )
                when (selectedType) {
                    ForwardType.LOCAL -> {
                        RemoteTextField(
                            value = localText,
                            onValueChange = { localText = it; error = null },
                            label = stringResource(R.string.remote_server_forward_local_rules),
                            supporting = stringResource(R.string.remote_server_forward_local_hint),
                            keyboardOptions = remoteLiteralKeyboardOptions(),
                            minLines = 3
                        )
                    }
                    ForwardType.REMOTE -> {
                        RemoteTextField(
                            value = remoteText,
                            onValueChange = { remoteText = it; error = null },
                            label = stringResource(R.string.remote_server_forward_remote_rules),
                            supporting = stringResource(R.string.remote_server_forward_remote_hint),
                            keyboardOptions = remoteLiteralKeyboardOptions(),
                            minLines = 3
                        )
                    }
                    ForwardType.DYNAMIC -> {
                        RemoteTextField(
                            value = dynamicText,
                            onValueChange = { input ->
                                val text = input.filter { it.isDigit() || it == ',' || it == ' ' }
                                dynamicText = text
                                error = null
                            },
                            label = stringResource(R.string.remote_server_forward_dynamic_ports),
                            supporting = stringResource(R.string.remote_server_forward_dynamic_hint),
                            // Raw password-style IME disables composition but still offers commas for the list.
                            keyboardOptions = remoteLiteralKeyboardOptions()
                        )
                    }
                }
                if (activeForwards.isNotEmpty()) {
                    Text(
                        stringResource(R.string.remote_server_forward_active, activeForwards.joinToString(", ")),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onStop) {
                        Text(stringResource(R.string.remote_server_stop_port_forward))
                    }
                }
                error?.let { RemoteErrorText(it) }
            }
        },
        buttons = {
            Button(
                enabled = hasPorts,
                onClick = {
                    // Only the selected kind is started. Other kinds keep their saved rules
                    // for the next time this dialog opens, but are not active in this session.
                    val nextConfig = when (selectedType) {
                        ForwardType.LOCAL -> initial.copy(
                            localEnabled = true,
                            localRules = localText.splitEntries(),
                            remoteEnabled = false,
                            dynamicEnabled = false
                        )
                        ForwardType.REMOTE -> initial.copy(
                            localEnabled = false,
                            remoteEnabled = true,
                            remoteRules = remoteText.splitEntries(),
                            dynamicEnabled = false
                        )
                        ForwardType.DYNAMIC -> initial.copy(
                            localEnabled = false,
                            remoteEnabled = false,
                            dynamicEnabled = true,
                            dynamicPorts = dynamicPorts
                        )
                    }
                    runCatching { nextConfig.validated() }
                        .onSuccess { config -> onSaveAndStart(machine.copy(portForwarding = config)) }
                        .onFailure { failure -> error = failure.message }
                }
            ) {
                SharedIcon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.remote_start_forwarding))
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}
