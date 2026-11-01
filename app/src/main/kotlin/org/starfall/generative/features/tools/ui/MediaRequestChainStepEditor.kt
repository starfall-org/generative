package org.starfall.generative.features.tools.ui

import org.starfall.generative.shared.ui.menus.DropdownMenu
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.R
import org.starfall.generative.shared.model.media.MediaFileTransport
import org.starfall.generative.shared.model.media.MediaFinalResponse
import org.starfall.generative.shared.model.media.MediaRequestMode
import org.starfall.generative.shared.model.media.MediaRequestChain
import org.starfall.generative.shared.model.media.MediaRequestStep
import org.starfall.generative.shared.model.media.MediaResponseObservation
import org.starfall.generative.shared.model.media.MediaResponseObservationMode
import org.starfall.generative.shared.model.media.MediaResponseType
import org.starfall.generative.shared.model.media.MediaRetryPolicy
import org.starfall.generative.shared.model.media.MediaValueKind
import org.starfall.generative.shared.helpers.media.isValidMediaEndpointTemplate
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.text.OutlinedTextField
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon

private const val MAX_ATTEMPTS = 20
private const val MAX_DELAY_MILLIS = 300_000L
private const val MAX_WAIT_MILLIS = 3_600_000L
private val requestMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")

@Composable
internal fun MediaRequestStepEditor(
    stepNumber: Int,
    step: MediaRequestStep,
    previousSteps: List<MediaRequestStep> = emptyList(),
    testedResponsePaths: Map<Int, List<String>> = emptyMap(),
    onChange: (MediaRequestStep) -> Unit
) {
    var advanced by remember(stepNumber) { mutableStateOf(false) }
    SettingsCard {
        Text(stringResource(R.string.media_chain_request_details), style = MaterialTheme.typography.titleMedium)
        MediaTemplateAutocompleteField(
            value = step.endpoint,
            onValueChange = { onChange(step.copy(endpoint = it)) },
            stepNumber = stepNumber,
            previousSteps = previousSteps,
            testedResponsePaths = testedResponsePaths,
            label = { Text(stringResource(R.string.media_chain_endpoint)) },
            supportingText = { Text(stringResource(R.string.media_chain_endpoint_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        MediaChoiceField(
            label = stringResource(R.string.media_chain_method),
            selected = step.method,
            options = requestMethods,
            optionLabel = { it },
            onSelect = { onChange(step.copy(method = it)) }
        )
        MediaChoiceField(
            label = stringResource(R.string.media_chain_content_type),
            selected = modeLabel(step.mode),
            options = MediaRequestMode.entries,
            optionLabel = { mode -> modeLabel(mode) },
            onSelect = { onChange(step.copy(mode = it)) }
        )
    }
    SettingsCard {
        MediaRequestHeadersEditor(stepNumber, step.headers, previousSteps, testedResponsePaths) { headers ->
            onChange(step.copy(headers = headers))
        }
    }
    SettingsCard {
        Text(stringResource(R.string.media_chain_body_editor), style = MaterialTheme.typography.titleMedium)
        MediaTemplateAutocompleteField(
            value = step.body,
            onValueChange = { onChange(step.copy(body = it)) },
            stepNumber = stepNumber,
            previousSteps = previousSteps,
            testedResponsePaths = testedResponsePaths,
            supportingText = { Text(stringResource(R.string.media_chain_request_body_help)) },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            minLines = 6,
            maxLines = 16,
            modifier = Modifier.fillMaxWidth()
        )
    }
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.media_chain_advanced_request), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.media_chain_advanced_request_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { advanced = !advanced }) {
                SharedIcon(if (advanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    stringResource(R.string.media_chain_advanced_request))
            }
        }
        if (advanced) {
            MediaChoiceField(
                label = stringResource(R.string.media_chain_file_transport),
                selected = fileTransportLabel(step.fileTransport),
                options = MediaFileTransport.entries,
                optionLabel = { transport -> fileTransportLabel(transport) },
                onSelect = { onChange(step.copy(fileTransport = it)) }
            )
            OutlinedTextField(
                value = step.contentType,
                onValueChange = { onChange(step.copy(contentType = it)) },
                label = { Text(stringResource(R.string.media_chain_custom_content_type)) },
                supportingText = { Text(stringResource(R.string.media_chain_custom_content_type_help)) },
                keyboardOptions = mediaLiteralKeyboardOptions,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    SettingsCard {
        ResponseObserverEditor(stepNumber, step.observation) { observation ->
            onChange(step.copy(observation = observation))
        }
    }
}

@Composable
private fun ResponseObserverEditor(
    stepNumber: Int,
    observation: MediaResponseObservation,
    onChange: (MediaResponseObservation) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.media_chain_observer), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(
                checked = observation.isEnabled,
                onCheckedChange = { onChange(observation.copy(enabled = it)) }
            )
        }
        if (observation.isEnabled) {
            OutlinedTextField(
                value = observation.statusPath,
                onValueChange = { onChange(observation.copy(statusPath = it)) },
                label = { Text(stringResource(R.string.media_chain_status_path)) },
                supportingText = { Text(stringResource(R.string.media_chain_status_path_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            MediaChoiceField(
                label = stringResource(R.string.media_chain_success_condition),
                selected = observationModeLabel(observation.mode),
                options = MediaResponseObservationMode.entries,
                optionLabel = { mode -> observationModeLabel(mode) },
                onSelect = { onChange(observation.copy(mode = it)) }
            )
            if (observation.mode == MediaResponseObservationMode.VALUE) {
                ObservationValuesField(stepNumber, "pending", R.string.media_chain_pending_values, observation.pendingValues) {
                    onChange(observation.copy(pendingValues = it))
                }
            }
            RetryPolicyEditor(stepNumber, observation.retry) { onChange(observation.copy(retry = it)) }
        } else {
            Text(
                stringResource(R.string.media_chain_observer_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ObservationValuesField(
    stepNumber: Int,
    kind: String,
    label: Int,
    values: List<String>,
    onChange: (List<String>) -> Unit
) {
    var text by remember(stepNumber, kind) { mutableStateOf(values.joinToString(", ")) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.split(',').map(String::trim).filter(String::isNotBlank))
        },
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(R.string.media_chain_values_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RetryPolicyEditor(
    stepNumber: Int,
    retry: MediaRetryPolicy,
    onChange: (MediaRetryPolicy) -> Unit
) {
    var attempts by remember(stepNumber) { mutableStateOf(retry.maxAttempts.toString()) }
    var delay by remember(stepNumber) { mutableStateOf(retry.delayMillis.toString()) }
    var wait by remember(stepNumber) { mutableStateOf(retry.maxWaitMillis.toString()) }
    Text(stringResource(R.string.media_chain_retry_policy), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        RetryNumberField(
            value = attempts,
            label = stringResource(R.string.media_chain_max_attempts),
            bounds = "1–$MAX_ATTEMPTS",
            modifier = Modifier.weight(1f),
            onValueChange = {
                attempts = boundedNumberText(it, 1, MAX_ATTEMPTS.toLong())
                onChange(retry.copy(maxAttempts = attempts.toIntOrNull() ?: 1))
            }
        )
        RetryNumberField(
            value = delay,
            label = stringResource(R.string.media_chain_delay_millis),
            bounds = "0–$MAX_DELAY_MILLIS",
            modifier = Modifier.weight(1f),
            onValueChange = {
                delay = boundedNumberText(it, 0, MAX_DELAY_MILLIS)
                onChange(retry.copy(delayMillis = delay.toLongOrNull() ?: 0L))
            }
        )
    }
    RetryNumberField(
        value = wait,
        label = stringResource(R.string.media_chain_max_wait_millis),
        bounds = "0–$MAX_WAIT_MILLIS",
        modifier = Modifier.fillMaxWidth(),
        onValueChange = {
            wait = boundedNumberText(it, 0, MAX_WAIT_MILLIS)
            onChange(retry.copy(maxWaitMillis = wait.toLongOrNull() ?: 0L))
        }
    )
}

@Composable
private fun RetryNumberField(
    value: String,
    label: String,
    bounds: String,
    modifier: Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter(Char::isDigit)) },
        label = { Text(label) },
        supportingText = { Text(bounds) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier
    )
}

private fun boundedNumberText(value: String, minimum: Long, maximum: Long): String =
    if (value.isBlank()) "" else value.toLongOrNull()?.coerceIn(minimum, maximum)?.toString() ?: maximum.toString()

@Composable
internal fun MediaFinalResponseEditor(
    finalResponse: MediaFinalResponse,
    stepCount: Int,
    onChange: (MediaFinalResponse) -> Unit,
    music: Boolean = false
) {
    SettingsCard {
        Text(stringResource(R.string.media_chain_final_response), style = MaterialTheme.typography.titleMedium)
        val sourceSteps = listOf<Int?>(null) + (1..stepCount).toList()
        MediaChoiceField(
            label = stringResource(R.string.media_chain_final_source_step),
            selected = finalSourceStepLabel(finalResponse.sourceStep),
            options = sourceSteps,
            optionLabel = { step -> finalSourceStepLabel(step) },
            onSelect = { onChange(finalResponse.copy(sourceStep = it)) }
        )
        MediaChoiceField(
            label = stringResource(R.string.media_chain_response_type),
            selected = responseTypeLabel(finalResponse.type),
            options = if (music) listOf(MediaResponseType.JSON, MediaResponseType.AUDIO) else MediaResponseType.entries,
            optionLabel = { type -> responseTypeLabel(type) },
            onSelect = { type ->
                onChange(finalResponse.copy(
                    type = type,
                    sourceStep = finalResponse.sourceStep.takeIf { type == MediaResponseType.JSON }
                ))
            }
        )
        if (finalResponse.type == MediaResponseType.JSON) {
            OutlinedTextField(
                value = finalResponse.mediaPath,
                onValueChange = { onChange(finalResponse.copy(mediaPath = it)) },
                label = { Text(stringResource(R.string.media_chain_media_path)) },
                supportingText = { Text(stringResource(R.string.media_chain_media_path_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            MediaChoiceField(
                label = stringResource(R.string.media_chain_value_format),
                selected = valueKindLabel(finalResponse.valueKind),
                options = MediaValueKind.entries,
                optionLabel = { kind -> valueKindLabel(kind) },
                onSelect = { onChange(finalResponse.copy(valueKind = it)) }
            )
        }
    }
}

@Composable
private fun <T> MediaChoiceField(
    label: String,
    selected: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected, modifier = Modifier.weight(1f))
                SharedIcon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = { onSelect(option); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun modeLabel(mode: MediaRequestMode) = stringResource(
    when (mode) {
        MediaRequestMode.JSON -> R.string.media_chain_content_type_json
        MediaRequestMode.FORM -> R.string.media_chain_content_type_form
        MediaRequestMode.RAW -> R.string.media_chain_content_type_raw
    }
)

@Composable
private fun fileTransportLabel(transport: MediaFileTransport) = stringResource(
    when (transport) {
        MediaFileTransport.FILE -> R.string.media_chain_file
        MediaFileTransport.BASE64 -> R.string.media_chain_base64
        MediaFileTransport.RAW -> R.string.common_raw
    }
)

@Composable
private fun responseTypeLabel(type: MediaResponseType) = stringResource(
    when (type) {
        MediaResponseType.JSON -> R.string.media_chain_json
        MediaResponseType.IMAGE -> R.string.common_image
        MediaResponseType.VIDEO -> R.string.common_video
        MediaResponseType.AUDIO -> R.string.music_audio
        MediaResponseType.OCTET_STREAM -> R.string.media_chain_binary_file
    }
)

@Composable
private fun valueKindLabel(kind: MediaValueKind) = stringResource(
    if (kind == MediaValueKind.LINK) R.string.media_chain_link else R.string.media_chain_base64
)

@Composable
private fun finalSourceStepLabel(step: Int?): String = if (step == null) {
    stringResource(R.string.media_chain_last_step)
} else {
    stringResource(R.string.media_chain_step, step)
}

@Composable
private fun observationModeLabel(mode: MediaResponseObservationMode) = stringResource(
    if (mode == MediaResponseObservationMode.PATH_EXISTS) {
        R.string.media_chain_observer_path_exists
    } else {
        R.string.media_chain_observer_read_value
    }
)

internal fun chainValidationError(chain: MediaRequestChain): Int? {
    if (chain.steps.isEmpty()) return R.string.media_chain_one_step_required
    if (chain.steps.any { step ->
            !isValidMediaEndpointTemplate(step.endpoint) ||
                step.method !in requestMethods ||
                step.headers.keys.any { key -> key.trim().isBlank() || key.any { it <= ' ' || it == ':' || it.code >= 127 } } ||
                step.headers.values.any { value -> value.contains('\r') || value.contains('\n') } ||
                (step.mode != MediaRequestMode.RAW && runCatching { Json.parseToJsonElement(step.body.ifBlank { "{}" }) }.getOrNull() == null) ||
                (step.mode == MediaRequestMode.FORM && runCatching { Json.parseToJsonElement(step.body.ifBlank { "{}" }) }.getOrNull() !is JsonObject) ||
                (step.observation.isEnabled && step.observation.statusPath.isBlank()) ||
                (step.observation.isEnabled &&
                    step.observation.mode == MediaResponseObservationMode.VALUE &&
                    step.observation.pendingValues.isEmpty()) ||
                step.observation.retry.maxAttempts !in 1..MAX_ATTEMPTS ||
                step.observation.retry.delayMillis !in 0..MAX_DELAY_MILLIS ||
                step.observation.retry.maxWaitMillis !in 0..MAX_WAIT_MILLIS
         }) return R.string.media_chain_invalid_configuration
    if (chain.finalResponse.type != MediaResponseType.JSON && chain.steps.last().observation.isEnabled) {
        return R.string.media_chain_invalid_configuration
    }
    if (chain.finalResponse.sourceStep != null && chain.finalResponse.sourceStep !in 1..chain.steps.size) {
        return R.string.media_chain_invalid_configuration
    }
    if (chain.finalResponse.type != MediaResponseType.JSON &&
        chain.finalResponse.sourceStep != null &&
        chain.finalResponse.sourceStep != chain.steps.size
    ) {
        return R.string.media_chain_invalid_configuration
    }
    if (chain.finalResponse.type == MediaResponseType.JSON && chain.finalResponse.mediaPath.isBlank()) {
        return R.string.media_chain_media_path_required
    }
    return null
}
