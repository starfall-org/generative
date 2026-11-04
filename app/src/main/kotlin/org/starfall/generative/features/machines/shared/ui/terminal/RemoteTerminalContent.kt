package org.starfall.generative.features.machines.shared.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.starfall.generative.R
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.remote.TerminalInputMode
import org.starfall.generative.shared.terminal.RemoteMouseTouchListener
import org.starfall.generative.shared.terminal.RemoteTerminalBridge
import org.starfall.generative.shared.terminal.RemoteTerminalSessions
import org.starfall.generative.shared.terminal.RemoteTerminalView
import org.starfall.generative.shared.terminal.TerminalSlot
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

@Composable
internal fun RemoteTerminalContent(
    machine: RemoteMachine,
    terminalSlots: List<TerminalSlot>,
    activeTerminalId: String?,
    activeBridge: RemoteTerminalBridge?,
    connecting: Boolean,
    error: String?,
    secretRequestVisible: Boolean,
    textSizePx: Int,
    terminalBackground: Color,
    terminalForeground: Color,
    terminalBackgroundArgb: Int,
    terminalForegroundArgb: Int,
    sessionsDrawerOpen: Boolean,
    onToggleSessions: () -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onInputModeChange: (TerminalInputMode) -> Unit,
    onToggleIme: () -> Unit,
    onToggleForceMouse: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSelect: (TerminalSlot) -> Unit,
    onClose: (TerminalSlot) -> Unit,
    onNew: () -> Unit
) {
    Column(
        Modifier.fillMaxSize()
            .background(terminalBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        TerminalAppBar(
            bridge = activeBridge,
            onToggleSessions = onToggleSessions,
            onBack = onBack,
            onDisconnect = onDisconnect,
            onInputModeChange = onInputModeChange,
            onToggleIme = onToggleIme,
            onToggleForceMouse = onToggleForceMouse
        )
        Row(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().background(terminalBackground)) {
                    terminalSlots.forEach { slot ->
                        key(slot.terminal.id) {
                            AndroidView(
                                factory = { viewContext ->
                                    val slotBridge = slot.bridge
                                    val host = RemoteTerminalView(viewContext, slotBridge)
                                    host.setBackgroundColor(terminalBackgroundArgb)
                                    val view = host.terminalView
                                    view.setOnTouchListener(RemoteMouseTouchListener(viewContext, view, slotBridge))
                                    view.setBackgroundColor(terminalBackgroundArgb)
                                    view.setTextSize(textSizePx)
                                    host.tag = textSizePx
                                    view.setTerminalViewClient(slotBridge)
                                    slotBridge.view = view
                                    slotBridge.inputView = host
                                    view.attachSession(slotBridge.createSession(viewContext.filesDir))
                                    slotBridge.setThemeColors(terminalBackgroundArgb, terminalForegroundArgb)
                                    host
                                },
                                modifier = if (slot.terminal.id == activeTerminalId) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier.size(0.dp)
                                },
                                onRelease = { view -> slot.bridge.detachView(view) },
                                update = { host ->
                                    val view = host.terminalView
                                    host.setBackgroundColor(terminalBackgroundArgb)
                                    slot.bridge.view = view
                                    slot.bridge.inputView = host
                                    if (host.tag != textSizePx) {
                                        host.tag = textSizePx
                                        view.setTextSize(textSizePx)
                                    }
                                    view.setBackgroundColor(terminalBackgroundArgb)
                                    slot.bridge.setThemeColors(terminalBackgroundArgb, terminalForegroundArgb)
                                }
                            )
                        }
                    }
                    TerminalOverlay(
                        activeBridge = activeBridge,
                        terminalSlotsEmpty = terminalSlots.isEmpty(),
                        connecting = connecting,
                        error = error,
                        secretRequestVisible = secretRequestVisible,
                        terminalForeground = terminalForeground,
                        onOpenTerminal = onOpenTerminal
                    )
                }
                if (activeBridge != null) TerminalExtraKeys(activeBridge)
            }
            if (sessionsDrawerOpen) VerticalDivider(Modifier.fillMaxHeight())
            AnimatedVisibility(
                visible = sessionsDrawerOpen,
                enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
            ) {
                TerminalSessionsSideBar(
                    machine = machine,
                    slots = terminalSlots,
                    activeTerminalId = activeTerminalId,
                    connecting = connecting,
                    onSelect = onSelect,
                    onClose = onClose,
                    onNew = onNew
                )
            }
        }
    }
}

@Composable
private fun BoxScope.TerminalOverlay(
    activeBridge: RemoteTerminalBridge?,
    terminalSlotsEmpty: Boolean,
    connecting: Boolean,
    error: String?,
    secretRequestVisible: Boolean,
    terminalForeground: Color,
    onOpenTerminal: () -> Unit
) {
    val draft = activeBridge?.localDraftForDisplay().orEmpty()
    if (draft.isNotEmpty()) {
        Surface(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp),
            shape = RoundedCornerShape(12.dp),
            color = prominentContainerColor(),
            border = prominentContainerBorder()
        ) {
            val draftScroll = rememberScrollState()
            LaunchedEffect(draft) { draftScroll.scrollTo(draftScroll.maxValue) }
            Text(
                draft,
                fontFamily = FontFamily.Monospace,
                softWrap = true,
                modifier = Modifier.heightIn(max = 140.dp)
                    .verticalScroll(draftScroll)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
    if (connecting && error == null && !secretRequestVisible) {
        Surface(
            Modifier.align(Alignment.Center),
            shape = RoundedCornerShape(12.dp),
            color = prominentContainerColor(),
            border = prominentContainerBorder()
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingIndicator(Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.remote_server_terminal_connecting))
            }
        }
    }
    if (terminalSlotsEmpty && !connecting && error == null && !secretRequestVisible) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SharedIcon(
                Icons.Outlined.Terminal,
                null,
                tint = terminalForeground.copy(alpha = 0.55f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                stringResource(R.string.remote_server_terminal_no_sessions),
                style = MaterialTheme.typography.titleMedium,
                color = terminalForeground
            )
            Text(
                stringResource(R.string.remote_server_terminal_no_sessions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = terminalForeground.copy(alpha = 0.65f)
            )
            FilledTonalButton(onClick = onOpenTerminal) {
                SharedIcon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.remote_server_terminal_add))
            }
        }
    }
    if (!connecting && error != null && !secretRequestVisible) {
        ErrorContainer(
            message = error,
            title = stringResource(R.string.remote_server_terminal_connection_error),
            onRetry = onOpenTerminal,
            modifier = Modifier.align(Alignment.Center).padding(16.dp)
        )
    }
}
