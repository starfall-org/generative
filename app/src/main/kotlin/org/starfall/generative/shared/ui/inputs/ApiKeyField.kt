package org.starfall.generative.shared.ui.inputs

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.text.ApiKeyMask

/** Shared credential input with explicit visibility and literal keyboard input. */
@Composable
fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 3,
    visibilityKey: Any? = null
) {
    var revealed by remember(visibilityKey) { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier,
        label = label, leadingIcon = leadingIcon, supportingText = supportingText,
        readOnly = readOnly, singleLine = singleLine, maxLines = maxLines,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        visualTransformation = if (revealed || readOnly) VisualTransformation.None else ApiKeyMask,
        trailingIcon = if (readOnly) null else { {
            IconButton(onClick = { revealed = !revealed }) {
                SharedIcon(if (revealed) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    stringResource(if (revealed) R.string.provider_hide_api_key else R.string.provider_show_api_key))
            }
        } }
    )
}
