package org.starfall.generative.features.mcp.shared.ui

import org.starfall.generative.shared.ui.menus.DropdownMenu
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.McpAuthMethod
import org.starfall.generative.shared.model.McpProtocol
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.bearerHeaderValue
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.subfeatures.icons.ui.IconPickerRow
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.inputs.ApiKeyField
import org.starfall.generative.shared.subfeatures.icons.ui.providerInitials
import org.starfall.generative.shared.ui.inputs.KeyValueField
import org.starfall.generative.shared.ui.inputs.KeyValueValueType

@Composable
internal fun mcpAuthenticationLabel(method: McpAuthMethod) = stringResource(when (method) {
    McpAuthMethod.NONE -> R.string.common_none
    McpAuthMethod.BEARER_TOKEN, McpAuthMethod.CUSTOM_HEADER -> R.string.bearer_token
    McpAuthMethod.QUERY_PARAM -> R.string.url_query
    McpAuthMethod.OAUTH2 -> R.string.oauth_flow
})

@Composable
internal fun McpConnectionSettings(
    name: String, onNameChange: (String) -> Unit, icon: String?, onIconChange: (String?) -> Unit, onIconBusyChange: (Boolean) -> Unit,
    protocol: McpProtocol, onProtocolChange: (McpProtocol) -> Unit, url: String, onUrlChange: (String) -> Unit,
    authMethod: McpAuthMethod, onAuthMethodChange: (McpAuthMethod) -> Unit,
    authKey: String, onAuthKeyChange: (String) -> Unit, authValue: String, onAuthValueChange: (String) -> Unit, authValid: Boolean,
    headers: List<Pair<String, String>>, onHeadersChange: (List<Pair<String, String>>) -> Unit,
    oauthClientId: String, onOauthClientIdChange: (String) -> Unit,
    oauthClientSecret: String, onOauthClientSecretChange: (String) -> Unit,
    oauthRedirectUri: String, onOauthRedirectUriChange: (String) -> Unit,
    oauthAuthorized: Boolean, onAuthorizeOAuth: suspend () -> Result<Unit>, onClearOAuth: suspend () -> Unit,
    modifier: Modifier = Modifier
) {
    var oauthAdvancedExpanded by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconPickerRow(icon, onIconChange, text = providerInitials(name), fallback = Icons.Outlined.Extension,
            onBusyChange = onIconBusyChange, matchName = name, faviconBaseUrl = url)
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.common_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.legacy_sse), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(protocol == McpProtocol.SSE, onCheckedChange = { onProtocolChange(if (it) McpProtocol.SSE else McpProtocol.STREAMABLE_HTTP) })
        }
        HorizontalDivider()
        Text(stringResource(R.string.server_url), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(url, onUrlChange, placeholder = { Text(stringResource(R.string.common_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (url.trim().startsWith("http://", true)) Text(stringResource(R.string.http_unencrypted_mcp_warning),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.mcp_auth), style = MaterialTheme.typography.titleMedium)
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(stringResource(R.string.mcp_auth_type, mcpAuthenticationLabel(authMethod))) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                listOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM, McpAuthMethod.OAUTH2, McpAuthMethod.NONE).forEach { method ->
                    DropdownMenuItem(text = { Text(mcpAuthenticationLabel(method)) }, onClick = { onAuthMethodChange(method); expanded = false })
                }
            }
        }
        if (authMethod in listOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM)) OutlinedTextField(authKey, onAuthKeyChange,
            label = { Text(stringResource(if (authMethod == McpAuthMethod.BEARER_TOKEN) R.string.header_key else R.string.query_parameter_name)) },
            isError = !authValid, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (authMethod == McpAuthMethod.OAUTH2) McpOAuthFields(url, oauthClientId, onOauthClientIdChange,
            oauthClientSecret, onOauthClientSecretChange, oauthAuthorized, oauthRedirectUri, onOauthRedirectUriChange,
            onAuthorizeOAuth, onClearOAuth,
            oauthAdvancedExpanded, { oauthAdvancedExpanded = !oauthAdvancedExpanded })
        if (authMethod != McpAuthMethod.NONE && authMethod != McpAuthMethod.OAUTH2) ApiKeyField(authValue, onAuthValueChange,
            label = { Text(stringResource(if (authMethod == McpAuthMethod.BEARER_TOKEN) R.string.bearer_token else R.string.common_value)) },
            visibilityKey = authMethod,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (!it.isFocused && authMethod == McpAuthMethod.BEARER_TOKEN) onAuthValueChange(bearerHeaderValue(authKey.trim().ifBlank { "Authorization" }, authValue))
            })
        HorizontalDivider()
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
        Spacer(Modifier.height(24.dp))
    }
}
