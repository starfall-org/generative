package org.starfall.generative.features.tools.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.starfall.generative.R
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.text.ApiKeyMask
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

/** The persisted editor draft only receives encrypted envelopes, never a plaintext key. */
@Composable
internal fun MusicProfileCredentialField(profileId: String, encrypted: String, onChange: (String) -> Unit) {
    var secret by remember(profileId) { mutableStateOf("") }
    OutlinedTextField(secret, {
        secret = it
        onChange(if (it.isBlank()) "" else SecretCipher.encrypt(it))
    }, label = { Text(stringResource(R.string.music_profile_credential)) },
        supportingText = { Text(stringResource(R.string.music_profile_credential_help)) },
        placeholder = { Text(if (encrypted.isBlank()) "" else "••••••••") },
        trailingIcon = if (encrypted.isNotBlank()) { {
            IconButton(onClick = { secret = ""; onChange("") }) {
                SharedIcon(Icons.Outlined.Delete, stringResource(R.string.common_remove))
            }
        } } else null,
        visualTransformation = ApiKeyMask, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth())
}
