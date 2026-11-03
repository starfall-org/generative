package org.starfall.generative.features.machines.shared.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.terminal.RemoteTerminalBridge
import org.starfall.generative.shared.terminal.TerminalSlot
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.theme.LocalAmoledMode

internal const val MIN_TERMINAL_FONT_DP = 8
internal const val MAX_TERMINAL_FONT_DP = 32

@Composable
internal fun TerminalSessionsSideBar(
    machine: RemoteMachine,
    slots: List<TerminalSlot>,
    activeTerminalId: String?,
    connecting: Boolean,
    onSelect: (TerminalSlot) -> Unit,
    onClose: (TerminalSlot) -> Unit,
    onNew: () -> Unit
) {
    Surface(
        Modifier.width(252.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.remote_server_terminal_sessions),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(
                            if (slots.isEmpty()) R.string.remote_server_terminal_disconnected
                            else R.string.remote_server_terminal_connected
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(enabled = !connecting, onClick = onNew) {
                    SharedIcon(Icons.Default.Add, stringResource(R.string.remote_server_terminal_add))
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(slots, key = { _, slot -> slot.terminal.id }) { index, slot ->
                    val selected = slot.terminal.id == activeTerminalId
                    val amoledSelected = selected && LocalAmoledMode.current
                    Surface(
                        onClick = { onSelect(slot) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected && !amoledSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        border = if (amoledSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TerminalStatusDot(slot.terminal.isOpen)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.remote_server_terminal_tab, index + 1),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${machine.username.ifBlank { "?" }}@${machine.displayHost}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onClose(slot) }, modifier = Modifier.size(32.dp)) {
                                SharedIcon(
                                    Icons.Default.Close,
                                    stringResource(R.string.remote_server_terminal_close_session),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                if (slots.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.remote_server_terminal_no_sessions_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TerminalExtraKeys(bridge: RemoteTerminalBridge) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Seven aligned columns, two rows. Keep the D-pad at columns 3-5 and allow
            // horizontal scrolling only when the display is too narrow for usable keys.
            BoxWithConstraints(Modifier.weight(1f)) {
                val keyWidth = maxOf(42.dp, (maxWidth - 32.dp) / 7)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    @Composable
                    fun KeyColumn(top: @Composable () -> Unit, bottom: @Composable () -> Unit) {
                        Column(Modifier.width(keyWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            top()
                            bottom()
                        }
                    }
                    KeyColumn(
                        { TerminalKey("Esc", Modifier.fillMaxWidth()) { bridge.sendSpecial("\u001b") } },
                        { TerminalKey("Ctrl", Modifier.fillMaxWidth(), active = bridge.ctrlActive) { bridge.ctrlActive = !bridge.ctrlActive } }
                    )
                    KeyColumn(
                        { TerminalKey("Tab", Modifier.fillMaxWidth()) { bridge.sendSpecial("\t") } },
                        { TerminalKey("Alt", Modifier.fillMaxWidth(), active = bridge.altActive) { bridge.altActive = !bridge.altActive } }
                    )
                    KeyColumn(
                        { TerminalKey("Shift", Modifier.fillMaxWidth(), active = bridge.shiftActive) { bridge.shiftActive = !bridge.shiftActive } },
                        { TerminalKey("←", Modifier.fillMaxWidth(), repeat = true) { bridge.sendSpecial("\u001b[D", forceRemote = true) } }
                    )
                    KeyColumn(
                        { TerminalKey("↑", Modifier.fillMaxWidth(), repeat = true) { bridge.sendSpecial("\u001b[A", forceRemote = true) } },
                        { TerminalKey("↓", Modifier.fillMaxWidth(), repeat = true) { bridge.sendSpecial("\u001b[B", forceRemote = true) } }
                    )
                    KeyColumn(
                        { TerminalKey("/", Modifier.fillMaxWidth()) { bridge.sendChar('/') } },
                        { TerminalKey("→", Modifier.fillMaxWidth(), repeat = true) { bridge.sendSpecial("\u001b[C", forceRemote = true) } }
                    )
                    KeyColumn(
                        { TerminalKey("P↑", Modifier.fillMaxWidth()) { bridge.sendSpecial("\u001b[5~", forceRemote = true) } },
                        { TerminalKey("P↓", Modifier.fillMaxWidth()) { bridge.sendSpecial("\u001b[6~", forceRemote = true) } }
                    )
                    KeyColumn(
                        { TerminalKey("Home", Modifier.fillMaxWidth()) { bridge.sendSpecial("\u001b[H", forceRemote = true) } },
                        { TerminalKey("End", Modifier.fillMaxWidth()) { bridge.sendSpecial("\u001b[F", forceRemote = true) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalKey(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    repeat: Boolean = false,
    onPress: () -> Unit
) {
    val current by rememberUpdatedState(onPress)
    val keyShape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .defaultMinSize(minWidth = 40.dp)
            .height(38.dp)
            .clip(keyShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .then(
                if (!active && LocalAmoledMode.current) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, keyShape)
                } else {
                    Modifier
                }
            )
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    current()
                    if (repeat) {
                        coroutineScope {
                            val job = launch {
                                delay(350)
                                while (true) {
                                    current()
                                    delay(60)
                                }
                            }
                            tryAwaitRelease()
                            job.cancel()
                        }
                    } else {
                        tryAwaitRelease()
                    }
                })
            }
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

@Composable
private fun TerminalStatusDot(connected: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
            )
    )
}
