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
import org.starfall.generative.features.httptools.logic.httpParameterDefinition
import org.starfall.generative.features.httptools.logic.updateHttpParameter
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.ToolSwitch
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.inputs.SelectionDropdown
import org.starfall.generative.shared.ui.lists.ItemCard

@Composable
internal fun HttpToolParametersEditor(
    schema: JsonObject,
    onSave: (originalName: String?, name: String, definition: JsonObject, required: Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val properties = (schema["properties"] as? JsonObject).orEmpty()
    val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.common_parameters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { editing = "" }) { SharedIcon(Icons.Default.Add, stringResource(R.string.http_parameter_add)) }
    }
    if (properties.isEmpty()) Text(stringResource(R.string.no_parameters), style = MaterialTheme.typography.bodySmall)
    properties.forEach { (name, value) ->
        val definition = value as? JsonObject ?: JsonObject(emptyMap())
        ItemCard(isGrid = false, onClick = { editing = name }, icon = {}, content = {
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text((definition["type"]?.toString()?.trim('"').orEmpty()) +
                    if (name in required) " • " + stringResource(R.string.http_parameter_required) else "",
                    style = MaterialTheme.typography.labelSmall)
                (definition["description"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }, actions = {
            IconButton(onClick = { onDelete(name) }) { SharedIcon(Icons.Outlined.Delete, stringResource(R.string.common_delete)) }
        })
    }
    editing?.let { parameterName ->
        key(parameterName) {
            HttpParameterDialog(originalName = parameterName.takeIf { it.isNotEmpty() }, schema = schema,
                definition = properties[parameterName] as? JsonObject ?: JsonObject(emptyMap()), required = parameterName in required,
                onSave = onSave, onDismiss = { editing = null })
        }
    }
}

@Composable
private fun HttpParameterDialog(
    originalName: String?, schema: JsonObject, definition: JsonObject, required: Boolean,
    onSave: (String?, String, JsonObject, Boolean) -> Unit, onDismiss: () -> Unit
) {
    val supportedTypes = listOf("string", "integer", "number", "boolean", "object", "array", "null")
    val originalType = (definition["type"] as? JsonPrimitive)?.content?.takeIf { it in supportedTypes } ?: "advanced"
    var name by rememberSaveable { mutableStateOf(originalName.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(if (definition.isEmpty()) "string" else originalType) }
    var description by rememberSaveable { mutableStateOf((definition["description"] as? JsonPrimitive)?.content.orEmpty()) }
    var isRequired by rememberSaveable { mutableStateOf(required) }
    var useDefault by rememberSaveable { mutableStateOf("default" in definition) }
    var defaultValue by rememberSaveable { mutableStateOf(definition["default"]?.let {
        if (originalType == "string" && it is JsonPrimitive) it.content else it.toString()
    }.orEmpty()) }
    var enumValues by rememberSaveable { mutableStateOf(definition["enum"]?.toString().orEmpty()) }
    var constraints by rememberSaveable { mutableStateOf(JsonObject(definition - setOf("description", "default", "enum") -
        if (originalType == "advanced") emptySet() else setOf("type")).toString()) }
    var advanced by rememberSaveable { mutableStateOf(originalType == "advanced" && definition.isNotEmpty() ||
        "enum" in definition || constraints != "{}") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.http_parameter_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (invalid) Text(stringResource(R.string.http_parameter_invalid), color = MaterialTheme.colorScheme.error)
                HttpLiteralField(stringResource(R.string.http_parameter_name), name, { name = it }, singleLine = true)
                SelectionDropdown(label = stringResource(R.string.http_parameter_type), selected = type, onSelect = { type = it },
                    options = supportedTypes.map { it to it } + ("advanced" to stringResource(R.string.image_tool_advanced_json)))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.common_description)) },
                    modifier = Modifier.fillMaxWidth())
                ToolSwitch(stringResource(R.string.http_parameter_required), isRequired) { isRequired = it }
                ToolSwitch(stringResource(R.string.http_parameter_use_default), useDefault) { useDefault = it }
                if (useDefault) HttpLiteralField(stringResource(R.string.http_parameter_default), defaultValue, { defaultValue = it },
                    singleLine = type !in listOf("object", "array", "advanced"))
                HttpAdvancedSection(expanded = advanced, onExpandedChange = { advanced = it }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HttpLiteralField(stringResource(R.string.http_parameter_enum), enumValues, { enumValues = it })
                        HttpLiteralField(stringResource(R.string.http_parameter_constraints), constraints, { constraints = it })
                    }
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val saved = runCatching {
                    val value = httpParameterDefinition(type, description, useDefault, defaultValue, enumValues, constraints)
                    updateHttpParameter(schema, originalName, name.trim(), value, isRequired) // Validate uniqueness before applying.
                    onSave(originalName, name.trim(), value, isRequired)
                }.isSuccess
                if (saved) onDismiss() else invalid = true
            }) { Text(stringResource(R.string.common_save)) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}

