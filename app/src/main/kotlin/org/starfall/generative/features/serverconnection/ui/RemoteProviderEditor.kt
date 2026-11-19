package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.starfall.generative.R
import org.starfall.generative.integrations.opencode.OpenCodeProviderConfigFile
import org.starfall.generative.integrations.opencode.openCodeProviderConfig
import org.starfall.generative.integrations.opencode.openCodeProviderTypes
import org.starfall.generative.integrations.opencode.validOpenCodeProviderId
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.providers.*

/** Same provider configuration controls, with a server-owned draft and save action. */
@Composable
internal fun RemoteProviderEditor(file: OpenCodeProviderConfigFile?, existingIds: Set<String>,
    busy: Boolean, failed: Boolean, insecure: Boolean, onBack: () -> Unit,
    onSave: (String, JsonObject) -> Unit) {
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ModelApiType.OPENAI) }
    var baseUrl by remember { mutableStateOf(ModelApiType.OPENAI_RESPONSES.defaultBaseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    val providerId = id.trim()
    val modelIds = models.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val config = remember(name, type, baseUrl, apiKey, models) {
        openCodeProviderConfig(name, type, baseUrl, apiKey, modelIds)
    }
    val validId = validOpenCodeProviderId(providerId) && providerId !in existingIds
    val literal = KeyboardOptions(capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
    ProviderEditorLayout(title = { Text(stringResource(R.string.add_provider)) },
        onBack = { if (!busy) onBack() }, actions = {
            Button(enabled = file != null && validId && config != null && !busy,
                onClick = { onSave(providerId, requireNotNull(config)) }, modifier = Modifier.padding(end = 8.dp)) {
                Text(stringResource(R.string.common_save))
            }
        }) {
        if (file == null) {
            if (failed) Text(stringResource(R.string.server_provider_config_failed), color = MaterialTheme.colorScheme.error)
            else CircularProgressIndicator()
            return@ProviderEditorLayout
        }
        Text(file.path, style = MaterialTheme.typography.bodySmall)
        if (insecure) Text(stringResource(R.string.server_provider_config_http_warning),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        ProviderNameField(name, type) { name = it }
        OutlinedTextField(id, { id = it }, label = { Text(stringResource(R.string.server_provider_id)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("remote_provider_id"),
            isError = id.isNotBlank() && !validId, supportingText = if (id.isNotBlank() && !validId)
                ({ Text(stringResource(R.string.server_provider_invalid_id)) }) else null,
            keyboardOptions = literal, visualTransformation = VisualTransformation.None)
        ProviderApiTypeSelector(type, openCodeProviderTypes) { next ->
            val previousDefault = if (type == ModelApiType.OPENAI) ModelApiType.OPENAI_RESPONSES.defaultBaseUrl else type.defaultBaseUrl
            if (baseUrl == previousDefault || baseUrl.isBlank())
                baseUrl = if (next == ModelApiType.OPENAI) ModelApiType.OPENAI_RESPONSES.defaultBaseUrl else next.defaultBaseUrl
            type = next
        }
        ProviderBaseUrlField(type, baseUrl, baseUrl.trim().toHttpUrlOrNull() != null) { baseUrl = it }
        ProviderCredentialField(apiKey, { apiKey = it },
            label = { Text(stringResource(R.string.provider_api_key_value)) }, visibilityKey = type)
        if (apiKey.isNotBlank()) Text(stringResource(R.string.server_provider_config_key_warning),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(models, { models = it }, label = { Text(stringResource(R.string.provider_models)) },
            supportingText = { Text(stringResource(R.string.server_provider_models_hint)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).testTag("remote_provider_models"),
            keyboardOptions = literal, visualTransformation = VisualTransformation.None)
        if (failed) Text(stringResource(R.string.server_provider_config_failed), color = MaterialTheme.colorScheme.error)
    }
}
