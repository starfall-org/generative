package org.starfall.generative.features.machines.shared.ui.terminal

import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.starfall.generative.R
import org.starfall.generative.shared.model.remote.TerminalInputMode
import org.starfall.generative.shared.terminal.RemoteTerminalBridge
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalAppBar(
    bridge: RemoteTerminalBridge?,
    onToggleSessions: () -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onInputModeChange: (TerminalInputMode) -> Unit,
    onToggleIme: () -> Unit,
    onToggleForceMouse: () -> Unit
) {
    var inputMenuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        colors = edgeToEdgeTopAppBarColors(),
        title = {},
        // The screen handles system insets for the entire terminal surface.
        windowInsets = WindowInsets(0, 0, 0, 0),
        navigationIcon = {
            Row {
                IconButton(onClick = onBack) {
                    SharedIcon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back))
                }
                IconButton(onClick = onDisconnect) {
                    SharedIcon(Icons.Outlined.LinkOff, stringResource(R.string.remote_server_terminal_disconnect_close))
                }
            }
        },
        actions = {
            val imeDescription = stringResource(R.string.remote_server_terminal_ime)
            IconToggleButton(
                checked = bridge?.imeEnabled == true,
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.semantics { contentDescription = imeDescription },
                onCheckedChange = { onToggleIme() },
                enabled = bridge?.inputMode?.usesLocalInput == true
            ) {
                Text(stringResource(R.string.remote_server_terminal_input_ime_short),
                    style = MaterialTheme.typography.labelMedium)
            }
            Box {
                val mode = bridge?.inputMode ?: TerminalInputMode.REMOTE
                val modeLabel = stringResource(if (mode == TerminalInputMode.LOCAL)
                    R.string.remote_server_terminal_input_local_short else R.string.remote_server_terminal_input_remote_short)
                IconButton(enabled = bridge != null, onClick = { inputMenuOpen = true }) {
                    val description = stringResource(R.string.remote_terminal_input_mode) + ": " + modeLabel
                    if (mode == TerminalInputMode.LOCAL) SharedIcon(Icons.Outlined.Edit, description)
                    else SharedIcon(Icons.Outlined.Computer, description)
                }
                DropdownMenu(expanded = inputMenuOpen, onDismissRequest = { inputMenuOpen = false }) {
                    TerminalInputMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(if (option == TerminalInputMode.LOCAL)
                                R.string.remote_server_terminal_input_local_short else R.string.remote_server_terminal_input_remote_short)) },
                            leadingIcon = {
                                if (option == mode) SharedIcon(Icons.Default.Check, null)
                            },
                            enabled = bridge != null,
                            onClick = { onInputModeChange(option); inputMenuOpen = false }
                        )
                    }
                }
            }
            IconToggleButton(
                checked = bridge?.forceMouse == true,
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                onCheckedChange = { onToggleForceMouse() },
                enabled = bridge != null
            ) {
                SharedIcon(R.drawable.ic_mouse_24, stringResource(R.string.remote_server_terminal_force_mouse))
            }
            IconButton(onClick = onToggleSessions) {
                SharedIcon(Icons.Outlined.FormatListBulleted, stringResource(R.string.remote_server_terminal_toggle_sessions))
            }
        }
    )
}
