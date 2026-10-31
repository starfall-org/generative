package org.starfall.generative.features.home.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.language.passbackMode
import org.starfall.generative.shared.ui.icons.EntityIcon
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.model.modelInitial

/** Linked model-settings page. It stays in the same modal sheet and retains its parent overview. */
@Composable
internal fun SelectedModelOptionsSheetContent(
    modelId: String,
    config: ModelConfiguration,
    providerName: String?,
    onBack: () -> Unit,
    onSetThinkingPassback: ((ThinkingPassback) -> Unit)? = null,
    onSetModelTools: ((List<String>) -> Unit)? = null
) {
    val visibleTools = remember(config.supportedModelTools) { config.supportedModelTools.distinct() }
    Column(Modifier.fillMaxWidth().testTag("selected-model-options-sheet")) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .testTag("model-options-appbar")) {
            QuickActionsSubpageHeader(
                title = stringResource(R.string.model_options_title),
                onBack = onBack
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EntityIcon(
                        image = config.icon,
                        modifier = Modifier.size(44.dp),
                        text = modelInitial(modelId),
                        matchName = config.displayName.ifBlank { modelId },
                        fallbackMatchName = modelId,
                        model = true
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(config.displayName.ifBlank { modelId },
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        providerName?.takeIf { it.isNotBlank() }?.let { name ->
                            Text(name, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (config.supportsThinking) {
                SettingsCard {
                    Text(stringResource(R.string.send_thinking_content_back),
                        style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.send_thinking_content_back_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val modes = listOf(ThinkingPassback.AUTO, ThinkingPassback.ON, ThinkingPassback.OFF)
                    val labels = listOf(
                        stringResource(R.string.common_auto),
                        stringResource(R.string.common_on),
                        stringResource(R.string.common_off)
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("passback-thinking-mode")) {
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = config.passbackMode == mode,
                                onClick = { onSetThinkingPassback?.invoke(mode) },
                                enabled = onSetThinkingPassback != null,
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                modifier = Modifier.testTag("passback-thinking-${mode.name.lowercase()}")
                            ) { Text(labels[index], maxLines = 1) }
                        }
                    }
                }
            }
            if (visibleTools.isNotEmpty()) {
                SettingsCard {
                    Text(stringResource(R.string.model_tools_title),
                        style = MaterialTheme.typography.titleSmall)
                    visibleTools.forEach { tool ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .testTag("model-tool-$tool"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(modelToolDisplayName(tool),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Switch(
                                checked = tool in config.enabledModelTools,
                                onCheckedChange = { enabled ->
                                    val current = config.enabledModelTools.filter { it in visibleTools }.toSet()
                                    onSetModelTools?.invoke((if (enabled) current + tool else current - tool).toList())
                                },
                                enabled = onSetModelTools != null,
                                modifier = Modifier.testTag("model-tool-switch-$tool")
                            )
                        }
                    }
                }
            }
            if (!config.supportsThinking && visibleTools.isEmpty()) {
                Text(stringResource(R.string.model_options_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                        .testTag("model-options-empty"))
            }
        }
    }
}

@Composable
private fun modelToolDisplayName(tool: String): String = when (tool.lowercase()) {
    "google_search", "web_search" -> stringResource(R.string.model_tool_web_search)
    "google_maps" -> stringResource(R.string.model_tool_google_maps)
    "url_context", "web_fetch" -> stringResource(R.string.model_tool_url_context)
    "code_execution", "code_interpreter" -> stringResource(R.string.model_tool_code_execution)
    "file_search" -> stringResource(R.string.model_tool_file_search)
    "image_generation" -> stringResource(R.string.tool_image_generation_title)
    "computer_use", "computer" -> stringResource(R.string.model_tool_computer_use)
    else -> tool.split('_', '-', '.').filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
