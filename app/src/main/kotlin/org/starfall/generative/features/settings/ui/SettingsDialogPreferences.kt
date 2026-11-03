package org.starfall.generative.features.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import androidx.compose.ui.res.stringResource
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.dialogs.RadioSelectionDialog
import org.starfall.generative.shared.ui.dialogs.RadioSelectionOption
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

internal data class SettingsChoice<T>(val value: T, val label: String, val description: String? = null)

/** A regular Settings row. Its choices are shown in a dialog, never an exposed dropdown. */
@Composable
internal fun <T> SettingsChoicePreference(
    title: String,
    value: T,
    choices: List<SettingsChoice<T>>,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = choices.firstOrNull { it.value == value }?.label.orEmpty()
    SettingsActionPreference(title, current, modifier, enabled) { open = true }
    if (open) {
        SettingsChoiceDialog(title, value, choices, onDismiss = { open = false }) { choice ->
            onSelected(choice)
            open = false
        }
    }
}

@Composable
internal fun SettingsActionPreference(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!summary.isNullOrBlank()) {
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SharedIcon(Icons.Outlined.ChevronRight, contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun <T> SettingsChoiceDialog(
    title: String,
    selected: T,
    choices: List<SettingsChoice<T>>,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit
) {
    RadioSelectionDialog(
        title = title,
        selected = selected,
        options = choices.map { choice ->
            RadioSelectionOption(choice.value, choice.label, choice.description)
        },
        onDismissRequest = onDismiss,
        onSelected = onSelected
    )
}

/** Multi-choice dialogs apply each toggle immediately and stay open for further choices. */
@Composable
internal fun <T> SettingsMultiChoicePreference(
    title: String,
    selected: Set<T>,
    choices: List<SettingsChoice<T>>,
    onSelectedChange: (Set<T>) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsActionPreference(title, selected.size.toString()) { open = true }
    if (open) {
        Dialog(onDismissRequest = { open = false }, title = { Text(title) }, scrollableContent = true, content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choices.forEach { choice ->
                    val checked = choice.value in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onSelectedChange(if (checked) selected - choice.value else selected + choice.value)
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        })
    }
}

/** Only draft text changes while the dialog is open; Save commits valid numbers. */
@Composable
internal fun SettingsNumberField(
    title: String,
    input: String,
    defaultValue: Int,
    range: IntRange,
    tag: String,
    onInputChange: (String) -> Unit
) {
    val valid = input.isBlank() || input.toIntOrNull()?.let { it in range } == true
    OutlinedTextField(
        value = input,
        onValueChange = { text ->
            if (text.length <= range.last.toString().length && text.all(Char::isDigit)) onInputChange(text)
        },
        label = { Text(title) },
        placeholder = { Text(stringResource(R.string.common_default_value_hint, defaultValue)) },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

@Composable
internal fun SettingsNumberPreference(
    title: String,
    value: Int,
    defaultValue: Int,
    range: IntRange,
    tag: String,
    onValueChange: (Int) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsActionPreference(title, value.toString(), Modifier.testTag("$tag-open")) { open = true }
    if (open) {
        var draft by remember(value) { mutableStateOf(value.toString()) }
        val parsed = if (draft.isBlank()) defaultValue else draft.toIntOrNull()
        Dialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            content = { SettingsNumberField(title, draft, defaultValue, range, tag, onInputChange = { draft = it }) },
            buttons = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_close)) }
                TextButton(
                    enabled = parsed != null && parsed in range,
                    onClick = {
                        parsed?.let(onValueChange)
                        open = false
                    }
                ) { Text(stringResource(R.string.common_save)) }
            }
        )
    }
}
