package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.starfall.generative.R
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.inputs.SelectionDropdown
import org.starfall.generative.shared.ui.providers.ProviderEditorLayout
import org.starfall.generative.shared.ui.providers.ProviderCredentialField
import org.starfall.generative.shared.ui.providers.OAuthAccountCard

/** Authentication form driven by integration methods supplied by the host. */
@Composable
fun ProviderIntegrationScreen(integration: JsonObject, onBack: () -> Unit,
    connectKey: suspend (String, JsonObject?) -> Unit,
    beginOAuth: suspend (String, JsonObject?) -> JsonObject,
    oauthStatus: suspend (String) -> JsonObject,
    completeOAuth: suspend (String, String?) -> Unit,
    cancelOAuth: suspend (String) -> Unit) {
    val methods = remember(integration) { integration["methods"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        .filter { it["type"]?.jsonPrimitive?.content in listOf("key", "oauth") } }
    var index by remember { mutableIntStateOf(0) }
    var key by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf(false) }
    var pollRetry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val method = methods.getOrNull(index)
    val kind = method?.get("type")?.jsonPrimitive?.content
    val form = method?.get("form")
    val parsedAnswer = remember(answer) { if (answer.isBlank()) null else runCatching { Json.parseToJsonElement(answer) as JsonObject }.getOrNull() }
    val answerValid = answer.isBlank() || parsedAnswer != null
    val attemptId = attempt?.get("attemptID")?.jsonPrimitive?.content
    LaunchedEffect(attemptId, pollRetry, terminal, complete) {
        if (attemptId == null || terminal || complete) return@LaunchedEffect
        while (true) {
            delay(2000)
            try {
                when (oauthStatus(attemptId)["status"]?.jsonPrimitive?.content) {
                    "complete" -> { complete = true; break }
                    "failed", "expired" -> { failed = true; terminal = true; break }
                    "pending" -> Unit
                    else -> { failed = true; break }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; break }
        }
    }
    fun launchAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true } finally { busy = false }
        }
    }
    ProviderEditorLayout(title = { Text(integration["name"]?.jsonPrimitive?.content.orEmpty()) }, onBack = {
        if (!busy) {
            val active = attemptId
            if (active != null && !complete && !terminal) launchAction {
                cancelOAuth(active)
                attempt = null
                onBack()
            } else onBack()
        }
    }) {
        if (methods.isEmpty()) {
            Text(stringResource(R.string.server_provider_no_method))
        } else {
            if (!complete && attempt == null) {
                SelectionDropdown(label = stringResource(R.string.server_connection_auth_method),
                    options = methods.mapIndexed { i, m -> i to (m["label"]?.jsonPrimitive?.content ?: m["type"]!!.jsonPrimitive.content) },
                    selected = index, onSelect = { index = it; answer = ""; key = ""; failed = false },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                if (kind == "key") ProviderCredentialField(key, { key = it },
                    label = { Text(stringResource(R.string.server_provider_api_key)) }, visibilityKey = index)
                if (form != null) {
                    Text(stringResource(R.string.server_provider_form_hint), style = MaterialTheme.typography.bodySmall)
                    Text(form.toString(), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(answer, { answer = it }, label = { Text(stringResource(R.string.server_provider_form_answer)) },
                        modifier = Modifier.fillMaxWidth(), isError = !answerValid,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
                }
            }
            if (kind == "oauth") {
                val active = attemptId != null && !complete && !terminal
                val methodId = method["id"]?.jsonPrimitive?.content
                OAuthAccountCard(signedIn = complete, identity = null,
                    signingIn = active || (busy && attempt == null && !complete), signingOut = false,
                    canSignIn = !busy && answerValid && methodId != null,
                    canSignOut = false, canCancelSignIn = active && !busy,
                    error = if (failed) stringResource(R.string.server_inventory_failed) else null,
                    onSignIn = if (attempt == null && !complete && methodId != null) ({
                        launchAction {
                            val result = beginOAuth(methodId, parsedAnswer)
                            require(!result["attemptID"]?.jsonPrimitive?.content.isNullOrBlank())
                            attempt = result
                        }
                    }) else null,
                    onCancelSignIn = if (active) ({
                        launchAction { cancelOAuth(attemptId); attempt = null; code = "" }
                    }) else null,
                    supportingText = if (active) attempt?.get("instructions")?.jsonPrimitive?.content else null,
                    pendingContent = {
                        val url = attempt?.get("url")?.jsonPrimitive?.content.orEmpty()
                        if (active && (url.startsWith("https://", true) || url.startsWith("http://", true))) {
                            TextButton(onClick = { try { uriHandler.openUri(url) } catch (_: Exception) { failed = true } }) {
                                Text(stringResource(R.string.server_provider_open_auth))
                            }
                        }
                        if (active && (attempt?.get("mode")?.jsonPrimitive?.content == "code" || attempt?.get("paste")?.jsonPrimitive?.content == "true")) {
                            OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.server_provider_auth_code)) },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
                            Button(enabled = !busy && code.isNotBlank(), onClick = {
                                launchAction { completeOAuth(attemptId, code); code = ""; pollRetry++ }
                            }) { Text(stringResource(R.string.server_provider_submit_code)) }
                        }
                    })
            } else if (complete) {
                Text(stringResource(R.string.server_provider_connected))
            } else if (kind == "key") {
                Button(enabled = !busy && answerValid && key.isNotBlank(), onClick = {
                    launchAction { connectKey(key, parsedAnswer); key = ""; complete = true }
                }) { Text(stringResource(R.string.server_mcp_connect)) }
            }
            if (failed) {
                if (kind != "oauth") Text(stringResource(R.string.server_inventory_failed), color = MaterialTheme.colorScheme.error)
                if (attemptId != null) TextButton(enabled = !busy, onClick = {
                    failed = false
                    if (terminal) { terminal = false; attempt = null; code = "" }
                    else pollRetry++
                }) { Text(stringResource(R.string.common_retry)) }
            }
        }
    }
}
