package org.starfall.generative

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.starfall.generative.shared.storage.preferences.AppLocale
import org.starfall.generative.shared.storage.preferences.ScreenWakelockMode
import org.starfall.generative.shared.theme.GenerativeTheme
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.features.settings.logic.SettingsViewModel
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.shared.model.parseProviderLink
import org.starfall.generative.shared.ui.dialogs.CrashReportDialog
import org.starfall.generative.shared.ui.logging.DebugLogOverlay
import org.starfall.generative.navigation.AppNavigation
import org.starfall.generative.home.chat.logic.ChatViewModel
import org.starfall.generative.home.shared.logic.ChatBackgroundService

private const val SERVICE_STOP_GRACE_MS = 5_000L

class MainActivity : ComponentActivity() {

    private val container get() = (application as GenerativeApplication).container
    private val viewModel: ChatViewModel by viewModels { container.viewModelFactory }
    private val configurationViewModel: ConfigurationViewModel by viewModels { container.viewModelFactory }
    private val settingsViewModel: SettingsViewModel by viewModels { container.viewModelFactory }
    private val remoteMachinesViewModel: RemoteMachinesViewModel by viewModels { container.viewModelFactory }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Language override this Activity was created with; a different one means the UI must be recreated. */
    private var createdLocaleTag: String? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdLocaleTag = AppLocale.overrideTag(this)
        // A recreated Activity (rotation, locale change, process restore) still carries the original
        // launch intent; only a fresh launch may import its provider link.
        if (savedInstanceState == null) handleIntent(intent)
        // Keep both system bars genuinely transparent in gesture and three-button navigation modes.
        // The default edge-to-edge navigation style may add a contrast scrim on some Android versions.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        lifecycleScope.launch {
            viewModel.isAnyGenerating.collect { busy ->
                updateGenerationService(busy)
            }
        }

        // Register before Compose so dialogs, drawers and navigation handle Back
        // first. At the root, retain the generation's ViewModel instead of finishing
        // the activity (Android versions before 12 finish root activities on Back).
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.isAnyGenerating.value) {
                moveTaskToBack(true)
            } else {
                isEnabled = false
                try { onBackPressedDispatcher.onBackPressed() }
                finally { isEnabled = true }
            }
        }

        setContent {
            val crashReports = (application as GenerativeApplication).crashReports
            var pendingCrash by remember { mutableStateOf(crashReports.pending()) }
            val appPrefs by settingsViewModel.preferences.collectAsStateWithLifecycle()
            val isAnyGenerating by viewModel.isAnyGenerating.collectAsStateWithLifecycle()
            val isAnyResponseActive by viewModel.isAnyResponseActive.collectAsStateWithLifecycle()
            // Read state during composition (not inside SideEffect) so changes trigger a recompose.
            // The response-active state is true from request start, before the first API event.
            val keepScreenOn = shouldKeepScreenOn(
                appPrefs.screenWakelockMode,
                anyGenerationActive = isAnyGenerating,
                anyResponseActive = isAnyResponseActive
            )
            // Strings are resolved when the Activity is created, so a new language needs a fresh one.
            LaunchedEffect(appPrefs.useSystemLanguage, appPrefs.selectedLanguage) {
                if (AppLocale.overrideTag(this@MainActivity) != createdLocaleTag) recreate()
            }
            GenerativeTheme(
                themeMode = appPrefs.themeMode,
                amoledMode = appPrefs.useAmoled,
                dynamicColor = appPrefs.useDynamicColor,
                colorSchemeName = appPrefs.colorSchemeName
            ) {
                val darkBars = MaterialTheme.colorScheme.background.luminance() < 0.5f
                SideEffect {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                    }
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkBars
                        isAppearanceLightNavigationBars = !darkBars
                    }
                    if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Keep Surface's behaviour of forcing its content to fill the window.
                    Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
                        AppNavigation(viewModel, configurationViewModel, settingsViewModel, remoteMachinesViewModel, container.toolFiles)
                        // Drawn last so the draggable debug button and log panel float above every screen.
                        if (appPrefs.debugMode) DebugLogOverlay()
                    }
                }
                pendingCrash?.let { report ->
                    CrashReportDialog(report,
                        onCreateIssue = {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, report.issueUri())) }
                                .onFailure {
                                    Toast.makeText(this, R.string.crash_issue_open_failed, Toast.LENGTH_LONG).show()
                                }
                        },
                        onDismiss = {
                            crashReports.dismiss(report.id)
                            pendingCrash = null
                        })
                }
            }
        }
        requestBackgroundNotificationPermission()
    }

    override fun onPause() {
        // Flush the current generation state before leaving the visible activity.
        // This includes Home, switching apps, and turning the screen off, even if
        // the StateFlow collector has not yet processed a newly started response.
        updateGenerationService(viewModel.isAnyGenerating.value)
        super.onPause()
    }

    override fun onDestroy() {
        // Finishing clears this activity's ViewModel and cancels its generation.
        // Rotation retains the ViewModel, so its service must stay running.
        if (isFinishing) updateGenerationService(false, immediate = true)
        super.onDestroy()
    }

    private var stopServiceJob: Job? = null

    private fun updateGenerationService(busy: Boolean, immediate: Boolean = false) {
        val serviceIntent = Intent(this, ChatBackgroundService::class.java)
        stopServiceJob?.cancel()
        stopServiceJob = null
        if (!busy) {
            if (immediate) {
                stopService(serviceIntent)
            } else {
                // A queued message or auto-summary can start the next generation right after this
                // one ends. Android 12+ refuses to start a foreground service from the background,
                // so keep it alive briefly instead of stopping and re-starting it.
                stopServiceJob = lifecycleScope.launch {
                    delay(SERVICE_STOP_GRACE_MS)
                    stopService(serviceIntent)
                }
            }
            return
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: IllegalStateException) {
            Log.w("ChatBackgroundService", "System refused foreground service start", e)
        } catch (e: SecurityException) {
            Log.w("ChatBackgroundService", "Foreground service permission unavailable", e)
        }
    }

    private fun requestBackgroundNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED) return
        val permissionPrefs = getSharedPreferences("notification_permission", MODE_PRIVATE)
        if (permissionPrefs.getBoolean("background_requested", false)) return
        permissionPrefs.edit().putBoolean("background_requested", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if ((data.scheme.equals("mg", true) || data.scheme.equals("generative", true)) &&
            data.host.equals("provider", true)) {
            // Consume the launch URI before saving; Activity recreation must not import it again.
            intent.data = null
            runCatching { parseProviderLink(data) }
                .onSuccess(configurationViewModel::importProvider)
                .onFailure {
                    Toast.makeText(this, it.message ?: "Invalid provider link.", Toast.LENGTH_LONG).show()
                }
            return
        }

    }
}

internal fun shouldKeepScreenOn(
    mode: ScreenWakelockMode,
    anyGenerationActive: Boolean,
    anyResponseActive: Boolean
): Boolean = when (mode) {
    ScreenWakelockMode.NONE -> false
    ScreenWakelockMode.ALL -> anyGenerationActive
    ScreenWakelockMode.EXCEPT_TOOL_CALLS -> anyResponseActive
}
