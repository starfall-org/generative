package org.starfall.generative.features.httptools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.starfall.generative.R
import org.starfall.generative.features.httptools.logic.httpEditorJson
import org.starfall.generative.features.httptools.logic.insertHttpParameter
import org.starfall.generative.features.httptools.logic.unknownHttpRequestParameters
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.inputs.SelectionDropdown
import org.starfall.generative.shared.ui.inputs.selectAllOnTripleClick

@Composable
internal fun HttpRequestTemplateField(
    label: String, value: String, onChange: (String) -> Unit, parameters: List<String>, tag: String,
    json: Boolean = true, objectOnly: Boolean = false, allowEmpty: Boolean = false,
    headers: Boolean = false, insertable: Boolean = true, readOnly: Boolean = false
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(value)) }
    var inserting by rememberSaveable { mutableStateOf(false) }
    var parameter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(value) {
        if (field.text != value) field = TextFieldValue(value, TextRange(field.selection.end.coerceAtMost(value.length)))
    }
    val parsed = remember(value, json) { if (json) runCatching { Json.parseToJsonElement(value) }.getOrNull() else null }
    val unknown = insertable && unknownHttpRequestParameters(value, json, parameters).isNotEmpty()
    val invalid = unknown || json && !(allowEmpty && value.isBlank()) && (parsed == null || objectOnly && parsed !is JsonObject ||
        headers && parsed is JsonObject && (parsed.any { (name, item) ->
            name.isBlank() || name.any { it.code !in 33..126 || !(it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~") } ||
                item !is JsonPrimitive || item == JsonNull || item.content.any { it == '\r' || it == '\n' }
        } || parsed.keys.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size != parsed.size))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (json && !readOnly) IconButton(enabled = parsed != null && !invalid, onClick = {
                val formatted = httpEditorJson.encodeToString(JsonElement.serializer(), parsed!!)
                field = TextFieldValue(formatted, TextRange(formatted.length))
                onChange(formatted)
            }, modifier = Modifier.testTag("${tag}_format")) {
                SharedIcon(Icons.Outlined.Code, stringResource(R.string.http_request_format_json))
            }
            if (insertable && !readOnly) IconButton(enabled = parameters.isNotEmpty(), onClick = {
                parameter = parameters.firstOrNull().orEmpty()
                inserting = true
            }, modifier = Modifier.testTag("${tag}_insert")) {
                SharedIcon(Icons.Outlined.Add, stringResource(R.string.http_request_insert_parameter))
            }
        }
        // Own the selection so inserting a token replaces the selection at the cursor.
        OutlinedTextField(value = field, onValueChange = {
            field = it.copy(composition = null)
            onChange(it.text)
        }, modifier = Modifier.fillMaxWidth().testTag(tag).selectAllOnTripleClick {
            field = field.copy(selection = TextRange(0, field.text.length), composition = null)
        }, readOnly = readOnly, isError = invalid, minLines = 3,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            supportingText = if (invalid) { { Text(stringResource(if (unknown) R.string.http_request_parameter_invalid
                else if (headers) R.string.http_request_headers_invalid
                else if (objectOnly) R.string.http_request_object_invalid else R.string.http_request_json_invalid)) } } else null)
    }
    if (inserting) AlertDialog(onDismissRequest = { inserting = false },
        title = { Text(stringResource(R.string.http_request_insert_parameter)) }, text = {
            SelectionDropdown(label = stringResource(R.string.http_parameter_name), selected = parameter,
                onSelect = { parameter = it }, options = parameters.map { it to it })
        }, confirmButton = {
            TextButton(enabled = parameter in parameters, onClick = {
                val (text, cursor) = insertHttpParameter(field.text, field.selection.start, field.selection.end, parameter, json)
                field = TextFieldValue(text, TextRange(cursor))
                onChange(text)
                inserting = false
            }) { Text(stringResource(R.string.http_request_insert)) }
        }, dismissButton = {
            TextButton(onClick = { inserting = false }) { Text(stringResource(R.string.common_cancel)) }
        })
}
