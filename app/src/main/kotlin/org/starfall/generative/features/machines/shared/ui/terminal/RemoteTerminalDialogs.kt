package org.starfall.generative.features.machines.shared.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.terminal.PendingRemoteSecretRequest
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

@Composable
internal fun RemoteTerminalSecretDialog(
    pending: PendingRemoteSecretRequest,
    passwordInput: String,
    onPasswordInputChange: (String) -> Unit,
    showPromptPassword: Boolean,
    onTogglePromptPassword: () -> Unit,
    onDismiss: () -> Unit
) {
    val request = pending.request
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (request.passphrase) R.string.remote_server_terminal_passphrase_title
                    else R.string.remote_server_terminal_password_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.remote_server_terminal_password_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = onPasswordInputChange,
                    label = {
                        Text(
                            when {
                                request.passphrase -> stringResource(R.string.remote_server_key_passphrase)
                                request.prompt.isBlank() -> stringResource(R.string.remote_server_password)
                                else -> request.prompt.trim()
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPromptPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onTogglePromptPassword) {
                            SharedIcon(
                                if (showPromptPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (showPromptPassword) R.string.remote_server_hide_password
                                    else R.string.remote_server_show_password
                                )
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            Button(
                enabled = passwordInput.isNotEmpty(),
                onClick = { pending.answer.complete(passwordInput) }
            ) {
                Text(stringResource(R.string.remote_server_terminal_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}
