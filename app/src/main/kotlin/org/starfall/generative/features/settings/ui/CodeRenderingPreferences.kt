package org.starfall.generative.features.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import kotlin.math.roundToInt
import org.starfall.generative.shared.model.preferences.AppPreferences
import org.starfall.generative.shared.model.preferences.CodeBlockDisplayMode
import org.starfall.generative.shared.model.preferences.DEFAULT_MESSAGE_FONT_SIZE
import org.starfall.generative.shared.model.preferences.DEFAULT_CODE_BLOCK_MAX_LINES
import org.starfall.generative.shared.model.preferences.DEFAULT_WORD_WRAP_COLUMN
import org.starfall.generative.shared.model.preferences.MessageFontFamily
import org.starfall.generative.shared.model.preferences.WordWrapMode
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeRenderingPreferences(
    preferences: AppPreferences,
    onModeChange: (WordWrapMode) -> Unit,
    onColumnChange: (Int) -> Unit,
    onPreviewChange: (Boolean) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onFontFamilyChange: (MessageFontFamily) -> Unit,
    onCodeBlockDisplayModeChange: (CodeBlockDisplayMode) -> Unit = {},
    onCodeBlockMaxLinesChange: (Int) -> Unit = {},
    onCodeBlockIncludeUndefinedChange: (Boolean) -> Unit = {},
    includeTextAppearance: Boolean = true
) {
    SettingsChoicePreference(
        title = stringResource(R.string.code_line_wrapping),
        value = preferences.wordWrapMode,
        choices = listOf(
            SettingsChoice(WordWrapMode.OFF, stringResource(R.string.common_off)),
            SettingsChoice(WordWrapMode.VIEWPORT, stringResource(R.string.word_wrap_viewport)),
            SettingsChoice(WordWrapMode.COLUMN, stringResource(R.string.word_wrap_column)),
            SettingsChoice(WordWrapMode.BOUNDED, stringResource(R.string.code_wrap_smaller_width_limit))
        ),
        onSelected = onModeChange,
        modifier = Modifier.testTag("word-wrap-mode")
    )
    if (preferences.wordWrapMode == WordWrapMode.COLUMN || preferences.wordWrapMode == WordWrapMode.BOUNDED) {
        SettingsNumberPreference(
            title = stringResource(R.string.word_wrap_column_count),
            value = preferences.wordWrapColumn,
            defaultValue = DEFAULT_WORD_WRAP_COLUMN,
            range = 1..Int.MAX_VALUE,
            tag = "word-wrap-column",
            onValueChange = onColumnChange
        )
    }
    PreferenceToggle(stringResource(R.string.code_preview), null,
        preferences.codePreviewEnabled, onPreviewChange)
    SettingsChoicePreference(
        title = stringResource(R.string.code_block_display),
        value = preferences.codeBlockDisplayMode,
        choices = listOf(
            SettingsChoice(CodeBlockDisplayMode.SHOW_ALL, stringResource(R.string.code_block_show_all)),
            SettingsChoice(CodeBlockDisplayMode.EXTENDABLE, stringResource(R.string.code_block_expandable_preview)),
            SettingsChoice(CodeBlockDisplayMode.SHOW_IN_DRAWER, stringResource(R.string.code_block_full_screen_viewer))
        ),
        onSelected = onCodeBlockDisplayModeChange,
        modifier = Modifier.testTag("code-block-display-mode")
    )
    if (preferences.codeBlockDisplayMode != CodeBlockDisplayMode.SHOW_ALL) {
        SettingsNumberPreference(
            title = stringResource(R.string.code_block_max_lines),
            value = preferences.codeBlockMaxLines,
            defaultValue = DEFAULT_CODE_BLOCK_MAX_LINES,
            range = 1..10_000,
            tag = "code-block-max-lines",
            onValueChange = onCodeBlockMaxLinesChange
        )
        PreferenceToggle(
            stringResource(R.string.code_block_include_undefined),
            null,
            preferences.codeBlockIncludeUndefined,
            onCodeBlockIncludeUndefinedChange
        )
    }
    if (includeTextAppearance) {
        TextAppearancePreferences(preferences, onFontSizeChange, onFontFamilyChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextAppearancePreferences(
    preferences: AppPreferences,
    onFontSizeChange: (Int) -> Unit,
    onFontFamilyChange: (MessageFontFamily) -> Unit,
    onFontSizeCustomChange: (Boolean) -> Unit = {}
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    PreferenceToggle(
        stringResource(R.string.message_text_size),
        null,
        preferences.messageFontSizeCustom,
        onFontSizeCustomChange
    )
    if (preferences.messageFontSizeCustom) {
        Text(stringResource(R.string.message_text_size_hint, preferences.messageFontSize),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val fontSize = preferences.messageFontSize.toFloat()
            val sliderState = remember {
                SliderState(value = fontSize, steps = 11, trackRange = 12f..24f)
            }
            sliderState.value = fontSize
            Slider(
                state = sliderState,
                onValueChange = { onFontSizeChange(it.roundToInt()) },
                modifier = Modifier.weight(1f).testTag("message-font-size")
            )
            IconButton(
                onClick = { onFontSizeChange(DEFAULT_MESSAGE_FONT_SIZE) },
                enabled = preferences.messageFontSize != DEFAULT_MESSAGE_FONT_SIZE,
                modifier = Modifier.size(36.dp).testTag("message-font-size-reset")
            ) {
                SharedIcon(Icons.Outlined.RestartAlt, stringResource(R.string.message_text_size_reset),
                    modifier = Modifier.size(20.dp))
            }
        }
    }

    SettingsChoicePreference(
        title = stringResource(R.string.message_font_family),
        value = preferences.messageFontFamily,
        choices = listOf(
            SettingsChoice(MessageFontFamily.DEFAULT, stringResource(R.string.font_family_default)),
            SettingsChoice(MessageFontFamily.SANS_SERIF, stringResource(R.string.font_family_sans_serif)),
            SettingsChoice(MessageFontFamily.SERIF, stringResource(R.string.font_family_serif)),
            SettingsChoice(MessageFontFamily.MONOSPACE, stringResource(R.string.font_family_monospace)),
            SettingsChoice(MessageFontFamily.CURSIVE, stringResource(R.string.font_family_cursive))
        ),
        onSelected = onFontFamilyChange,
        modifier = Modifier.testTag("message-font-family")
    )
}
