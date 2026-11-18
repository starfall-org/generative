package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.starfall.generative.R
import org.starfall.generative.features.serverconnection.logic.runtimeMcpConfig
import org.starfall.generative.shared.ui.mcp.McpEditorLayout
import org.starfall.generative.shared.ui.mcp.McpConnectionForm
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.inputs.SelectionDropdown

/** Runtime configuration belongs to OpenCode; no local MCP repository or OAuth client is used. */
@Composable
fun McpRuntimeEditor(existingNames: Set<String>, busy: Boolean, failed: Boolean,
    onBack: () -> Unit, onSave: (String, JsonObject) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("remote") }
    var url by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var remoteOptions by remember { mutableStateOf("{}") }
    var localOptions by remember { mutableStateOf("{\n  \"command\": []\n}") }
    val options = if (type == "remote") remoteOptions else localOptions
    val config = remember(type, url, headers, options) { runtimeMcpConfig(type, url, headers, options) }
    val valid = name.isNotBlank() && name !in existingNames && '/' !in name && name != "." && name != ".." && config != null
    val literal = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
    McpEditorLayout(
        title = stringResource(R.string.server_mcp_add), onBack = { if (!busy) onBack() },
        canSave = valid && !busy, onSave = { onSave(name, requireNotNull(config)) }
    ) {
        McpConnectionForm(
            name = name, onNameChange = { name = it }, nameLiteral = true, nameError = name in existingNames,
            url = url.takeIf { type == "remote" }, onUrlChange = { url = it },
            headers = headers.takeIf { type == "remote" }, onHeadersChange = { headers = it },
            modifier = Modifier.fillMaxSize(),
            leadingContent = {
                Text(stringResource(R.string.server_mcp_add_hint), style = MaterialTheme.typography.bodySmall)
            },
            connectionTypeContent = {
                if (name in existingNames) Text(stringResource(R.string.server_mcp_name_exists), color = MaterialTheme.colorScheme.error)
                SelectionDropdown(label = stringResource(R.string.http_parameter_type), options = listOf("remote" to "remote", "local" to "local"),
                    selected = type, onSelect = { type = it }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            },
            additionalContent = {
                OutlinedTextField(value = options, onValueChange = { if (type == "remote") remoteOptions = it else localOptions = it },
                    label = { Text(stringResource(R.string.server_mcp_config)) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), keyboardOptions = literal)
                if (failed) Text(stringResource(R.string.server_mcp_operation_failed), color = MaterialTheme.colorScheme.error)
            }
        )
    }
}
