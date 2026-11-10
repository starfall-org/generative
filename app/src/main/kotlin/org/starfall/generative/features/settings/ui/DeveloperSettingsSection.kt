package org.starfall.generative.features.settings.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.shared.model.preferences.AppPreferences
import org.starfall.generative.shared.subfeatures.logging.ui.labelRes
import org.starfall.generative.shared.ui.layout.SettingsCard

/** Debug log switch plus, while it is on, the choice of which kinds of log the floating panel records. */
@Composable
internal fun DeveloperSettingsSection(
    appPreferences: AppPreferences,
    onDebugModeChange: (Boolean) -> Unit,
    onDebugLogCategoriesChange: (Set<DebugLogCategory>) -> Unit,
    onDebugLogLevelChange: (DebugLogLevel) -> Unit
) {
    SettingsCard {
        PreferenceToggle(
            stringResource(R.string.settings_debug_logs), null,
            appPreferences.debugMode, onDebugModeChange
        )
        AnimatedVisibility(visible = appPreferences.debugMode) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMultiChoicePreference(
                    title = stringResource(R.string.settings_debug_log_scope),
                    selected = appPreferences.debugLogCategories,
                    choices = DebugLogCategory.entries.map { category ->
                        SettingsChoice(category, stringResource(category.labelRes()))
                    },
                    onSelectedChange = onDebugLogCategoriesChange
                )
                SettingsChoicePreference(
                    title = stringResource(R.string.settings_debug_log_level),
                    value = appPreferences.debugLogLevel,
                    choices = DebugLogLevel.entries.map { SettingsChoice(it, it.name) },
                    onSelected = onDebugLogLevelChange
                )
            }
        }
    }
}
