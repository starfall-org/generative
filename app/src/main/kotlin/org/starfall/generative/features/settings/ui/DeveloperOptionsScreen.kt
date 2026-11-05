package org.starfall.generative.features.settings.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate

/** Dedicated Developer Options destination, not an expanding group in preferences. */
@Composable
fun DeveloperOptionsScreen(
    preferences: AppPreferences,
    onDebugModeChange: (Boolean) -> Unit,
    onDebugLogCategoriesChange: (Set<DebugLogCategory>) -> Unit,
    onDebugLogLevelChange: (DebugLogLevel) -> Unit,
    onOpenLauncherDragTest: () -> Unit,
    onOpenProvidersDemo: () -> Unit = {},
    onBack: () -> Unit
) {
    EditorScreenTemplate(title = stringResource(R.string.settings_developer), onBack = onBack) { scaffoldPadding ->
        val padding = scrollableScaffoldPadding(scaffoldPadding, horizontal = 16.dp, top = 8.dp, bottom = 24.dp)

        LazyColumn(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = padding
        ) {
            item {
                DeveloperSettingsSection(preferences, onDebugModeChange, onDebugLogCategoriesChange,
                    onDebugLogLevelChange)
            }
            item {
                SettingsCard {
                    SettingsActionPreference(
                        title = stringResource(R.string.launcher_drag_test_title),
                        onClick = onOpenLauncherDragTest
                    )
                    SettingsActionPreference(
                        title = stringResource(R.string.providers_demo_title),
                        onClick = onOpenProvidersDemo
                    )
                }
            }
        }

    }
}
