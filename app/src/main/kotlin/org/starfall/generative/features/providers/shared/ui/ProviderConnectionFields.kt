package org.starfall.generative.features.providers.shared.ui

import org.starfall.generative.shared.ui.menus.DropdownMenu
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.Box
import org.starfall.generative.R
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.DEFAULT_ANTIGRAVITY_CHANGELIST
import org.starfall.generative.shared.model.provider.DEFAULT_ANTIGRAVITY_VERSION
import org.starfall.generative.shared.model.provider.DEFAULT_CODEX_CLIENT_VERSION
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.ui.text.ApiKeyField
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.text.OutlinedTextField

private val literalInputKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password
)

@Composable
private fun AuthMethod.legacyDisplayName(): String = when (this) {
    AuthMethod.PLATFORM_DEFAULT -> stringResource(R.string.provider_auth_platform_default)
    AuthMethod.NONE, AuthMethod.OTHER -> stringResource(R.string.common_none)
    AuthMethod.BEARER_TOKEN, AuthMethod.CUSTOM_HEADER -> stringResource(R.string.bearer_token)
    AuthMethod.QUERY_PARAM -> stringResource(R.string.url_query)
    AuthMethod.OAUTH -> stringResource(R.string.oauth_flow)
}

@Composable
private fun AuthMethod.displayNameFallback(): String = when (this) {
    AuthMethod.BEARER_TOKEN -> stringResource(R.string.bearer_token)
    AuthMethod.CUSTOM_HEADER -> stringResource(R.string.provider_auth_custom_header)
    else -> legacyDisplayName()
}

@Composable
fun AuthMethod.displayName(): String = when (this) {
    AuthMethod.BEARER_TOKEN -> stringResource(R.string.provider_auth_bearer_type)
    AuthMethod.CUSTOM_HEADER -> stringResource(R.string.provider_auth_custom_header)
    else -> displayNameFallback()
}

@Composable
internal fun ProviderBaseUrlField(type: ModelApiType, value: String, valid: Boolean, onChange: (String) -> Unit) {
    val defaultUrl = if (type == ModelApiType.OPENAI) "https://api.openai.com/v1" else type.defaultBaseUrl
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(R.string.provider_base_url)) },
        isError = !valid,
        supportingText = if (!valid) ({ Text(stringResource(R.string.provider_invalid_base_url)) }) else null,
        trailingIcon = {
            IconButton(onClick = { onChange(defaultUrl) }) {
                SharedIcon(Icons.Outlined.Language, stringResource(R.string.provider_restore_default_url))
            }
        },
        keyboardOptions = literalInputKeyboardOptions,
        visualTransformation = VisualTransformation.None,
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun CodexClientVersionField(value: String, valid: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(R.string.provider_codex_client_version)) },
        placeholder = { Text(DEFAULT_CODEX_CLIENT_VERSION) },
        supportingText = if (!valid) ({ Text(stringResource(R.string.provider_codex_client_version_invalid,
            DEFAULT_CODEX_CLIENT_VERSION)) }) else null,
        isError = !valid,
        keyboardOptions = literalInputKeyboardOptions,
        visualTransformation = VisualTransformation.None,
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun AntigravityVersionFields(
    version: String,
    versionValid: Boolean,
    onVersionChange: (String) -> Unit,
    changelist: String,
    changelistValid: Boolean,
    onChangelistChange: (String) -> Unit
) {
    OutlinedTextField(
        value = version, onValueChange = onVersionChange,
        label = { Text(stringResource(R.string.provider_antigravity_version)) },
        placeholder = { Text(DEFAULT_ANTIGRAVITY_VERSION) },
        supportingText = if (!versionValid) ({ Text(stringResource(R.string.provider_antigravity_version_invalid,
            DEFAULT_ANTIGRAVITY_VERSION)) }) else null,
        isError = !versionValid,
        keyboardOptions = literalInputKeyboardOptions,
        visualTransformation = VisualTransformation.None,
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = changelist, onValueChange = onChangelistChange,
        label = { Text(stringResource(R.string.provider_antigravity_changelist)) },
        placeholder = { Text(DEFAULT_ANTIGRAVITY_CHANGELIST) },
        supportingText = if (!changelistValid) ({ Text(stringResource(R.string.provider_antigravity_changelist_invalid)) }) else null,
        isError = !changelistValid,
        keyboardOptions = literalInputKeyboardOptions,
        visualTransformation = VisualTransformation.None,
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun AntigravityProjectIdField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(R.string.provider_antigravity_project_id)) },
        keyboardOptions = literalInputKeyboardOptions,
        visualTransformation = VisualTransformation.None,
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("antigravity-project-id")
    )
}

@Composable
internal fun GeminiCliProjectIdField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(R.string.provider_gemini_cli_project_id)) },
        keyboardOptions = literalInputKeyboardOptions,
        visualTransformation = VisualTransformation.None,
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("gemini-cli-project-id")
    )
}

@Composable
internal fun ProviderAuthField(
    method: AuthMethod,
    value: String,
    canSelect: Boolean,
    onMethodChange: (AuthMethod) -> Unit,
    onValueChange: (String) -> Unit,
    onFocusLost: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasKey = method !in listOf(AuthMethod.OAUTH, AuthMethod.NONE, AuthMethod.OTHER)
    ApiKeyField(
        value = if (hasKey) value else "", onValueChange = onValueChange,
        label = {
            Text(
                if (hasKey) stringResource(R.string.provider_api_key_label, method.displayName())
                else method.displayName()
            )
        }, readOnly = !hasKey,
        leadingIcon = if (canSelect) ({
            Box {
                IconButton(onClick = { expanded = true }) {
                    SharedIcon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        stringResource(R.string.provider_select_auth_type)
                    )
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    listOf(AuthMethod.PLATFORM_DEFAULT, AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM, AuthMethod.OAUTH, AuthMethod.NONE).forEach { option ->
                        DropdownMenuItem(text = { Text(option.displayName()) }, onClick = {
                            onMethodChange(option)
                            expanded = false
                        })
                    }
                }
            }
        }) else null,
        visibilityKey = method,
        maxLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provider_api_key")
            .onFocusChanged { if (!it.isFocused) onFocusLost() }
    )
}
