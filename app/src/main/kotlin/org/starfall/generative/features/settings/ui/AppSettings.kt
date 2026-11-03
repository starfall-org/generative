package org.starfall.generative.features.settings.ui

import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.layout.AppSwipeTabs
import androidx.compose.ui.platform.LocalLayoutDirection
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import org.starfall.generative.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import org.starfall.generative.shared.storage.preferences.AppLanguage
import org.starfall.generative.shared.storage.preferences.AppPreferences
import org.starfall.generative.shared.storage.preferences.CodeBlockDisplayMode
import org.starfall.generative.shared.storage.preferences.DEFAULT_TOOL_CALL_CONCURRENT_LIMIT
import org.starfall.generative.shared.storage.preferences.DEFAULT_TOOL_ROUND_LIMIT
import org.starfall.generative.shared.storage.preferences.MAX_TOOL_CALL_CONCURRENT_LIMIT
import org.starfall.generative.shared.storage.preferences.MAX_TOOL_ROUND_LIMIT
import org.starfall.generative.shared.storage.preferences.McpAttachmentSettings
import org.starfall.generative.shared.storage.preferences.MessageFontFamily
import org.starfall.generative.shared.storage.preferences.ScreenWakelockMode
import org.starfall.generative.shared.storage.preferences.ToolCallStrategy
import org.starfall.generative.shared.storage.preferences.WordWrapMode
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.windowHeightIn

@Composable
fun PreferenceToggle(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsView(
    appPreferences: AppPreferences,
    onThemeChange: (String) -> Unit,
    onAmoledChange: (Boolean) -> Unit,
    onHideStatusBarChange: (Boolean) -> Unit = {},
    onWordWrapModeChange: (WordWrapMode) -> Unit = {},
    onWordWrapColumnChange: (Int) -> Unit = {},
    onCodePreviewEnabledChange: (Boolean) -> Unit = {},
    onCodeBlockDisplayModeChange: (CodeBlockDisplayMode) -> Unit = {},
    onCodeBlockMaxLinesChange: (Int) -> Unit = {},
    onCodeBlockIncludeUndefinedChange: (Boolean) -> Unit = {},
    onMessageFontSizeChange: (Int) -> Unit = {},
    onMessageFontSizeCustomChange: (Boolean) -> Unit = {},
    onMessageFontFamilyChange: (MessageFontFamily) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp)
) {
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            org.starfall.generative.shared.ui.layout.SettingsCard {
                SettingsChoicePreference(
                    title = stringResource(R.string.settings_theme),
                    value = appPreferences.themeMode,
                    choices = listOf(
                        SettingsChoice("SYSTEM", stringResource(R.string.common_system)),
                        SettingsChoice("LIGHT", stringResource(R.string.settings_theme_light)),
                        SettingsChoice("DARK", stringResource(R.string.settings_theme_dark))
                    ),
                    onSelected = onThemeChange
                )
                PreferenceToggle(stringResource(R.string.settings_amoled), null,
                    appPreferences.useAmoled, onAmoledChange)
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_code_blocks)) {
                CodeRenderingPreferences(appPreferences, onWordWrapModeChange, onWordWrapColumnChange,
                    onCodePreviewEnabledChange, onMessageFontSizeChange, onMessageFontFamilyChange,
                    onCodeBlockDisplayModeChange, onCodeBlockMaxLinesChange, onCodeBlockIncludeUndefinedChange,
                    includeTextAppearance = false)
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_text_appearance)) {
                TextAppearancePreferences(appPreferences, onMessageFontSizeChange, onMessageFontFamilyChange, onMessageFontSizeCustomChange)
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_status_bar)) {
                PreferenceToggle(stringResource(R.string.settings_hide_status_bar), null,
                    appPreferences.hideStatusBar, onHideStatusBarChange)
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PreferencesSettingsView(
    appPreferences: AppPreferences,
    onContinueLastConversationChange: (Boolean) -> Unit,
    onPersistSelectedModelChange: (Boolean) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onEnableVibrationChange: (Boolean) -> Unit,
    onLatexModeChange: (String) -> Unit,
    onTtsReadCodeBlocksChange: (Boolean) -> Unit = {},
    onToolRoundLimitEnabledChange: (Boolean) -> Unit = {},
    onToolRoundLimitChange: (Int) -> Unit = {},
    onToolCallStrategyChange: (ToolCallStrategy) -> Unit = {},
    onToolCallConcurrentLimitChange: (Int) -> Unit = {},
    onMcpAttachmentsChange: (McpAttachmentSettings) -> Unit = {},
    onScreenWakelockModeChange: (ScreenWakelockMode) -> Unit = {},
    onAppLanguageChange: (useSystem: Boolean, tag: String) -> Unit = { _, _ -> },
    onAutoSummaryEnabledChange: (Boolean) -> Unit = {},
    onSummarySuggestionEnabledChange: (Boolean) -> Unit = {},
    onAutoSummaryPercentChange: (Int) -> Unit = {},
    onOpenDeveloperOptions: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp)
) {
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            org.starfall.generative.shared.ui.layout.SettingsCard {
                SettingsChoicePreference(
                    title = stringResource(R.string.settings_language),
                    value = if (appPreferences.useSystemLanguage) "" else appPreferences.selectedLanguage,
                    choices = listOf(SettingsChoice("", stringResource(R.string.use_system_language))) +
                        AppLanguage.entries.map { language ->
                            SettingsChoice(language.tag, stringResource(language.nameRes), language.nativeName)
                        },
                    onSelected = { tag ->
                        onAppLanguageChange(tag.isEmpty(), tag.ifEmpty { appPreferences.selectedLanguage })
                    },
                    modifier = Modifier.testTag("app-language")
                )
            }
        }
        item {
            org.starfall.generative.shared.ui.layout.SettingsCard {
                SettingsChoicePreference(
                    title = stringResource(R.string.keep_screen_on_during_generation),
                    value = appPreferences.screenWakelockMode,
                    choices = listOf(
                        SettingsChoice(ScreenWakelockMode.EXCEPT_TOOL_CALLS, stringResource(R.string.common_auto)),
                        SettingsChoice(ScreenWakelockMode.ALL, stringResource(R.string.common_on)),
                        SettingsChoice(ScreenWakelockMode.NONE, stringResource(R.string.common_off))
                    ),
                    onSelected = onScreenWakelockModeChange
                )
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_conversations)) {
                PreferenceToggle(stringResource(R.string.settings_continue_last_chat), null,
                    appPreferences.continueLastConversation, onContinueLastConversationChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                PreferenceToggle(stringResource(R.string.settings_persist_selected_model), null,
                    appPreferences.persistSelectedModel, onPersistSelectedModelChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                PreferenceToggle(stringResource(R.string.settings_auto_scroll), null,
                    appPreferences.autoScroll, onAutoScrollChange)
            }
        }
        item {
            SettingsSection(stringResource(R.string.mcp_and_tools)) {
                Text(stringResource(R.string.tool_execution), style = MaterialTheme.typography.titleMedium)
                SettingsChoicePreference(
                    title = stringResource(R.string.tool_call_strategy),
                    value = appPreferences.toolCallStrategy,
                    choices = listOf(
                        SettingsChoice(ToolCallStrategy.SEQUENTIAL, stringResource(R.string.tool_call_strategy_sequential)),
                        SettingsChoice(ToolCallStrategy.PARALLEL, stringResource(R.string.tool_call_strategy_parallel))
                    ),
                    onSelected = onToolCallStrategyChange,
                    modifier = Modifier.testTag("tool-call-strategy")
                )
                if (appPreferences.toolCallStrategy == ToolCallStrategy.PARALLEL) {
                    SettingsNumberPreference(
                        title = stringResource(R.string.maximum_simultaneous_tool_calls),
                        value = appPreferences.toolCallConcurrentLimit,
                        defaultValue = DEFAULT_TOOL_CALL_CONCURRENT_LIMIT,
                        range = 1..MAX_TOOL_CALL_CONCURRENT_LIMIT,
                        tag = "tool-call-concurrent-limit",
                        onValueChange = onToolCallConcurrentLimitChange
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                PreferenceToggle(stringResource(R.string.settings_limit_tool_rounds),
                    null,
                    appPreferences.toolRoundLimitEnabled, onToolRoundLimitEnabledChange)
                if (appPreferences.toolRoundLimitEnabled) {
                    SettingsNumberPreference(
                        title = stringResource(R.string.settings_maximum_tool_rounds),
                        value = appPreferences.toolRoundLimit,
                        defaultValue = DEFAULT_TOOL_ROUND_LIMIT,
                        range = 1..MAX_TOOL_ROUND_LIMIT,
                        tag = "tool-round-limit",
                        onValueChange = onToolRoundLimitChange
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                McpAttachmentsPreferences(appPreferences.mcpAttachments, onMcpAttachmentsChange)
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_haptics)) {
                PreferenceToggle(stringResource(R.string.settings_haptic_vibration), null,
                    appPreferences.enableVibration, onEnableVibrationChange)
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_text_to_speech)) {
                PreferenceToggle(stringResource(R.string.settings_read_code_blocks), null,
                    appPreferences.ttsReadCodeBlocks, onTtsReadCodeBlocksChange)
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_content)) {
                SettingsChoicePreference(
                    title = stringResource(R.string.settings_latex_equation_rendering),
                    value = appPreferences.latexMode,
                    choices = listOf(
                        SettingsChoice("OFF", stringResource(R.string.settings_latex_disabled)),
                        SettingsChoice("AUTO", stringResource(R.string.settings_latex_auto_detect))
                    ),
                    onSelected = onLatexModeChange
                )
            }
        }
        item {
            org.starfall.generative.shared.ui.layout.SettingsCard {
                SettingsActionPreference(stringResource(R.string.settings_developer), onClick = onOpenDeveloperOptions)
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppSettings(
    appPreferences: AppPreferences,
    onThemeChange: (String) -> Unit,
    onAmoledChange: (Boolean) -> Unit,
    onHideStatusBarChange: (Boolean) -> Unit,
    onWordWrapModeChange: (WordWrapMode) -> Unit,
    onWordWrapColumnChange: (Int) -> Unit,
    onCodePreviewEnabledChange: (Boolean) -> Unit,
    onCodeBlockDisplayModeChange: (CodeBlockDisplayMode) -> Unit,
    onCodeBlockMaxLinesChange: (Int) -> Unit,
    onCodeBlockIncludeUndefinedChange: (Boolean) -> Unit,
    onMessageFontSizeChange: (Int) -> Unit,
    onMessageFontSizeCustomChange: (Boolean) -> Unit,
    onMessageFontFamilyChange: (MessageFontFamily) -> Unit,
    onContinueLastConversationChange: (Boolean) -> Unit,
    onPersistSelectedModelChange: (Boolean) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onEnableVibrationChange: (Boolean) -> Unit,
    onLatexModeChange: (String) -> Unit,
    onTtsReadCodeBlocksChange: (Boolean) -> Unit,
    onToolRoundLimitEnabledChange: (Boolean) -> Unit,
    onToolRoundLimitChange: (Int) -> Unit,
    onToolCallStrategyChange: (ToolCallStrategy) -> Unit,
    onToolCallConcurrentLimitChange: (Int) -> Unit,
    onMcpAttachmentsChange: (McpAttachmentSettings) -> Unit = {},
    onScreenWakelockModeChange: (ScreenWakelockMode) -> Unit,
    onAppLanguageChange: (Boolean, String) -> Unit,
    onAutoSummaryEnabledChange: (Boolean) -> Unit = {},
    onSummarySuggestionEnabledChange: (Boolean) -> Unit = {},
    onAutoSummaryPercentChange: (Int) -> Unit = {},
    onOpenDeveloperOptions: () -> Unit = {},
    scaffoldPadding: PaddingValues = PaddingValues()
) {
    val pager = rememberPagerState(pageCount = { 2 })
    // Tabs stay below the toolbar. The pages themselves scroll under the navigation bar.
    val pagePadding = scrollableScaffoldPadding(
        PaddingValues(
            start = scaffoldPadding.calculateStartPadding(LocalLayoutDirection.current),
            end = scaffoldPadding.calculateEndPadding(LocalLayoutDirection.current),
            bottom = scaffoldPadding.calculateBottomPadding()
        ), horizontal = 16.dp, top = 8.dp, bottom = 24.dp
    )
    Column(Modifier.fillMaxSize().padding(top = scaffoldPadding.calculateTopPadding() + 8.dp)) {
        AppSwipeTabs(
            tabs = listOf(
                stringResource(R.string.settings_tab_preferences),
                stringResource(R.string.settings_tab_appearance)
            ),
            pagerState = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            tabRowModifier = Modifier.padding(
                start = scaffoldPadding.calculateStartPadding(LocalLayoutDirection.current) + 16.dp,
                end = scaffoldPadding.calculateEndPadding(LocalLayoutDirection.current) + 16.dp
            ),
            spacing = 12.dp
        ) { page ->
            when (page) {
                0 -> PreferencesSettingsView(
                    contentPadding = pagePadding,
                    appPreferences = appPreferences,
                    onContinueLastConversationChange = onContinueLastConversationChange,
                    onPersistSelectedModelChange = onPersistSelectedModelChange,
                    onAutoScrollChange = onAutoScrollChange,
                    onEnableVibrationChange = onEnableVibrationChange,
                    onLatexModeChange = onLatexModeChange,
                    onTtsReadCodeBlocksChange = onTtsReadCodeBlocksChange,
                    onToolRoundLimitEnabledChange = onToolRoundLimitEnabledChange,
                    onToolRoundLimitChange = onToolRoundLimitChange,
                    onToolCallStrategyChange = onToolCallStrategyChange,
                    onToolCallConcurrentLimitChange = onToolCallConcurrentLimitChange,
                    onMcpAttachmentsChange = onMcpAttachmentsChange,
                    onScreenWakelockModeChange = onScreenWakelockModeChange,
                    onAppLanguageChange = onAppLanguageChange,
                    onAutoSummaryEnabledChange = onAutoSummaryEnabledChange,
                    onSummarySuggestionEnabledChange = onSummarySuggestionEnabledChange,
                    onAutoSummaryPercentChange = onAutoSummaryPercentChange,
                    onOpenDeveloperOptions = onOpenDeveloperOptions
                )
                else -> AppearanceSettingsView(
                    contentPadding = pagePadding,
                    appPreferences = appPreferences,
                    onThemeChange = onThemeChange,
                    onAmoledChange = onAmoledChange,
                    onHideStatusBarChange = onHideStatusBarChange,
                    onWordWrapModeChange = onWordWrapModeChange,
                    onWordWrapColumnChange = onWordWrapColumnChange,
                    onCodePreviewEnabledChange = onCodePreviewEnabledChange,
                    onCodeBlockDisplayModeChange = onCodeBlockDisplayModeChange,
                    onCodeBlockMaxLinesChange = onCodeBlockMaxLinesChange,
                    onCodeBlockIncludeUndefinedChange = onCodeBlockIncludeUndefinedChange,
                    onMessageFontSizeChange = onMessageFontSizeChange,
                    onMessageFontSizeCustomChange = onMessageFontSizeCustomChange,
                    onMessageFontFamilyChange = onMessageFontFamilyChange
                )
            }
        }
    }
}
