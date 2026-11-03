package org.starfall.generative.features.tools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.media.MediaRequestStep
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.nestedContainerColor
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

/** Hold incomplete edits locally so an unfinished header name never corrupts the saved request. */
internal data class EditableHeader(val id: Int, val name: String, val value: String)

internal fun headersFromRows(rows: List<EditableHeader>): Map<String, String>? {
    val headers = linkedMapOf<String, String>()
    rows.forEach { row ->
        val name = row.name.trim()
        if (name.isEmpty() && row.value.isEmpty()) return@forEach
        if (name.isEmpty() || name.any { it <= ' ' || it == ':' || it.code >= 127 } ||
            row.value.contains('\r') || row.value.contains('\n') ||
            headers.keys.any { it.equals(name, ignoreCase = true) }) return null
        headers[name] = row.value
    }
    return headers
}

internal val mediaLiteralKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password
)

@Composable
internal fun MediaRequestHeadersEditor(
    stepNumber: Int,
    headers: Map<String, String>,
    previousSteps: List<MediaRequestStep> = emptyList(),
    testedResponsePaths: Map<Int, List<String>> = emptyMap(),
    onChange: (Map<String, String>) -> Unit
) {
    var rows by remember(stepNumber) {
        mutableStateOf(headers.entries.mapIndexed { index, entry ->
            EditableHeader(index, entry.key, entry.value)
        })
    }
    var nextId by remember(stepNumber) { mutableIntStateOf(rows.size) }
    val invalid = headersFromRows(rows) == null
    fun update(updated: List<EditableHeader>) {
        rows = updated
        headersFromRows(updated)?.let { if (it != headers) onChange(it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.media_chain_headers), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.media_chain_provider_auth_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { update(rows + EditableHeader(nextId++, "", "")) },
                modifier = Modifier.testTag("media-add-header")) {
                SharedIcon(Icons.Default.Add, null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.media_chain_add_header))
            }
        }
        if (rows.isEmpty()) {
            Text(stringResource(R.string.media_chain_header_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.forEach { row ->
            key(row.id) {
                Surface(
                    color = nestedContainerColor(), border = nestedContainerBorder(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = row.name,
                                onValueChange = { value -> update(rows.map { if (it.id == row.id) it.copy(name = value) else it }) },
                                label = { Text(stringResource(R.string.media_chain_header_name)) },
                                placeholder = { Text(stringResource(R.string.media_chain_header_name_hint)) },
                                keyboardOptions = mediaLiteralKeyboardOptions,
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("media-header-name-${row.id}")
                            )
                            IconButton(onClick = { update(rows.filterNot { it.id == row.id }) },
                                modifier = Modifier.testTag("media-remove-header-${row.id}")) {
                                SharedIcon(Icons.Outlined.Delete, stringResource(R.string.common_remove))
                            }
                        }
                        MediaTemplateAutocompleteField(
                            value = row.value,
                            onValueChange = { value -> update(rows.map { if (it.id == row.id) it.copy(value = value) else it }) },
                            stepNumber = stepNumber,
                            previousSteps = previousSteps,
                            testedResponsePaths = testedResponsePaths,
                            label = { Text(stringResource(R.string.media_chain_header_value)) },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("media-header-value-${row.id}")
                        )
                    }
                }
            }
        }
        if (invalid) Text(stringResource(R.string.media_chain_header_invalid),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
