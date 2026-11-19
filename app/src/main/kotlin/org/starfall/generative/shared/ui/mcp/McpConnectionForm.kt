package org.starfall.generative.shared.ui.mcp

import org.starfall.generative.shared.ui.layout.fadingVerticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.inputs.KeyValueField
import org.starfall.generative.shared.ui.inputs.KeyValueValueType

@Composable
private fun McpNameField(name: String, onNameChange: (String) -> Unit, literal: Boolean = false, isError: Boolean = false) {
    OutlinedTextField(value = name, onValueChange = onNameChange,
        label = { Text(stringResource(R.string.common_name)) }, singleLine = true,
        isError = isError, modifier = Modifier.fillMaxWidth().testTag("mcp_name"), keyboardOptions = if (literal)
            KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
        else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
}

@Composable
private fun McpUrlField(url: String, onUrlChange: (String) -> Unit) {
    Text(stringResource(R.string.server_url), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(url, onUrlChange, placeholder = { Text(stringResource(R.string.common_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("mcp_url"), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
    if (url.trim().startsWith("http://", true)) Text(stringResource(R.string.http_unencrypted_mcp_warning),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** Shared connection form with optional fields and protocol-specific sections. */
@Composable
internal fun McpConnectionForm(
    name: String, onNameChange: (String) -> Unit,
    url: String? = null, onUrlChange: ((String) -> Unit)? = null,
    headers: List<Pair<String, String>>? = null, onHeadersChange: ((List<Pair<String, String>>) -> Unit)? = null,
    nameLiteral: Boolean = false, nameError: Boolean = false,
    modifier: Modifier = Modifier,
    leadingContent: @Composable ColumnScope.() -> Unit = {},
    connectionTypeContent: @Composable ColumnScope.() -> Unit = {},
    authenticationContent: @Composable ColumnScope.() -> Unit = {},
    additionalContent: @Composable ColumnScope.() -> Unit = {}
) {
    Column(modifier.testTag("mcp-connection-settings").fadingVerticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        leadingContent()
        McpNameField(name, onNameChange, literal = nameLiteral, isError = nameError)
        connectionTypeContent()
        if (url != null && onUrlChange != null) {
            HorizontalDivider()
            McpUrlField(url, onUrlChange)
        }
        authenticationContent()
        if (headers != null && onHeadersChange != null) {
            HorizontalDivider()
            McpHeaderFields(headers, onHeadersChange)
        }
        additionalContent()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun McpHeaderFields(headers: List<Pair<String, String>>, onHeadersChange: (List<Pair<String, String>>) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.custom_headers), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onHeadersChange(headers + ("" to "")) }) { SharedIcon(Icons.Default.Add, stringResource(R.string.common_add)) }
    }
    headers.forEachIndexed { index, header ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyValueField(
                keyValue = header.first,
                onKeyChange = { value -> onHeadersChange(headers.toMutableList().also { it[index] = value to header.second }) },
                value = header.second,
                onValueChange = { value -> onHeadersChange(headers.toMutableList().also { it[index] = header.first to value }) },
                keyLabel = { Text(stringResource(R.string.common_key)) },
                valueLabel = { Text(stringResource(R.string.common_value)) },
                valueType = KeyValueValueType.Literal,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onHeadersChange(headers.filterIndexed { i, _ -> i != index }) }) { SharedIcon(Icons.Outlined.Delete, stringResource(R.string.common_remove)) }
        }
    }
}
