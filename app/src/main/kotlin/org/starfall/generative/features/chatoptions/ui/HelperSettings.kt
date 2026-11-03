package org.starfall.generative.features.chatoptions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.DEFAULT_CHAT_SUMMARY_PROMPT
import org.starfall.generative.shared.model.DEFAULT_TITLE_GENERATION_PROMPT
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.storage.preferences.AppPreferences
import org.starfall.generative.shared.storage.preferences.MAX_AUTO_SUMMARY_PERCENT
import org.starfall.generative.shared.storage.preferences.MIN_AUTO_SUMMARY_PERCENT
import org.starfall.generative.shared.model.MAX_TITLE_GENERATION_MIN_MESSAGES
import org.starfall.generative.shared.model.MAX_TITLE_GENERATION_MIN_WORDS
import org.starfall.generative.shared.model.MIN_TITLE_GENERATION_MIN_MESSAGES
import org.starfall.generative.shared.model.MIN_TITLE_GENERATION_MIN_WORDS
import org.starfall.generative.shared.ui.sheets.BottomSheet
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

@Composable
internal fun HelperSettingsSheet(
    name: String,
    config: SystemToolConfig,
    appPreferences: AppPreferences,
    onSave: (SystemToolConfig) -> Unit,
    onAutoSummaryEnabledChange: (Boolean) -> Unit,
    onSummarySuggestionEnabledChange: (Boolean) -> Unit,
    onAutoSummaryPercentChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val isSummary = name == "chat_summary"
    val title = stringResource(
        if (isSummary) R.string.tool_chat_summary_title else R.string.tool_title_generation_title
    )
    val defaultPrompt = if (isSummary) DEFAULT_CHAT_SUMMARY_PROMPT else DEFAULT_TITLE_GENERATION_PROMPT
    var prompt by rememberSaveable(name) {
        mutableStateOf(config.prompt.ifBlank { defaultPrompt })
    }
    var minimumMessages by rememberSaveable(name) {
        mutableStateOf(config.titleGenerationMinMessages.toString())
    }
    var minimumWords by rememberSaveable(name) {
        mutableStateOf(config.titleGenerationMinWords.toString())
    }
    val parsedMinimumMessages = minimumMessages.toIntOrNull()
        ?.takeIf { it in MIN_TITLE_GENERATION_MIN_MESSAGES..MAX_TITLE_GENERATION_MIN_MESSAGES }
    val parsedMinimumWords = minimumWords.toIntOrNull()
        ?.takeIf { it in MIN_TITLE_GENERATION_MIN_WORDS..MAX_TITLE_GENERATION_MIN_WORDS }

    BottomSheet(
        onDismissRequest = onDismiss,
        fixedHeightFraction = 0.92f
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                item {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.common_instructions)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        minLines = 6,
                        maxLines = 14,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("helper_instruction_input")
                    )
                }
                if (!isSummary) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.tool_title_generation_rules_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.tool_title_generation_rules_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = minimumMessages,
                                onValueChange = { text ->
                                    if (text.length <= 2 && text.all(Char::isDigit)) minimumMessages = text
                                },
                                label = { Text(stringResource(R.string.tool_title_generation_min_messages)) },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            if (parsedMinimumMessages == null) R.string.tool_title_generation_number_invalid
                                            else R.string.tool_title_generation_min_messages_hint,
                                            MIN_TITLE_GENERATION_MIN_MESSAGES,
                                            MAX_TITLE_GENERATION_MIN_MESSAGES
                                        )
                                    )
                                },
                                isError = parsedMinimumMessages == null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = minimumWords,
                                onValueChange = { text ->
                                    if (text.length <= 4 && text.all(Char::isDigit)) minimumWords = text
                                },
                                label = { Text(stringResource(R.string.tool_title_generation_min_words)) },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            if (parsedMinimumWords == null) R.string.tool_title_generation_number_invalid
                                            else R.string.tool_title_generation_min_words_hint,
                                            MIN_TITLE_GENERATION_MIN_WORDS,
                                            MAX_TITLE_GENERATION_MIN_WORDS
                                        )
                                    )
                                },
                                isError = parsedMinimumWords == null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                if (isSummary) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.tool_summary_settings_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            HelperPreferenceToggle(
                                title = stringResource(R.string.settings_summary_suggestion),
                                subtitle = stringResource(R.string.settings_summary_suggestion_description),
                                checked = appPreferences.summarySuggestionEnabled,
                                onCheckedChange = onSummarySuggestionEnabledChange
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            HelperPreferenceToggle(
                                title = stringResource(R.string.settings_auto_summary),
                                subtitle = stringResource(R.string.settings_auto_summary_description),
                                checked = appPreferences.autoSummaryEnabled,
                                onCheckedChange = onAutoSummaryEnabledChange
                            )
                            if (appPreferences.autoSummaryEnabled) {
                                Text(
                                    stringResource(
                                        R.string.settings_auto_summary_threshold,
                                        appPreferences.autoSummaryPercent
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val summaryPercent = appPreferences.autoSummaryPercent.toFloat()
                                val summarySliderState = remember {
                                    SliderState(
                                        value = summaryPercent,
                                        steps = MAX_AUTO_SUMMARY_PERCENT - MIN_AUTO_SUMMARY_PERCENT - 1,
                                        trackRange = MIN_AUTO_SUMMARY_PERCENT.toFloat()..MAX_AUTO_SUMMARY_PERCENT.toFloat()
                                    )
                                }
                                summarySliderState.value = summaryPercent
                                Slider(
                                    state = summarySliderState,
                                    onValueChange = { onAutoSummaryPercentChange(kotlin.math.round(it).toInt()) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { prompt = defaultPrompt }) {
                    Text(stringResource(R.string.common_reset))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(onClick = {
                    onSave(
                        config.copy(
                            prompt = prompt.trim().ifBlank { defaultPrompt },
                            titleGenerationMinMessages = parsedMinimumMessages ?: config.titleGenerationMinMessages,
                            titleGenerationMinWords = parsedMinimumWords ?: config.titleGenerationMinWords
                        )
                    )
                }, enabled = isSummary || (parsedMinimumMessages != null && parsedMinimumWords != null)) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}

@Composable
private fun HelperPreferenceToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
