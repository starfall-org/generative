package org.starfall.generative.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.starfall.generative.features.machines.shared.ui.RemoteMachineFilesScreen
import org.starfall.generative.features.storage.ui.StorageExplorerScreen
import org.starfall.generative.integrations.remote.RemoteMachineServiceOwner
import org.starfall.generative.shared.storage.preferences.AppPreferences
import org.starfall.generative.shared.storage.preferences.AppPreferencesRepository
import org.starfall.generative.shared.theme.GenerativeTheme
import org.starfall.generative.shared.ui.RuntimeAppLocale
import org.starfall.generative.shared.ui.layout.enableTransparentSystemBars

/** Activity host: routes to the owning feature, not to a combined storage implementation. */
class ExplorerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableTransparentSystemBars()
        val preferences = AppPreferencesRepository(this)
        val machineId = intent.getStringExtra(EXTRA_MACHINE_ID)
        setContent {
            val prefs by preferences.appPreferencesFlow.collectAsStateWithLifecycle(initialValue = AppPreferences())
            RuntimeAppLocale(prefs.useSystemLanguage, prefs.selectedLanguage) {
            GenerativeTheme(
                themeMode = prefs.themeMode,
                amoledMode = prefs.useAmoled,
                dynamicColor = prefs.useDynamicColor,
                colorSchemeName = prefs.colorSchemeName,
                customPalette = prefs.customPalette
            ) {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                androidx.compose.runtime.SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val openExternal: (Uri, String) -> Unit = { uri, mime ->
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }
                    if (machineId != null) {
                        RemoteMachineFilesScreen(
                            service = (application as RemoteMachineServiceOwner).remoteMachineService,
                            machineId = machineId,
                            onClose = ::finish,
                            onOpenExternal = openExternal
                        )
                    } else {
                        StorageExplorerScreen(onClose = ::finish, onOpenExternal = openExternal)
                    }
                }
            }
            }
        }
    }

    companion object {
        private const val EXTRA_MACHINE_ID = "machine_id"

        fun open(context: Context, machineId: String? = null) {
            val intent = Intent(context, ExplorerActivity::class.java).apply {
                machineId?.let { putExtra(EXTRA_MACHINE_ID, it) }
                if (context !is ComponentActivity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
