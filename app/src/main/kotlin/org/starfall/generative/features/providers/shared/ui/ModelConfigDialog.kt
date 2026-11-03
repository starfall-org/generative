package org.starfall.generative.features.providers.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import androidx.compose.ui.res.stringResource
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

@Composable
fun ModelConfigDialog(
    provider: ModelProvider,
    modelId: String,
    onSave: (ModelConfiguration) -> Unit,
    onDismiss: () -> Unit
) {
    val config = provider.config.modelConfigs[modelId] ?: ModelConfiguration()
    var temperature by remember { mutableStateOf(config.temperature?.toString().orEmpty()) }
    var topP by remember { mutableStateOf(config.topP?.toString().orEmpty()) }
    var topK by remember { mutableStateOf(config.topK?.toString().orEmpty()) }
    val supportsTopK = !provider.type.isOpenAi
    val tempMax = if (provider.type == ModelApiType.ANTHROPIC) 1.0 else 2.0
    val tempValid = temperature.isBlank() || temperature.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..tempMax } == true
    val topPValid = topP.isBlank() || topP.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..1.0 } == true
    val topKValid = !supportsTopK || topK.isBlank() || topK.toIntOrNull()?.let { it > 0 } == true

    Dialog(
        onDismissRequest = onDismiss,
        title = { Text(modelId, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(provider.name, style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.model_stream_settings_description))
                OutlinedTextField(temperature, { temperature = it }, label = { Text(stringResource(R.string.model_temperature_label, tempMax)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                     supportingText = { if (!tempValid) Text(stringResource(R.string.model_temperature_invalid, tempMax)) },
                    isError = !tempValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(topP, { topP = it }, label = { Text(stringResource(R.string.model_top_p_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                     supportingText = { if (!topPValid) Text(stringResource(R.string.model_top_p_invalid)) },
                    isError = !topPValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (supportsTopK) {
                    OutlinedTextField(topK, { topK = it }, label = { Text(stringResource(R.string.model_top_k_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                         supportingText = { if (!topKValid) Text(stringResource(R.string.model_top_k_invalid)) },
                        isError = !topKValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        buttons = {
            TextButton(onClick = { temperature = ""; topP = ""; topK = "" }) {
                SharedIcon(Icons.Outlined.RestartAlt, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.model_reset_sampling))
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            Button(enabled = tempValid && topPValid && topKValid, onClick = {
                onSave(config.copy(temperature = temperature.toDoubleOrNull(), topP = topP.toDoubleOrNull(),
                    topK = if (supportsTopK) topK.toIntOrNull() else null))
                onDismiss()
            }) { Text(stringResource(R.string.common_save)) }
        }
    )
}


@Composable
fun ModelToolsDialog(
    provider: ModelProvider,
    modelId: String,
    config: ModelConfiguration,
    onSave: (ModelConfiguration) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultTools = listOf(
        "google_search", "google_maps", "url_context", "code_execution",
        "web_search", "code_interpreter", "image_generation", "web_fetch"
    )
    val modelName = modelId.lowercase()
    val presetTools = when {
        modelName.contains("gemini") -> listOf("google_search", "google_maps", "url_context", "code_execution")
        modelName.contains("gpt") -> listOf("web_search", "code_interpreter", "image_generation")
        modelName.contains("claude") -> listOf("web_search", "web_fetch", "code_execution")
        else -> emptyList()
    }
    val initialTools = remember(modelId, config.modelTools, config.modelJson) {
        (config.modelTools.ifEmpty { presetTools.ifEmpty { config.supportedModelTools } } + defaultTools)
            .distinct()
    }
    val toolNames = remember(modelId, config.modelTools, config.modelJson) {
        mutableStateListOf<String>().apply { addAll(initialTools) }
    }
    val enabledTools = remember(modelId, config.enabledModelTools, config.modelTools, config.modelJson) {
        mutableStateOf(
            config.enabledModelTools.toSet().ifEmpty {
                (config.modelTools.ifEmpty { presetTools }).toSet()
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_tools_title)) },
        scrollableContent = false,
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(modelId, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(provider.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                toolNames.forEachIndexed { index, tool ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (tool !in defaultTools) {
                            OutlinedTextField(
                                value = tool,
                                onValueChange = { toolNames[index] = it },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.model_tool_name)) },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Password
                                ),
                                singleLine = true
                            )
                        } else {
                            Text(tool, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(
                            checked = tool.isNotBlank() && tool in enabledTools.value,
                            enabled = tool.isNotBlank(),
                            onCheckedChange = { checked ->
                                enabledTools.value = if (checked) enabledTools.value + tool else enabledTools.value - tool
                            },
                            modifier = Modifier.testTag("editor-model-tool-$index")
                        )
                        if (tool !in defaultTools) {
                            IconButton(onClick = {
                                enabledTools.value = enabledTools.value - tool
                                toolNames.removeAt(index)
                            }) {
                                Text("×", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { toolNames.add("") },
                    modifier = Modifier.fillMaxWidth().testTag("model-tool-add-button")
                ) {
                    Text(stringResource(R.string.common_add))
                }
            }
        },
        confirmAtRight = true,
        confirmButton = {
            Button(onClick = {
                val available = toolNames.map(String::trim).filter(String::isNotEmpty).distinct()
                onSave(config.copy(
                    modelTools = available,
                    enabledModelTools = enabledTools.value.intersect(available.toSet()).toList()
                ))
                onDismiss()
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}
