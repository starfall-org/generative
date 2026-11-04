package org.starfall.generative.features.machines.shared.ui.terminal

import android.app.Activity
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.remote.RemoteSecretPrompt
import org.starfall.generative.shared.model.remote.RemoteTerminalSession
import org.starfall.generative.shared.model.remote.TerminalInputMode
import org.starfall.generative.shared.storage.preferences.REMOTE_TERMINAL_FONT_SIZE_KEY
import org.starfall.generative.shared.storage.preferences.REMOTE_TERMINAL_FORCE_MOUSE_KEY
import org.starfall.generative.shared.storage.preferences.RemoteTerminalPreferenceStore
import org.starfall.generative.shared.storage.preferences.remoteTerminalImePreferenceKey
import org.starfall.generative.shared.storage.preferences.remoteTerminalInputModePreferenceKey
import org.starfall.generative.shared.storage.preferences.remoteTerminalInputPreferences
import org.starfall.generative.shared.terminal.PendingRemoteSecretRequest
import org.starfall.generative.shared.terminal.RemoteTerminalBridge
import org.starfall.generative.shared.terminal.RemoteTerminalSessions
import org.starfall.generative.shared.terminal.TerminalSlot
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionActive

@Composable
internal fun RemoteTerminalScreen(
    machine: RemoteMachine,
    openTerminal: suspend (RemoteMachine, RemoteSecretPrompt?) -> RemoteTerminalSession,
    disconnect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val terminalStore = remember(context) { RemoteTerminalPreferenceStore.getInstance(context) }
    val terminalPrefs by terminalStore.data.collectAsState(initial = null)
    // Sessions live in a process-wide registry so they survive leaving this screen.
    val terminalSlots = RemoteTerminalSessions.slotsFor(machine.id)
    val activeTerminalId = RemoteTerminalSessions.activeIdFor(machine.id)
    var connecting by remember(machine.id) { mutableStateOf(false) }
    var connectionJob by remember(machine.id) { mutableStateOf<Job?>(null) }
    var openError by remember(machine.id) { mutableStateOf<String?>(null) }
    val error = openError ?: if (RemoteTerminalSessions.connectionLost(machine.id)) {
        stringResource(R.string.remote_server_terminal_connection_lost)
    } else null
    var secretRequest by remember(machine.id) { mutableStateOf<PendingRemoteSecretRequest?>(null) }
    var passwordInput by remember(machine.id) { mutableStateOf("") }
    var showPromptPassword by remember(machine.id) { mutableStateOf(false) }
    // Null means "follow the layout": open on wide screens, collapsed on narrow ones.
    var drawerExpanded by remember(machine.id) { mutableStateOf<Boolean?>(null) }
    val sessionsDrawerOpen = drawerExpanded ?: (LocalConfiguration.current.screenWidthDp >= 720)
    var fontDp by remember { mutableIntStateOf(14) }
    var fontLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(terminalPrefs) {
        if (!fontLoaded) terminalPrefs?.let { preferences ->
            fontDp = (preferences[intPreferencesKey(REMOTE_TERMINAL_FONT_SIZE_KEY)] ?: 14)
                .coerceIn(MIN_TERMINAL_FONT_DP, MAX_TERMINAL_FONT_DP)
            fontLoaded = true
        }
    }
    val textSizePx = (fontDp * density).toInt()
    val activeSlot = terminalSlots.firstOrNull { it.terminal.id == activeTerminalId }
    val activeBridge = activeSlot?.bridge
    val currentSecretRequest by rememberUpdatedState(secretRequest)
    val terminalBackground = MaterialTheme.colorScheme.background
    val terminalForeground = MaterialTheme.colorScheme.onBackground
    val connectionFailedMessage = stringResource(R.string.connection_failed)
    val terminalBackgroundArgb = terminalBackground.toArgb()
    val terminalForegroundArgb = terminalForeground.toArgb()

    fun setFontDp(value: Int) {
        fontDp = value.coerceIn(MIN_TERMINAL_FONT_DP, MAX_TERMINAL_FONT_DP)
        val size = fontDp
        terminalStore.update { it[intPreferencesKey(REMOTE_TERMINAL_FONT_SIZE_KEY)] = size }
    }

    fun closeSlot(slot: TerminalSlot) = RemoteTerminalSessions.close(machine.id, slot)

    // Leaving the screen keeps the sessions alive; only a pending password prompt is dropped.
    fun leaveScreen() {
        connectionJob?.cancel()
        currentSecretRequest?.answer?.complete(null)
    }

    fun openTerminal() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            connecting = true
            openError = null
            RemoteTerminalSessions.clearConnectionLost(machine.id)
            try {
                val opened = openTerminal(machine) { request ->
                    // The SSH thread pauses here only after the server requests an unsaved secret.
                    val pending = PendingRemoteSecretRequest(request, CompletableDeferred())
                    withContext(Dispatchers.Main) {
                        passwordInput = ""
                        showPromptPassword = false
                        secretRequest = pending
                    }
                    try {
                        pending.answer.await()
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            if (secretRequest === pending) secretRequest = null
                        }
                    }
                }
                // Read the live preference so sessions opened after a toolbar toggle use it too.
                val preferences = terminalStore.snapshot()
                val inputPreferences = preferences.remoteTerminalInputPreferences(machine.id)
                if (!isActive) {
                    // The user left while connecting: do not leave an unowned shell behind.
                    opened.close()
                    throw CancellationException()
                }
                val newBridge = RemoteTerminalBridge(
                    context.applicationContext,
                    inputPreferences.mode,
                    hostWindow = (context as? Activity)?.window
                ).also { bridge ->
                    bridge.updateImeEnabled(inputPreferences.imeEnabled)
                    bridge.forceMouse = preferences[booleanPreferencesKey(REMOTE_TERMINAL_FORCE_MOUSE_KEY)] ?: false
                    bridge.attachRemote(opened)
                }
                RemoteTerminalSessions.register(machine.id, opened, newBridge)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                openError = failure.message?.takeIf { it.isNotBlank() }
                    ?: connectionFailedMessage
            } finally {
                connecting = false
            }
        }
    }

    // Both the system Back action and the app-bar Back action leave active sessions untouched.
    // Only an unfinished connection attempt is cancelled when the screen goes away.
    val navigateBack = {
        leaveScreen()
        onDismiss()
    }
    BackHandler(enabled = LocalScreenTransitionActive.current) { navigateBack() }
    DisposableEffect(machine.id) {
        onDispose { leaveScreen() }
    }
    // Pinch zoom callbacks belong to this composition, so (re)bind them on every entry.
    DisposableEffect(terminalSlots) {
        terminalSlots.forEach { slot ->
            slot.bridge.onZoomStep = { increase -> setFontDp(fontDp + if (increase) 1 else -1) }
        }
        onDispose { terminalSlots.forEach { it.bridge.onZoomStep = {} } }
    }
    LaunchedEffect(machine.id, terminalPrefs != null) {
        if (terminalPrefs != null && RemoteTerminalSessions.slotsFor(machine.id).isEmpty()) openTerminal()
    }
    // Sessions can die without a clean end of stream (network drop, killed channel); keep checking while
    // the screen is open so a dead terminal is replaced by the reconnect panel instead of staying on screen.
    LaunchedEffect(machine.id) {
        while (true) {
            RemoteTerminalSessions.pruneDead(machine.id)
            delay(3_000)
        }
    }
    // Coming back to the app is when a dropped connection is most likely: re-check and re-focus.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        RemoteTerminalSessions.pruneDead(machine.id)
        RemoteTerminalSessions.slotsFor(machine.id)
            .firstOrNull { it.terminal.id == RemoteTerminalSessions.activeIdFor(machine.id) }
            ?.bridge?.focusTerminalView()
    }
    // The active id must always point at a live session, otherwise every view is laid out at zero size.
    LaunchedEffect(activeTerminalId, terminalSlots) {
        if (activeSlot == null && terminalSlots.isNotEmpty()) {
            RemoteTerminalSessions.setActive(machine.id, terminalSlots.last().terminal.id)
        }
    }
    // Keep IME/keyboard focus on the session the user is looking at, not on a hidden one.
    LaunchedEffect(activeTerminalId, terminalSlots) {
        activeSlot?.bridge?.focusTerminalView()
    }

    RemoteTerminalContent(
        machine = machine,
        terminalSlots = terminalSlots,
        activeTerminalId = activeTerminalId,
        activeBridge = activeBridge,
        connecting = connecting,
        error = error,
        secretRequestVisible = secretRequest != null,
        textSizePx = textSizePx,
        terminalBackground = terminalBackground,
        terminalForeground = terminalForeground,
        terminalBackgroundArgb = terminalBackgroundArgb,
        terminalForegroundArgb = terminalForegroundArgb,
        sessionsDrawerOpen = sessionsDrawerOpen,
        onToggleSessions = { drawerExpanded = !sessionsDrawerOpen },
        onBack = navigateBack,
        onDisconnect = {
            leaveScreen()
            RemoteTerminalSessions.closeAll(machine.id)
            disconnect(machine.id)
            onDismiss()
        },
        onInputModeChange = { mode ->
            updateRemoteTerminalInputMode(
                mode = mode,
                activeBridge = activeBridge,
                terminalSlots = terminalSlots,
                terminalPrefs = terminalPrefs,
                machineId = machine.id,
                terminalStore = terminalStore
            )
        },
        onToggleIme = {
            toggleRemoteTerminalIme(activeBridge, terminalSlots, machine.id, terminalStore)
        },
        onToggleForceMouse = {
            toggleRemoteTerminalForceMouse(activeBridge, terminalSlots, terminalStore)
        },
        onOpenTerminal = { openTerminal() },
        onSelect = { slot ->
            RemoteTerminalSessions.setActive(machine.id, slot.terminal.id)
            slot.bridge.focusTerminalView()
        },
        onClose = { slot -> closeSlot(slot) },
        onNew = { openTerminal() }
    )

    secretRequest?.let { pending ->
        RemoteTerminalSecretDialog(
            pending = pending,
            passwordInput = passwordInput,
            onPasswordInputChange = { passwordInput = it },
            showPromptPassword = showPromptPassword,
            onTogglePromptPassword = { showPromptPassword = !showPromptPassword },
            onDismiss = {
                leaveScreen()
                onDismiss()
            }
        )
    }
}

private fun updateRemoteTerminalInputMode(
    mode: TerminalInputMode,
    activeBridge: RemoteTerminalBridge?,
    terminalSlots: List<TerminalSlot>,
    terminalPrefs: Preferences?,
    machineId: String,
    terminalStore: RemoteTerminalPreferenceStore
) {
    if (activeBridge == null) return
    val localImeEnabled = mode.usesLocalInput &&
        terminalPrefs?.get(booleanPreferencesKey(remoteTerminalImePreferenceKey(machineId))) != false
    terminalSlots.forEach { slot ->
        slot.bridge.setInputMode(mode)
        slot.bridge.updateImeEnabled(localImeEnabled)
    }
    terminalStore.update { it[stringPreferencesKey(remoteTerminalInputModePreferenceKey(machineId))] = mode.name }
}

private fun toggleRemoteTerminalIme(
    activeBridge: RemoteTerminalBridge?,
    terminalSlots: List<TerminalSlot>,
    machineId: String,
    terminalStore: RemoteTerminalPreferenceStore
) {
    activeBridge?.takeIf { it.inputMode.usesLocalInput }?.let { bridge ->
        val enabled = !bridge.imeEnabled
        terminalSlots.forEach { it.bridge.updateImeEnabled(enabled) }
        terminalStore.update { it[booleanPreferencesKey(remoteTerminalImePreferenceKey(machineId))] = enabled }
    }
}

private fun toggleRemoteTerminalForceMouse(
    activeBridge: RemoteTerminalBridge?,
    terminalSlots: List<TerminalSlot>,
    terminalStore: RemoteTerminalPreferenceStore
) {
    activeBridge?.let { bridge ->
        val enabled = !bridge.forceMouse
        terminalSlots.forEach { slot -> slot.bridge.forceMouse = enabled }
        terminalStore.update { it[booleanPreferencesKey(REMOTE_TERMINAL_FORCE_MOUSE_KEY)] = enabled }
    }
}
