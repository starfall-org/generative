package org.starfall.generative.shared.ui.inputs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.ui.menus.ExposedDropdownMenu
import org.starfall.generative.shared.ui.text.OutlinedTextField

/** Value input modes shared by headers, parameters, credentials and other key/value editors. */
enum class KeyValueValueType { Plain, Literal, Secret, Multiline }

data class KeyValueSuggestion(val value: String, val label: String = value)

/** Paired key/value editor with editable key autocomplete and literal-input-safe IME options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyValueField(
    keyValue: String,
    onKeyChange: (String) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyLabel: (@Composable () -> Unit)? = null,
    valueLabel: (@Composable () -> Unit)? = null,
    keyPlaceholder: (@Composable () -> Unit)? = null,
    valuePlaceholder: (@Composable () -> Unit)? = null,
    keySuggestions: List<KeyValueSuggestion> = emptyList(),
    onKeySuggestionSelected: (String) -> Unit = onKeyChange,
    valueType: KeyValueValueType = KeyValueValueType.Plain,
    keyError: Boolean = false,
    valueError: Boolean = false,
    keySupportingText: (@Composable () -> Unit)? = null,
    valueSupportingText: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    testTag: String? = null
) {
    var keyFocused by remember { mutableStateOf(false) }
    var suggestionsDismissed by remember { mutableStateOf(false) }
    val visibleSuggestions = remember(keyValue, keySuggestions) {
        if (keyValue.isBlank()) keySuggestions
        else keySuggestions.filter { it.value.contains(keyValue, ignoreCase = true) }
    }
    val menuExpanded = keyFocused && enabled && !suggestionsDismissed && visibleSuggestions.isNotEmpty()
    val literalKeyOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password
    )
    val valueOptions = when (valueType) {
        KeyValueValueType.Plain, KeyValueValueType.Multiline -> KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            keyboardType = KeyboardType.Text
        )
        KeyValueValueType.Literal, KeyValueValueType.Secret -> KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password
        )
    }
    val valueTransformation = if (valueType == KeyValueValueType.Secret) {
        PasswordVisualTransformation()
    } else VisualTransformation.None

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { suggestionsDismissed = !it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = keyValue,
                onValueChange = { onKeyChange(it); suggestionsDismissed = false },
                enabled = enabled,
                label = keyLabel,
                placeholder = keyPlaceholder,
                supportingText = keySupportingText,
                isError = keyError,
                keyboardOptions = literalKeyOptions,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
                    .then(testTag?.let { Modifier.testTag("$it-key") } ?: Modifier)
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = enabled)
                    .onFocusChanged {
                        keyFocused = it.isFocused
                        if (!it.isFocused) suggestionsDismissed = false
                    }
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { suggestionsDismissed = true }
            ) {
                visibleSuggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                suggestion.label,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            onKeySuggestionSelected(suggestion.value)
                            suggestionsDismissed = true
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            label = valueLabel,
            placeholder = valuePlaceholder,
            supportingText = valueSupportingText,
            isError = valueError,
            visualTransformation = valueTransformation,
            keyboardOptions = valueOptions,
            singleLine = valueType != KeyValueValueType.Multiline,
            maxLines = if (valueType == KeyValueValueType.Multiline) 6 else 1,
            modifier = Modifier.weight(1f).then(testTag?.let { Modifier.testTag("$it-value") } ?: Modifier)
        )
    }
}
