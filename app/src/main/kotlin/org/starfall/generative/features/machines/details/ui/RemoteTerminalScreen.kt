@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.starfall.generative.features.machines.details.ui

import android.app.Activity
import android.content.ClipData
import android.net.Uri
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.LocalTextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.ByteArrayOutputStream
import org.starfall.generative.R
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.integrations.remote.ExecutableBinary
import org.starfall.generative.integrations.remote.REMOTE_TERMINAL_UI_PREFERENCES
import org.starfall.generative.integrations.remote.RemoteSecretRequest
import org.starfall.generative.integrations.remote.RemoteTerminal
import org.starfall.generative.features.machines.shared.ui.RemoteTerminalBridge
import org.starfall.generative.features.machines.shared.ui.RemoteMouseTouchListener
import org.starfall.generative.features.machines.shared.ui.RemoteTextField
import org.starfall.generative.integrations.remote.remoteTerminalInputModePreferenceKey
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.ui.dialogs.AppAlertDialog
import org.starfall.generative.shared.ui.icons.FluentIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.text.SelectableOutlinedTextField
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.layout.SlideScreenContent
import org.starfall.generative.features.machines.shared.ui.TerminalInputMode

private val TERMINAL_BACKGROUND = Color(0xFF101214)

@Composable
internal fun RemoteTerminalScreen(machine: RemoteMachine, viewModel: RemoteMachinesViewModel, onBack: () -> Unit) {
    var terminal by remember(machine.id) { mutableStateOf<RemoteTerminal?>(null) }
    var connecting by remember(machine.id) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var readerJob by remember { mutableStateOf<Job?>(null) }
    var retryAttempt by remember(machine.id) { mutableStateOf(0) }
    var secretRequest by remember(machine.id) { mutableStateOf<PendingSecretRequest?>(null) }
    var passwordInput by remember(machine.id) { mutableStateOf("") }
    var showPromptPassword by remember(machine.id) { mutableStateOf(false) }
    var inputModeMenuExpanded by remember(machine.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val terminalPrefs = remember {
        context.getSharedPreferences(REMOTE_TERMINAL_UI_PREFERENCES, android.content.Context.MODE_PRIVATE)
    }
    var fontDp by remember { mutableIntStateOf(terminalPrefs.getInt("font_dp", 14).coerceIn(MIN_TERMINAL_FONT_DP, MAX_TERMINAL_FONT_DP)) }
    val textSizePx = (fontDp * density).toInt()
    val initialInputMode = when (terminalPrefs.getString(remoteTerminalInputModePreferenceKey(machine.id), null)) {
        TerminalInputMode.LOCAL.name -> TerminalInputMode.LOCAL
        else -> TerminalInputMode.REMOTE
    }
    val bridge = remember(machine.id) {
        RemoteTerminalBridge(context, initialInputMode).also {
            it.forceMouse = terminalPrefs.getBoolean("force_mouse", false)
        }
    }
    SideEffect {
        bridge.onZoomStep = { increase ->
            fontDp = (fontDp + if (increase) 1 else -1).coerceIn(MIN_TERMINAL_FONT_DP, MAX_TERMINAL_FONT_DP)
            terminalPrefs.edit().putInt("font_dp", fontDp).apply()
        }
    }
    val currentTerminal by rememberUpdatedState(terminal)
    val currentReaderJob by rememberUpdatedState(readerJob)
    val currentSecretRequest by rememberUpdatedState(secretRequest)

    fun closeTerminal() {
        secretRequest?.answer?.complete(null)
        readerJob?.cancel()
        bridge.close()
        terminal?.close()
        terminal = null
    }
    // Phone back button: disconnect explicitly (same as the app bar back arrow).
    BackHandler(enabled = LocalScreenTransitionActive.current) { closeTerminal(); onBack() }
    DisposableEffect(machine.id) {
        onDispose {
            currentSecretRequest?.answer?.complete(null)
            currentReaderJob?.cancel()
            bridge.close()
            currentTerminal?.close()
        }
    }
    LaunchedEffect(machine.id, retryAttempt) {
        connecting = true
        error = null
        try {
            val opened = viewModel.openTerminal(machine) { request ->
                // The SSH thread pauses here only after the server requests an unsaved secret.
                val pending = PendingSecretRequest(request, CompletableDeferred())
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
            terminal = opened
            bridge.attachRemote(opened)
            connecting = false
            readerJob = launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                try {
                    while (true) {
                        val count = opened.input.read(buffer)
                        if (count < 0) break
                        val chunk = buffer.copyOf(count)
                        withContext(Dispatchers.Main) { bridge.append(chunk) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: java.io.IOException) {
                    // Stream closed: handled below like an end of stream.
                }
                // The remote side ended the session (not a local back/disconnect, which cancels us).
                if (coroutineContext.isActive) {
                    val cleanExit = opened.exitStatus >= 0
                    withContext(Dispatchers.Main) {
                        closeTerminal()
                        if (cleanExit) onBack() else error = "Connection lost"
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            connecting = false
            error = failure.message?.takeIf { it.isNotBlank() } ?: "Connection failed"
            terminal?.close()
            terminal = null
        }
    }

    Scaffold(
        containerColor = TERMINAL_BACKGROUND,
        topBar = {
            TopAppBar(
                title = { Text(machine.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { closeTerminal(); onBack() }) { FluentIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    Box {
                        IconButton(onClick = { inputModeMenuExpanded = true }) {
                            FluentIcon(Icons.Outlined.Settings, stringResource(R.string.remote_machine_terminal_input_settings))
                        }
                        DropdownMenu(
                            expanded = inputModeMenuExpanded,
                            onDismissRequest = { inputModeMenuExpanded = false },
                            containerColor = Color(0xFF1B1E21),
                            tonalElevation = 0.dp,
                            shadowElevation = 8.dp
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.remote_machine_terminal_local_input),
                                        color = Color.White
                                    )
                                },
                                trailingIcon = {
                                    if (bridge.inputMode == TerminalInputMode.LOCAL) Text("✓", color = Color(0xFF9CCC65))
                                },
                                onClick = {
                                    if (bridge.setInputMode(TerminalInputMode.LOCAL)) {
                                        terminalPrefs.edit()
                                            .putString(remoteTerminalInputModePreferenceKey(machine.id), TerminalInputMode.LOCAL.name)
                                            .apply()
                                    }
                                    inputModeMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                enabled = !bridge.hasLocalDraft,
                                text = {
                                    Text(
                                        stringResource(R.string.remote_machine_terminal_remote_input),
                                        color = if (bridge.hasLocalDraft) Color(0xFF777D82) else Color.White
                                    )
                                },
                                trailingIcon = {
                                    if (bridge.inputMode == TerminalInputMode.REMOTE) Text("✓", color = Color(0xFF9CCC65))
                                },
                                onClick = {
                                    if (bridge.setInputMode(TerminalInputMode.REMOTE)) {
                                        terminalPrefs.edit()
                                            .putString(remoteTerminalInputModePreferenceKey(machine.id), TerminalInputMode.REMOTE.name)
                                            .apply()
                                    }
                                    inputModeMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.remote_machine_terminal_force_mouse),
                                        color = Color.White
                                    )
                                },
                                trailingIcon = {
                                    if (bridge.forceMouse) Text("✓", color = Color(0xFF9CCC65))
                                },
                                onClick = {
                                    bridge.forceMouse = !bridge.forceMouse
                                    terminalPrefs.edit().putBoolean("force_mouse", bridge.forceMouse).apply()
                                    inputModeMenuExpanded = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TERMINAL_BACKGROUND,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().background(TERMINAL_BACKGROUND)
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                terminal?.let {
                    AndroidView(
                        factory = { viewContext ->
                            val view = TerminalView(viewContext, null)
                            view.setOnTouchListener(RemoteMouseTouchListener(viewContext, view, bridge))
                            view.isFocusable = true
                            view.isFocusableInTouchMode = true
                            view.setBackgroundColor(android.graphics.Color.rgb(16, 18, 20))
                            view.setTextSize(textSizePx)
                            view.tag = textSizePx
                            view.setTerminalViewClient(bridge)
                            bridge.view = view
                            val session = bridge.createSession(context.filesDir)
                            view.attachSession(session)
                            view
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = {
                            bridge.view = it
                            if (it.tag != textSizePx) {
                                it.tag = textSizePx
                                it.setTextSize(textSizePx)
                            }
                        }
                    )
                }
                bridge.localDraftForDisplay()?.let { draft ->
                    Surface(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xEE1B1E21)
                    ) {
                        val label = stringResource(R.string.remote_machine_terminal_local_input_label) + ": "
                        val draftScroll = rememberScrollState()
                        // Wrap long drafts; cap the height and keep the cursor line (the end) in view.
                        LaunchedEffect(draft) { draftScroll.scrollTo(draftScroll.maxValue) }
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Color(0xFF9CCC65))) { append(label) }
                                withStyle(SpanStyle(color = Color.White)) { append(draft) }
                            },
                            fontFamily = FontFamily.Monospace,
                            softWrap = true,
                            modifier = Modifier
                                .heightIn(max = 140.dp)
                                .verticalScroll(draftScroll)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                if (connecting && secretRequest == null) {
                    Surface(
                        Modifier.align(Alignment.Center),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.remote_machine_terminal_connecting))
                        }
                    }
                }
                if (!connecting && error != null) {
                    Surface(
                        Modifier.align(Alignment.Center).padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.remote_machine_terminal_connection_error),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Button(onClick = { retryAttempt++ }) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                }
                secretRequest?.let { pending ->
                    val request = pending.request
                    Surface(
                        Modifier.align(Alignment.Center).padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                stringResource(
                                    if (request.passphrase) R.string.remote_machine_terminal_passphrase_title
                                    else R.string.remote_machine_terminal_password_title
                                ),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.remote_machine_terminal_password_help),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            RemoteTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = when {
                                    request.passphrase -> stringResource(R.string.remote_machine_key_passphrase)
                                    request.prompt.isBlank() -> stringResource(R.string.remote_machine_password)
                                    else -> request.prompt.trim()
                                },
                                visual = if (showPromptPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showPromptPassword = !showPromptPassword }) {
                                        FluentIcon(
                                            if (showPromptPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = stringResource(
                                                if (showPromptPassword) R.string.remote_machine_hide_password else R.string.remote_machine_show_password
                                            )
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    pending.answer.complete(null)
                                    closeTerminal()
                                    onBack()
                                }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                                Button(
                                    enabled = passwordInput.isNotEmpty(),
                                    onClick = { pending.answer.complete(passwordInput) }
                                ) {
                                    Text(stringResource(R.string.remote_machine_terminal_connect))
                                }
                            }
                        }
                    }
                }
            }
            if (terminal != null) TerminalExtraKeys(bridge)
        }
    }
}

private class PendingSecretRequest(
    val request: RemoteSecretRequest,
    val answer: CompletableDeferred<String?>
)

private const val MIN_TERMINAL_FONT_DP = 8
private const val MAX_TERMINAL_FONT_DP = 32

@Composable
private fun TerminalExtraKeys(bridge: RemoteTerminalBridge) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1B1E21)).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TerminalKey("ESC") { bridge.sendSpecial("\u001b") }
            TerminalKey("TAB") { bridge.sendSpecial("\t") }
            TerminalKey("CTRL", active = bridge.ctrlActive) { bridge.ctrlActive = !bridge.ctrlActive }
            TerminalKey("ALT", active = bridge.altActive) { bridge.altActive = !bridge.altActive }
            TerminalKey("←", repeat = true) { bridge.sendSpecial("\u001b[D") }
            TerminalKey("↑", repeat = true) { bridge.sendSpecial("\u001b[A") }
            TerminalKey("↓", repeat = true) { bridge.sendSpecial("\u001b[B") }
            TerminalKey("→", repeat = true) { bridge.sendSpecial("\u001b[C") }
            TerminalKey("HOME") { bridge.sendSpecial("\u001b[H") }
            TerminalKey("END") { bridge.sendSpecial("\u001b[F") }
            TerminalKey("PGUP") { bridge.sendSpecial("\u001b[5~") }
            TerminalKey("PGDN") { bridge.sendSpecial("\u001b[6~") }
            listOf('|', '~', '-', '/', '\\', '_', '$', '&', '*', '<', '>', '`').forEach { char ->
                TerminalKey(char.toString()) { bridge.sendChar(char) }
            }
        }
        IconButton(onClick = { bridge.toggleKeyboard() }) {
            FluentIcon(Icons.Outlined.Keyboard, null, tint = Color.White)
        }
    }
}

@Composable
private fun TerminalKey(
    label: String,
    active: Boolean = false,
    repeat: Boolean = false,
    onPress: () -> Unit
) {
    val current by rememberUpdatedState(onPress)
    Box(
        Modifier
            .defaultMinSize(minWidth = 40.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color(0xFF2B2F33))
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    current()
                    if (repeat) {
                        coroutineScope {
                            val job = launch {
                                delay(350)
                                while (true) { current(); delay(60) }
                            }
                            tryAwaitRelease()
                            job.cancel()
                        }
                    } else {
                        tryAwaitRelease()
                    }
                })
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}
