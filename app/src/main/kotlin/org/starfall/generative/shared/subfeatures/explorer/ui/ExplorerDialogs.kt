package org.starfall.generative.shared.subfeatures.explorer.ui

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.text.OutlinedTextField

/** Create/rename dialog using the app's shared dialog and text field styles. */
@Composable
internal fun ExplorerNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.common_name)) },
                singleLine = true,
                modifier = Modifier.testTag("explorer-name")
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) },
                modifier = Modifier.testTag("explorer-confirm")
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("explorer-cancel")
            ) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** Deletion stays behind the shared confirmation dialog; the host decides what deletion means. */
@Composable
internal fun ExplorerDeleteDialog(
    item: ExplorerItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(
                if (item.directory) R.string.explorer_delete_folder_title
                else R.string.dialog_delete_file_title
            ))
        },
        text = { Text(stringResource(R.string.explorer_delete_confirm, item.name)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("explorer-confirm")
            ) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("explorer-cancel")
            ) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
