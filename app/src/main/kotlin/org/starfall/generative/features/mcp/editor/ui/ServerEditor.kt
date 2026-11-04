package org.starfall.generative.features.mcp.editor.ui

import org.starfall.generative.shared.ui.layout.AppSwipeTabs
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.features.mcp.shared.ui.McpConnectionSettings
import org.starfall.generative.features.mcp.shared.ui.McpToolsTab
import org.starfall.generative.shared.model.McpAuthMethod
import org.starfall.generative.shared.model.McpAuthorization
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.McpToolParameterConfig
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.bearerHeaderValue
import org.starfall.generative.shared.model.isContentApiName
import org.starfall.generative.shared.ui.screens.ScreenTemplate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditor(
    initialServer: McpInfo, isNew: Boolean,
    onRefreshTools: suspend (McpInfo) -> Result<List<ToolDefinition>>,
    onAuthorizeOAuth: suspend (McpInfo) -> Result<McpInfo>, onClearOAuth: suspend (McpInfo) -> McpInfo,
    cachedTools: List<ToolDefinition>?, cachedError: String?, cachedLoading: Boolean,
    toolSettings: ToolSettings, onSetToolEnabled: (String, String, Boolean) -> Unit,
    onSetToolParameter: (String, String, String, McpToolParameterConfig) -> Unit,
    onDismiss: () -> Unit, onSave: (McpInfo) -> Unit,
    onSaveIcon: (McpInfo) -> Unit = {}
) {
    var serverIcon by remember(initialServer.id) { mutableStateOf(initialServer.icon) }
    var iconImporting by remember { mutableStateOf(false) }
    var name by remember(initialServer.id) { mutableStateOf(initialServer.name) }
    var protocol by remember(initialServer.id) { mutableStateOf(initialServer.protocol) }
    var url by remember(initialServer.id) { mutableStateOf(initialServer.url.orEmpty()) }
    var headers by remember(initialServer.id) { mutableStateOf(initialServer.headers.orEmpty().toList()) }
    var authMethod by remember(initialServer.id) { mutableStateOf(if (initialServer.auth.method == McpAuthMethod.CUSTOM_HEADER) McpAuthMethod.BEARER_TOKEN else initialServer.auth.method) }
    var authKey by remember(initialServer.id) { mutableStateOf(initialServer.auth.key.orEmpty().ifBlank { if (initialServer.auth.method == McpAuthMethod.QUERY_PARAM) "key" else "Authorization" }) }
    var authValue by remember(initialServer.id) { mutableStateOf(initialServer.auth.value.orEmpty()) }
    val pager = key(initialServer.id) { androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 }) }
    val scope = rememberCoroutineScope()
    var oauthClientId by remember(initialServer.id) { mutableStateOf(initialServer.auth.oauthClientId.orEmpty()) }
    var oauthClientSecret by remember(initialServer.id) { mutableStateOf(initialServer.auth.oauthClientSecret.orEmpty()) }
    var oauthRedirectUri by remember(initialServer.id) {
        mutableStateOf(initialServer.auth.oauthRedirectUri ?: org.starfall.generative.integrations.mcp.McpOAuthService.REDIRECT_URI)
    }
    var oauthAuthorized by remember(initialServer.id) { mutableStateOf(initialServer.auth.oauthAuthorized ||
        (initialServer.auth.method == McpAuthMethod.OAUTH2 && initialServer.auth.value?.isNotBlank() == true)) }

    fun currentAuth(): McpAuthorization = when (authMethod) {
        McpAuthMethod.NONE -> McpAuthorization()
        McpAuthMethod.OAUTH2 -> McpAuthorization(method = McpAuthMethod.OAUTH2,
            value = authValue.trim().takeIf { it.isNotEmpty() }, oauthClientId = oauthClientId.trim().takeIf { it.isNotEmpty() },
            oauthClientSecret = oauthClientSecret.takeIf { it.isNotBlank() },
            oauthRedirectUri = oauthRedirectUri.trim().takeIf { it.isNotEmpty() && it != org.starfall.generative.integrations.mcp.McpOAuthService.REDIRECT_URI },
            oauthAuthorized = oauthAuthorized)
        McpAuthMethod.BEARER_TOKEN -> McpAuthorization(method = authMethod, key = authKey.trim(),
            value = bearerHeaderValue(authKey.ifBlank { "Authorization" }, authValue))
        McpAuthMethod.QUERY_PARAM -> McpAuthorization(method = authMethod, key = authKey.trim(), value = authValue.trim())
        McpAuthMethod.CUSTOM_HEADER -> McpAuthorization(method = McpAuthMethod.BEARER_TOKEN, key = authKey.trim(), value = authValue.trim())
    }
    fun currentServer() = initialServer.copy(name = name.trim(), icon = serverIcon, protocol = protocol,
        url = url.trim().ifEmpty { null }, headers = headers.map { it.first.trim() to it.second }
            .filter { it.first.isNotEmpty() }.toMap().ifEmpty { null }, auth = currentAuth())
    val authValid = authValue.isBlank() || authKey.isBlank() || authMethod !in listOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM) ||
        (authKey.isNotBlank() && !authKey.contains(':') && authKey.none { it <= ' ' || it.code >= 127 })
    val canSave = name.isNotBlank() && (url.isNotBlank() || name.isContentApiName() || initialServer.id == "content-api") && authValid && headers.all {
        it.first.isNotBlank() && !it.first.contains(':') && !it.first.any { ch -> ch == '\r' || ch == '\n' } &&
            !it.second.any { ch -> ch == '\r' || ch == '\n' }
    }
    fun refreshTools() { if (canSave && !cachedLoading) scope.launch { onRefreshTools(currentServer()) } }
    ScreenTemplate(
        title = {
            Text(name.trim().ifEmpty { stringResource(R.string.common_name) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        onBack = onDismiss,
        actions = {
            Button(onClick = { onSave(currentServer()) }, enabled = canSave && !iconImporting,
                modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.common_save)) }
        }
    ) {
        ContentVisualEditor { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                AppSwipeTabs(
                    tabs = listOf(stringResource(R.string.mcp_basic_settings), stringResource(R.string.common_tools)),
                    pagerState = pager,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { page ->
                    if (page == 0) McpConnectionSettings(
                    name = name, onNameChange = { name = it }, icon = serverIcon,
                    onIconChange = { serverIcon = it; onSaveIcon(currentServer()) }, onIconBusyChange = { iconImporting = it },
                    protocol = protocol, onProtocolChange = { protocol = it }, url = url,
                    onUrlChange = { changed -> if (authMethod == McpAuthMethod.OAUTH2 && changed.trim() != url.trim()) oauthAuthorized = false; url = changed },
                    authMethod = authMethod, onAuthMethodChange = { method ->
                        if (method != authMethod) { authValue = ""; if (method == McpAuthMethod.OAUTH2) oauthAuthorized = false }
                        authMethod = method; authKey = if (method == McpAuthMethod.QUERY_PARAM) "key" else "Authorization"
                    }, authKey = authKey, onAuthKeyChange = { authKey = it }, authValue = authValue, onAuthValueChange = { authValue = it }, authValid = authValid,
                     headers = headers, onHeadersChange = { headers = it }, oauthClientId = oauthClientId,
                     onOauthClientIdChange = { changed -> if (changed.trim() != oauthClientId.trim()) oauthAuthorized = false; oauthClientId = changed },
                     oauthClientSecret = oauthClientSecret,
                     onOauthClientSecretChange = { changed -> if (changed != oauthClientSecret) oauthAuthorized = false; oauthClientSecret = changed },
                     oauthRedirectUri = oauthRedirectUri,
                     onOauthRedirectUriChange = { changed -> if (changed.trim() != oauthRedirectUri.trim()) oauthAuthorized = false; oauthRedirectUri = changed },
                     oauthAuthorized = oauthAuthorized, onAuthorizeOAuth = {
                         onAuthorizeOAuth(currentServer()).map { authorized ->
                             oauthClientId = authorized.auth.oauthClientId.orEmpty(); oauthClientSecret = authorized.auth.oauthClientSecret.orEmpty()
                             oauthRedirectUri = authorized.auth.oauthRedirectUri ?: org.starfall.generative.integrations.mcp.McpOAuthService.REDIRECT_URI
                             oauthAuthorized = authorized.auth.oauthAuthorized; authValue = authorized.auth.value.orEmpty()
                         }
                    }, onClearOAuth = {
                         val cleared = onClearOAuth(currentServer())
                         oauthClientId = cleared.auth.oauthClientId.orEmpty(); oauthClientSecret = cleared.auth.oauthClientSecret.orEmpty()
                         oauthRedirectUri = cleared.auth.oauthRedirectUri ?: org.starfall.generative.integrations.mcp.McpOAuthService.REDIRECT_URI
                         oauthAuthorized = false; authValue = cleared.auth.value.orEmpty()
                    }, modifier = Modifier.fillMaxSize()
                ) else McpToolsTab(initialServer.id, cachedTools, cachedLoading, cachedError, canSave, toolSettings,
                    onSetToolEnabled, onSetToolParameter, ::refreshTools, Modifier.fillMaxSize())
                }
            }
        }
    }
}
