package org.starfall.generative.features.httptools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.starfall.generative.R
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.inputs.KeyValueField
import org.starfall.generative.shared.ui.inputs.KeyValueValueType
import org.starfall.generative.shared.ui.lists.ItemCard

@Composable
internal fun HttpResponseMappingsEditor(mapping: String, onChange: (String) -> Unit) {
    val fields = remember(mapping) { runCatching { Json.parseToJsonElement(mapping).jsonObject }.getOrNull() }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.http_tool_response_mapping_section), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f))
        IconButton(enabled = fields != null, onClick = { creating = true; editing = "" }) {
            SharedIcon(Icons.Default.Add, stringResource(R.string.http_response_mapping_add))
        }
    }
    fields?.forEach { (field, path) ->
        ItemCard(isGrid = false, icon = {}, onClick = {
            if (path is JsonPrimitive && path.isString) { creating = false; editing = field } else advanced = true
        }, content = {
            Column {
                Text(field, style = MaterialTheme.typography.titleSmall)
                Text(when (path) {
                    is JsonPrimitive -> if (path.content.isEmpty()) stringResource(R.string.http_tool_entire_response) else path.content
                    is JsonArray -> "[]"
                    else -> "{}"
                }, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }, actions = {
            IconButton(onClick = { onChange(JsonObject(fields.orEmpty() - field).toString()) }) {
                SharedIcon(Icons.Outlined.Delete, stringResource(R.string.common_delete))
            }
        })
    }
    HttpAdvancedSection(expanded = advanced || fields == null, onExpandedChange = { advanced = it }) {
        HttpLiteralField(stringResource(R.string.http_tool_response_mapping), mapping, onChange)
    }
    editing?.let { original ->
        key(original, creating) {
            HttpResponseMappingDialog(original = original.takeUnless { creating }, fields = fields ?: JsonObject(emptyMap()),
                onSave = onChange, onDismiss = { editing = null })
        }
    }
}

@Composable
private fun HttpResponseMappingDialog(original: String?, fields: JsonObject, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var field by rememberSaveable { mutableStateOf(original.orEmpty()) }
    var path by rememberSaveable { mutableStateOf((original?.let { fields[it] } as? JsonPrimitive)?.content.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.http_tool_response_mapping_section)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (invalid) Text(stringResource(R.string.http_response_mapping_invalid), color = MaterialTheme.colorScheme.error)
            KeyValueField(keyValue = field, onKeyChange = { field = it }, value = path, onValueChange = { path = it },
                keyLabel = { Text(stringResource(R.string.http_response_field)) },
                valueLabel = { Text(stringResource(R.string.http_response_path)) },
                valuePlaceholder = { Text(stringResource(R.string.http_tool_entire_response)) }, valueType = KeyValueValueType.Literal)
        }
    }, confirmButton = {
        TextButton(onClick = {
            val name = field.trim()
            if (name.isBlank() || name != original && name in fields ||
                path.isNotEmpty() && !path.startsWith('/') || Regex("~(?![01])").containsMatchIn(path)) {
                invalid = true
            } else {
                onSave(JsonObject((if (original == null) fields else fields - original) + (name to JsonPrimitive(path))).toString())
                onDismiss()
            }
        }) { Text(stringResource(R.string.common_save)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
