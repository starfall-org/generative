package org.starfall.generative.features.httptools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.starfall.generative.features.httptools.logic.updateHttpParameter
import org.starfall.generative.features.httptools.logic.removeHttpParameter
import org.starfall.generative.features.httptools.logic.renameHttpParameterTokens
import org.starfall.generative.R
import org.starfall.generative.shared.model.CustomHttpTool
import org.starfall.generative.shared.model.HttpToolResponseMode
import org.starfall.generative.shared.model.HttpToolBodyFormat
import org.starfall.generative.features.httptools.logic.httpEditorJson
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.inputs.SelectionDropdown
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate
import org.starfall.generative.shared.ui.layout.SettingsCard

private val literalOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false, keyboardType = KeyboardType.Password)

@Composable
fun HttpToolEditor(tool: CustomHttpTool, onSave: suspend (CustomHttpTool) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable(tool.id) { mutableStateOf(tool.name) }
    var description by rememberSaveable(tool.id) { mutableStateOf(tool.description) }
    var method by rememberSaveable(tool.id) { mutableStateOf(tool.method) }
    var url by rememberSaveable(tool.id) { mutableStateOf(tool.url) }
    var parameters by rememberSaveable(tool.id) { mutableStateOf(tool.parameters.toString()) }
    var query by rememberSaveable(tool.id) { mutableStateOf(httpEditorJson.encodeToString(JsonObject.serializer(), tool.query)) }
    var headers by rememberSaveable(tool.id) { mutableStateOf(httpEditorJson.encodeToString(JsonObject.serializer(), tool.headers)) }
    var body by rememberSaveable(tool.id) { mutableStateOf(tool.body) }
    var bodyFormat by rememberSaveable(tool.id) { mutableStateOf(tool.bodyFormat) }
    var mode by rememberSaveable(tool.id) { mutableStateOf(tool.responseMode) }
    var mapping by rememberSaveable(tool.id) { mutableStateOf(tool.responseMapping.toString()) }
    var advanced by rememberSaveable(tool.id) { mutableStateOf(false) }
    val parameterSchema = remember(parameters) { runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull() }
    var saving by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun draft() = tool.copy(name = name.trim(), description = description, method = method, url = url.trim(),
        parameters = Json.parseToJsonElement(parameters).jsonObject,
        query = Json.parseToJsonElement(query).jsonObject, headers = Json.parseToJsonElement(headers).jsonObject,
        body = body, bodyFormat = bodyFormat, responseMode = mode,
        responseMapping = Json.parseToJsonElement(mapping).jsonObject)
    EditorScreenTemplate(title = stringResource(R.string.http_tool_editor), onBack = onBack, actions = {
        IconButton(enabled = !saving, onClick = {
            invalid = false
            saveFailed = false
            val edited = runCatching { draft().also { it.validate() } }.getOrNull()
            if (edited == null) invalid = true else scope.launch {
                saving = true
                try { onSave(edited); onBack() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { saveFailed = true }
                finally { saving = false }
            }
        }) { SharedIcon(Icons.Outlined.Check, stringResource(R.string.common_save)) }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (invalid) Text(stringResource(R.string.http_tool_invalid), color = MaterialTheme.colorScheme.error)
            if (saveFailed) Text(stringResource(R.string.save_change_failed), color = MaterialTheme.colorScheme.error)
            SettingsCard {
                HttpLiteralField(stringResource(R.string.http_tool_name), name, { name = it }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.common_description)) })
            }
            parameterSchema?.let { schema ->
                HttpToolParametersEditor(schema = schema, onSave = { originalName, newName, definition, required ->
                    parameters = updateHttpParameter(schema, originalName, newName, definition, required).toString()
                    if (originalName != null && originalName != newName) {
                        // Request templates keep following the renamed argument.
                        query = renameHttpParameterTokens(query, originalName, newName)
                        headers = renameHttpParameterTokens(headers, originalName, newName)
                        body = renameHttpParameterTokens(body, originalName, newName)
                    }
                }, onDelete = { parameterName ->
                    parameters = removeHttpParameter(schema, parameterName).toString()
                })
            }
            HttpAdvancedSection(expanded = advanced || parameterSchema == null, onExpandedChange = { advanced = it },
                label = stringResource(R.string.http_parameter_advanced)) {
                HttpLiteralField(stringResource(R.string.http_tool_parameters), parameters, { parameters = it })
            }
            HttpToolRequestEditor(method = method, onMethodChange = { method = it }, url = url, onUrlChange = { url = it },
                query = query, onQueryChange = { query = it }, headers = headers, onHeadersChange = { headers = it },
                body = body, onBodyChange = { body = it }, bodyFormat = bodyFormat, onBodyFormatChange = { bodyFormat = it },
                parameters = (parameterSchema?.get("properties") as? JsonObject).orEmpty().keys.toList(), draft = ::draft)

            SettingsCard {
                Text(stringResource(R.string.http_tool_response_section), style = MaterialTheme.typography.titleMedium)
                SelectionDropdown(label = stringResource(R.string.http_tool_response_mode),
                    options = HttpToolResponseMode.entries.map { it to it.name }, selected = mode,
                    onSelect = { mode = it }, modifier = Modifier.fillMaxWidth())
            }
            HttpResponseMappingsEditor(mapping = mapping, onChange = { mapping = it })
        }
    }
}

@Composable
internal fun HttpLiteralField(
    label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = false, placeholder: String? = null
) {
    OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, placeholder = if (placeholder == null) null else { { Text(placeholder) } },
        keyboardOptions = literalOptions, singleLine = singleLine,
        minLines = if (singleLine) 1 else 3)
}
