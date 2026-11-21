package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.R
import org.starfall.generative.features.serverconnection.logic.OpenCodeAuthorizationChallenge
import org.starfall.generative.features.serverconnection.logic.OpenCodeAuthorizationMethod
import org.starfall.generative.features.serverconnection.logic.OpenCodeProviderAuthorization
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.inputs.SelectionDropdown

internal class OpenCodeAuthorizationUi(private val authorization: OpenCodeProviderAuthorization) {
    private val selected = mutableStateMapOf<AuthMethod, Int>()
    private val answers = mutableStateMapOf<OpenCodeAuthorizationMethod, String>()
    private var pendingCode by mutableStateOf<CodeRequest?>(null)
    private data class CodeRequest(
        val challenge: OpenCodeAuthorizationChallenge,
        val result: CompletableDeferred<String?>,
    )

    private fun methods(kind: AuthMethod) = authorization.methods.filter { it.kind == kind }
    fun method(kind: AuthMethod): OpenCodeAuthorizationMethod? = methods(kind).getOrNull(selected[kind] ?: 0)

    fun isReady(kind: AuthMethod): Boolean {
        val method = method(kind) ?: return false
        val text = answers[method].orEmpty()
        return text.isBlank() || parseAnswers(text) != null
    }

    fun formAnswers(kind: AuthMethod): JsonObject? {
        val text = method(kind)?.let { answers[it] }.orEmpty()
        return if (text.isBlank()) null else requireNotNull(parseAnswers(text))
    }

    private fun parseAnswers(text: String): JsonObject? =
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    suspend fun presentChallenge(challenge: OpenCodeAuthorizationChallenge, openUri: (String) -> Unit): String? {
        if (!challenge.requiresCode) {
            openUri(requireNotNull(challenge.url))
            return null
        }
        val request = CodeRequest(challenge, CompletableDeferred())
        pendingCode = request
        return try {
            request.result.await()
        } finally {
            if (pendingCode == request) pendingCode = null
        }
    }

    @Composable
    fun Form(kind: AuthMethod) {
        val choices = methods(kind)
        val method = method(kind)
        if (method == null) {
            Text(stringResource(R.string.server_provider_no_method), style = MaterialTheme.typography.bodySmall)
            return
        }
        if (choices.size > 1) {
            val fallback = stringResource(if (kind == AuthMethod.OAUTH) R.string.oauth_sign_in else R.string.server_provider_api_key)
            SelectionDropdown(
                label = stringResource(R.string.server_connection_auth_method),
                options = choices.mapIndexed { index, choice -> index to (choice.label ?: choice.id ?: fallback) },
                selected = selected[kind] ?: 0,
                onSelect = { selected[kind] = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val form = method.form ?: return
        Text(stringResource(R.string.server_provider_form_hint), style = MaterialTheme.typography.bodySmall)
        Text(form.toString(), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = answers[method].orEmpty(),
            onValueChange = { answers[method] = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.server_provider_form_answer)) },
            isError = !isReady(kind),
            keyboardOptions = rawAuthorizationInput,
        )
    }

    @Composable
    fun PendingCode(openUri: (String) -> Unit) {
        val request = pendingCode ?: return
        var code by remember(request) { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            request.challenge.instructions?.takeIf { it.isNotBlank() }?.let { Text(it) }
            request.challenge.url?.let { url ->
                TextButton(onClick = {
                    runCatching { openUri(url) }.onFailure { request.result.completeExceptionally(it) }
                }) {
                    Text(stringResource(R.string.server_provider_open_auth))
                }
            }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.server_provider_auth_code)) },
                singleLine = true,
                keyboardOptions = rawAuthorizationInput,
            )
            Button(enabled = code.isNotBlank(), onClick = { request.result.complete(code.trim()) }) {
                Text(stringResource(R.string.server_provider_submit_code))
            }
        }
    }
}

private val rawAuthorizationInput = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
)

@Composable
internal fun OpenCodeProviderCredentialNotices(insecure: Boolean, writesApiKey: Boolean) {
    if (insecure) {
        Text(stringResource(R.string.server_provider_config_http_warning),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (writesApiKey) {
        Text(stringResource(R.string.server_provider_config_key_warning),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
