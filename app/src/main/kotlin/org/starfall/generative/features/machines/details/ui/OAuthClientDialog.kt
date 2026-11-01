package org.starfall.generative.features.machines.details.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.machines.shared.ui.RemoteDropdownField
import org.starfall.generative.features.machines.shared.ui.RemoteTextField
import org.starfall.generative.shared.model.GCloudOAuthClient
import org.starfall.generative.shared.model.GoogleOAuthClientType
import org.starfall.generative.shared.ui.dialogs.AlertDialog

/** Creates or edits a Google OAuth client used to sign in to Cloud Shell machines. */
@Composable
internal fun OAuthClientDialog(
    initial: GCloudOAuthClient,
    onDismiss: () -> Unit,
    onSave: (GCloudOAuthClient) -> Unit
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var clientId by remember(initial.id) { mutableStateOf(initial.clientId) }
    var secret by remember(initial.id) { mutableStateOf(initial.clientSecret) }
    var clientType by remember(initial.id) { mutableStateOf(initial.type) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_server_oauth_client)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteTextField(name, { name = it }, stringResource(R.string.common_name))
                RemoteTextField(clientId, { clientId = it }, stringResource(R.string.client_id))
                RemoteDropdownField(
                    label = stringResource(R.string.remote_server_oauth_client_type),
                    options = GoogleOAuthClientType.entries.map { option ->
                        option to stringResource(
                            if (option == GoogleOAuthClientType.ANDROID) R.string.remote_server_oauth_type_android
                            else R.string.remote_server_oauth_type_desktop
                        )
                    },
                    selected = clientType,
                    onSelect = { clientType = it }
                )
                if (clientType == GoogleOAuthClientType.DESKTOP) {
                    RemoteTextField(secret, { secret = it }, stringResource(R.string.client_secret), visual = PasswordVisualTransformation())
                }
            }
        },
        confirmAtRight = true,
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && clientId.isNotBlank(),
                onClick = { onSave(GCloudOAuthClient(initial.id, name, clientId, secret, clientType)) }
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}
