package org.starfall.generative.features.home.chat.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.integrations.serverside.ServerProviderKey
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.inputs.SelectionDropdown

@Composable
internal fun RemoteKeyDialog(keys: List<ServerProviderKey>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var selected by remember(keys) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_remote_select_key)) },
        text = {
            SelectionDropdown(
                label = stringResource(R.string.server_remote_key),
                options = keys.map { it.id to it.label.ifBlank { it.id } },
                selected = selected,
                onSelect = { selected = it },
            )
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { onSelect(selected) }) { Text(stringResource(R.string.server_connection_select)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}
