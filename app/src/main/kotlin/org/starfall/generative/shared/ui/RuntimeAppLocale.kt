package org.starfall.generative.shared.ui

import android.content.ContextWrapper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import org.starfall.generative.shared.storage.preferences.AppLocale

/**
 * Apply DataStore language choices to the live Compose tree. Unlike Activity recreation, this
 * preserves navigation, editor drafts, active model streams and retained screen state.
 */
@Composable
fun RuntimeAppLocale(useSystemLanguage: Boolean, selectedLanguage: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val systemConfiguration = LocalConfiguration.current
    val configuration = remember(systemConfiguration, useSystemLanguage, selectedLanguage) {
        AppLocale.configuration(systemConfiguration, useSystemLanguage, selectedLanguage)
    }
    val localizedContext = remember(baseContext, configuration, useSystemLanguage) {
        if (useSystemLanguage) baseContext else {
            val configured = baseContext.createConfigurationContext(configuration)
            // Preserve the Activity in the ContextWrapper chain. Compose launchers and other
            // AndroidX owners resolve their host by walking that chain from LocalContext.
            object : ContextWrapper(baseContext) {
                override fun getResources() = configured.resources
                override fun getAssets() = configured.assets
            }
        }
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides configuration,
        LocalResources provides localizedContext.resources,
        content = content
    )
}
