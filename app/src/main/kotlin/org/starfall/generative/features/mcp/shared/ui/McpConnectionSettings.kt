package org.starfall.generative.features.mcp.shared.ui

import org.starfall.generative.shared.ui.menus.DropdownMenu
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.ui.mcp.McpConnectionForm
import org.starfall.generative.shared.model.McpAuthMethod
import org.starfall.generative.shared.model.McpProtocol
import org.starfall.generative.shared.model.auth.bearerHeaderValue
import org.starfall.generative.shared.subfeatures.icons.ui.IconPickerRow
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.inputs.ApiKeyField
import org.starfall.generative.shared.subfeatures.icons.ui.providerInitials

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
    McpConnectionForm(
        name = name, onNameChange = onNameChange, url = url, onUrlChange = onUrlChange,
        headers = headers, onHeadersChange = onHeadersChange, modifier = modifier,
        leadingContent = {
            IconPickerRow(icon, onIconChange, text = providerInitials(name), fallback = Icons.Outlined.Mcp,
                onBusyChange = onIconBusyChange, matchName = name, faviconBaseUrl = url)
        },
        connectionTypeContent = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.legacy_sse), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(protocol == McpProtocol.SSE, onCheckedChange = { onProtocolChange(if (it) McpProtocol.SSE else McpProtocol.STREAMABLE_HTTP) })
            }
        },
        authenticationContent = {
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
        }
    )
}
