package org.starfall.generative

import org.starfall.generative.shared.ui.inputs.InputFocusHost
import org.starfall.generative.features.httptools.logic.CustomHttpToolsViewModel
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.Locale
import android.widget.Toast
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.starfall.generative.shared.model.preferences.ScreenWakelockMode
import org.starfall.generative.shared.state.LocalWriteErrors
import org.starfall.generative.shared.ui.theme.GenerativeTheme
import org.starfall.generative.shared.ui.RuntimeAppLocale
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.features.settings.logic.SettingsViewModel
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.shared.model.parseProviderLink
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashLogExporter
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashReport
import org.starfall.generative.shared.subfeatures.crashreports.ui.CrashReportDialog
import org.starfall.generative.shared.subfeatures.logging.ui.DebugLogOverlay
import org.starfall.generative.shared.ui.layout.enableTransparentSystemBars
import org.starfall.generative.navigation.AppNavigation
import org.starfall.generative.navigation.AppDeepLinkRequest
import org.starfall.generative.navigation.ExternalShareRequest
import org.starfall.generative.navigation.ExternalShare
import android.net.Uri
import org.starfall.generative.navigation.parseExternalShare
import org.starfall.generative.navigation.parseAppDeepLink
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.home.shared.logic.ChatBackgroundService
import org.starfall.generative.shared.subfeatures.terminal.RemoteTerminalLaunch

private const val SERVICE_STOP_GRACE_MS = 5_000L

class MainActivity : ComponentActivity() {

    private val container get() = (application as GenerativeApplication).container
    private val viewModel: ChatViewModel by viewModels { container.viewModelFactory }
    private val configurationViewModel: ConfigurationViewModel by viewModels { container.viewModelFactory }
    private val settingsViewModel: SettingsViewModel by viewModels { container.viewModelFactory }
    private val customHttpToolsViewModel: CustomHttpToolsViewModel by viewModels { container.viewModelFactory }
    private val remoteMachinesViewModel: RemoteMachinesViewModel by viewModels { container.viewModelFactory }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingTerminalMachineId by mutableStateOf<String?>(null)
    private var pendingAppDeepLink by mutableStateOf<AppDeepLinkRequest?>(null)
    private var nextAppDeepLinkId = 0L
    private var pendingExternalShare by mutableStateOf<ExternalShareRequest?>(null)
    private var nextExternalShareId = 0L
    private val crashReportStore get() = (application as GenerativeApplication).crashReports
    private var pendingCrash by mutableStateOf<CrashReport?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCrash = crashReportStore.pending()
        pendingTerminalMachineId = savedInstanceState?.getString("pending_terminal_machine_id")
        if (savedInstanceState?.getBoolean("pending_external_share") == true) {
            pendingExternalShare = ExternalShareRequest(++nextExternalShareId, ExternalShare(
                savedInstanceState.getString("external_share_text"),
                savedInstanceState.getStringArrayList("external_share_uris").orEmpty().map(Uri::parse),
                savedInstanceState.getString("external_share_mime")
            ))
        }
        // A recreated Activity (rotation, locale change, process restore) still carries the original
        // launch intent; only a fresh launch may import its provider link.
        if (savedInstanceState == null) handleIntent(intent)
        enableTransparentSystemBars()

        lifecycleScope.launch {
            // A running conversation summary keeps the process alive like a response does, and its
            // progress is what the service notification shows meanwhile.
            combine(viewModel.isAnyGenerating, viewModel.summaryProgress) { generating, summary ->
                generating to summary
            }.collect { (generating, summary) ->
                ChatBackgroundService.showSummaryProgress(summary)
                updateGenerationService(generating || summary != null)
            }
        }

        // Register before Compose so dialogs, drawers and navigation handle Back
        // first. At the root, retain the generation's ViewModel instead of finishing
        // the activity (Android versions before 12 finish root activities on Back).
        onBackPressedDispatcher.addCallback(this) {
            if (isGenerationOrSummaryActive()) {
                moveTaskToBack(true)
            } else {
                isEnabled = false
                try { onBackPressedDispatcher.onBackPressed() }
                finally { isEnabled = true }
            }
        }

        setContent {
            val crashReports = crashReportStore
            val crashScope = rememberCoroutineScope()
            fun saveCrashLog(report: CrashReport) {
                crashScope.launch {
                    val result = withContext(Dispatchers.IO) { CrashLogExporter.save(this@MainActivity, report) }
                    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    Toast.makeText(this@MainActivity,
                        result.fold({ getString(R.string.crash_log_saved, it) },
                            { getString(R.string.crash_log_save_failed) }),
                        Toast.LENGTH_LONG).show()
                }
            }
            val storagePermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                val report = pendingCrash
                if (report == null) return@rememberLauncherForActivityResult
                if (granted) saveCrashLog(report)
                else Toast.makeText(this@MainActivity, R.string.crash_log_permission_denied, Toast.LENGTH_LONG).show()
            }
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
            // The first frame uses the device language. DataStore can then supply an override
            // without recreating Activity or interrupting an ongoing chat session.
            RuntimeAppLocale(appPrefs.useSystemLanguage, appPrefs.selectedLanguage) {
            GenerativeTheme(
                themeMode = appPrefs.themeMode,
                amoledMode = appPrefs.useAmoled,
                dynamicColor = appPrefs.useDynamicColor,
                colorSchemeName = appPrefs.colorSchemeName,
                customPalette = appPrefs.customPalette
            ) {
                val darkBars = MaterialTheme.colorScheme.background.luminance() < 0.5f
                SideEffect {
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
                    InputFocusHost(Modifier.fillMaxSize(), propagateMinConstraints = true) {
                        AppNavigation(viewModel, configurationViewModel, settingsViewModel, remoteMachinesViewModel,
                            container.toolFiles, container.appDataTransfer,
                            customHttpToolsViewModel = customHttpToolsViewModel,
                            pendingTerminalMachineId = pendingTerminalMachineId,
                            onTerminalLaunchHandled = { pendingTerminalMachineId = null },
                            pendingAppDeepLink = pendingAppDeepLink,
                            onAppDeepLinkHandled = { id ->
                                if (pendingAppDeepLink?.id == id) pendingAppDeepLink = null
                            },
                            pendingExternalShare = pendingExternalShare,
                            onExternalShareHandled = { id ->
                                if (pendingExternalShare?.id == id) pendingExternalShare = null
                            })
                        // Drawn last so the draggable debug button and log panel float above every screen.
                        if (appPrefs.debugMode) DebugLogOverlay(
                            onDisable = { settingsViewModel.setDebugMode(false) }
                        )
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
                        onSaveLog = {
                            if (CrashLogExporter.needsLegacyStoragePermission && ContextCompat.checkSelfPermission(
                                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            else saveCrashLog(report)
                        },
                        onDismiss = {
                            crashReports.dismiss(report.id)
                            pendingCrash = null
                        })
                }
            }
            }
        }
        requestBackgroundNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // OAuth opens an external browser. A fatal exception can be recorded while this
        // Activity is stopped but retained by Android; onCreate will not run again then.
        // Refresh on each return so the crash dialog is not lost behind browser handoff.
        pendingCrash = crashReportStore.pending()
    }

    override fun onPause() {
        // Flush the current generation state before leaving the visible activity.
        // This includes Home, switching apps, and turning the screen off, even if
        // the StateFlow collector has not yet processed a newly started response.
        updateGenerationService(isGenerationOrSummaryActive())
        super.onPause()
    }

    override fun onDestroy() {
        // Finishing clears this activity's ViewModel and cancels its generation.
        // Rotation retains the ViewModel, so its service must stay running.
        if (isFinishing) updateGenerationService(false, immediate = true)
        super.onDestroy()
    }

    private var stopServiceJob: Job? = null

    private fun isGenerationOrSummaryActive() =
        viewModel.isAnyGenerating.value || viewModel.summaryProgress.value != null

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
        lifecycleScope.launch(LocalWriteErrors.handler) {
            if (container.notificationPermissionStore.claimBackgroundRequest()) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_terminal_machine_id", pendingTerminalMachineId)
        pendingExternalShare?.content?.let { share ->
            outState.putBoolean("pending_external_share", true)
            outState.putString("external_share_text", share.text)
            outState.putStringArrayList("external_share_uris", ArrayList(share.streams.map(Uri::toString)))
            outState.putString("external_share_mime", share.fallbackMime)
        }
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_PROCESS_TEXT)) {
            // Shared extras originate in another app and can contain malformed parcelables.
            runCatching { parseExternalShare(intent) }
                .onSuccess { content ->
                    if (content != null) pendingExternalShare = ExternalShareRequest(++nextExternalShareId, content)
                }
                .onFailure {
                    Toast.makeText(this, R.string.shared_attachment_failed, Toast.LENGTH_LONG).show()
                }
            // The pending request now owns the content. Do not reprocess the launch Intent.
            intent.action = Intent.ACTION_MAIN
            return
        }
        RemoteTerminalLaunch.consumeMachineId(intent)?.let { machineId ->
            pendingTerminalMachineId = machineId
            return
        }
        val data = intent?.data ?: return
        // `generative://` is the canonical scheme; accept `mg://` for older shared links.
        if ((data.scheme.equals("generative", true) || data.scheme.equals("mg", true)) &&
            data.host.equals("provider", true)) {
            // Consume the launch URI before saving; Activity recreation must not import it again.
            intent.data = null
            runCatching { parseProviderLink(data) }
                .onSuccess(configurationViewModel::importProvider)
                .onFailure {
                    Toast.makeText(this, it.message ?: getString(R.string.invalid_provider_link), Toast.LENGTH_LONG).show()
                }
            return
        }
        if (data.scheme.equals("generative", true) &&
            data.host?.lowercase(Locale.ROOT) in setOf("providers", "mcp", "chat")) {
            // Consume before navigation to prevent a duplicate action on Activity recreation.
            intent.data = null
            runCatching { parseAppDeepLink(data) }
                .onSuccess { action ->
                    if (action != null) pendingAppDeepLink = AppDeepLinkRequest(++nextAppDeepLinkId, action)
                }
                .onFailure {
                    Toast.makeText(this, R.string.invalid_app_link, Toast.LENGTH_LONG).show()
                }
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
