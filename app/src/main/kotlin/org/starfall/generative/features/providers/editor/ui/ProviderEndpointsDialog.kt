package org.starfall.generative.features.providers.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

internal fun endpointFields(type: ModelApiType): List<Pair<String, String>> = when (type) {
    ModelApiType.ANTHROPIC -> listOf("catalog" to "Model catalog", "messages" to "Messages")
    ModelApiType.OLLAMA -> listOf("catalog" to "Model catalog", "chat" to "Chat")
    ModelApiType.GOOGLE -> listOf("catalog" to "Model catalog", "generate" to "Generate ({model}, {method})", "operation" to "Video operation ({operation})")
    ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES -> listOf(
        "catalog" to "Model catalog", (if (type == ModelApiType.OPENAI) "chat" to "Chat completions" else "responses" to "Responses"),
        "speech" to "Text to speech", "imageGeneration" to "Image generation", "imageEdit" to "Image edits", "video" to "Video generation", "videoStatus" to "Video status ({id})", "videoContent" to "Video content ({id})"
    )
    else -> emptyList()
}

@Composable
internal fun ProviderEndpointsDialog(type: ModelApiType, baseUrl: String, endpoints: Map<String, String>, onChange: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.provider_custom_endpoints)) }, content = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.provider_custom_endpoints_hint), style = MaterialTheme.typography.bodySmall) }
            items(endpointFields(type), key = { it.first }) { (key, label) ->
                val value = endpoints[key].orEmpty()
                val valid = value.isBlank() || validEndpoint(value, key)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(value, { onChange(endpoints + (key to it)) },
                        label = { Text(defaultEndpoint(type, baseUrl, key, endpoints).let { endpoint ->
                            val base = baseUrl.trim().trimEnd('/')
                            if (base.isNotEmpty() && endpoint.startsWith("$base/")) endpoint.removePrefix(base) else endpoint
                        }, maxLines = 1) },
                        modifier = Modifier.fillMaxWidth(), isError = !valid,
                        trailingIcon = { IconButton(onClick = { onChange(endpoints - key) }, enabled = value.isNotEmpty()) {
                            SharedIcon(Icons.Default.Close, stringResource(R.string.common_clear))
                        } },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
                }
            }
        }
    }, buttons = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } })
}

internal fun validEndpoint(value: String, key: String): Boolean {
    val required = when (key) { "operation" -> listOf("{operation}"); "videoStatus", "videoContent" -> listOf("{id}"); else -> emptyList() }
    val expanded = (required + listOf("{model}", "{method}")).fold(value) { url, token -> url.replace(token, "example") }
    val url = okhttp3.HttpUrl.Companion.run { expanded.toHttpUrlOrNull() } ?: return false
    return url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        required.all { it in value }
}

/** Mirrors the provider routes used when an override is absent. */
internal fun defaultEndpoint(type: ModelApiType, baseUrl: String, key: String, endpoints: Map<String, String>): String {
    var base = baseUrl.trim().trimEnd('/')
    for (suffix in listOf("/chat/completions", "/responses", "/messages", "/models", "/chat", "/tags", "/generate")) {
        base = base.removeSuffix(suffix)
    }
    val googleRoot = base
    val ollamaRoot = base.removeSuffix("/api").removeSuffix("/v1") + "/api"
    val videoRoot = endpoints["video"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "${base.removeSuffix("/videos")}/videos"
    return when (key) {
        "catalog" -> when (type) {
            ModelApiType.OLLAMA -> "$ollamaRoot/tags"
            ModelApiType.ANTHROPIC -> "$base/models"
            ModelApiType.GOOGLE -> "$googleRoot/models"
            else -> "$base/models"
        }
        "chat" -> if (type == ModelApiType.OLLAMA) (if (base.endsWith("/api")) "$base/chat" else "$base/api/chat") else "$base/chat/completions"
        "messages" -> "$base/messages"
        "responses" -> "$base/responses"
        "generate" -> "$googleRoot/models/{model}:{method}"
        "operation" -> "$googleRoot/{operation}"
        "speech" -> "$base/audio/speech"
        "imageGeneration" -> "$base/images/generations"
        "imageEdit" -> "$base/images/edits"
        "video" -> "${base.removeSuffix("/videos")}/videos"
        "videoStatus" -> "$videoRoot/{id}"
        "videoContent" -> "$videoRoot/{id}/content"
        else -> ""
    }
}
