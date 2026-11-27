package org.starfall.generative

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.filters.SdkSuppress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.machines.shared.ui.terminal.RemoteTerminalContent
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.remote.RemoteTerminalSession
import org.starfall.generative.shared.model.remote.TerminalInputMode
import org.starfall.generative.shared.subfeatures.terminal.RemoteTerminalBridge
import org.starfall.generative.shared.subfeatures.terminal.TerminalSlot
import org.starfall.generative.shared.ui.layout.enableTransparentSystemBars

@SdkSuppress(minSdkVersion = 30)
class RemoteTerminalInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun keyboardFollowsCursorAndAllowsPanningWithoutResizingOrScrolling() {
        lateinit var composeView: View
        compose.activityRule.scenario.onActivity { it.enableTransparentSystemBars() }
        val bridge = RemoteTerminalBridge(compose.activity, TerminalInputMode.REMOTE)
        val terminal = object : RemoteTerminalSession {
            override val id = "terminal-insets"
            override val input = ByteArrayInputStream(byteArrayOf())
            override val output = ByteArrayOutputStream()
            override val isOpen = true
            override val exitStatus = 0
            override fun resize(columns: Int, rows: Int) = Unit
            override fun close() = Unit
        }
        val slots = listOf(TerminalSlot(terminal, bridge))
        try {
            compose.setContent {
                val view = LocalView.current
                SideEffect { composeView = view }
                MaterialTheme {
                    RemoteTerminalContent(
                        machine = RemoteMachine("terminal-insets", "Test"),
                        terminalSlots = slots,
                        activeTerminalId = terminal.id,
                        activeBridge = bridge,
                        connecting = false,
                        error = null,
                        secretRequestVisible = false,
                        textSizePx = 20,
                        terminalBackground = Color.Black,
                        terminalForeground = Color.White,
                        terminalBackgroundArgb = Color.Black.toArgb(),
                        terminalForegroundArgb = Color.White.toArgb(),
                        sessionsDrawerOpen = false,
                        onToggleSessions = {},
                        onBack = {},
                        onDisconnect = {},
                        onInputModeChange = {},
                        onToggleIme = {},
                        onToggleForceMouse = {},
                        onOpenTerminal = {},
                        onSelect = {},
                        onClose = {},
                        onNew = {}
                    )
                }
            }

            val (baseInsets, keyboardTravel) = compose.runOnIdle {
                checkNotNull(ViewCompat.getRootWindowInsets(composeView)) to (composeView.height / 4)
            }
            val systemBottom = baseInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ).bottom
            fun dispatchKeyboard(bottom: Int) = compose.runOnIdle {
                val insets = WindowInsetsCompat.Builder(baseInsets)
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(composeView, insets)
            }

            dispatchKeyboard(0)
            val initial = snapshot(bridge)
            dispatchKeyboard(systemBottom + keyboardTravel)
            assertEquals(initial, snapshot(bridge))

            compose.runOnIdle { bridge.append("\u001b[999;1H".toByteArray()) }
            val cursorAtBottom = snapshot(bridge)
            val cursorBottom = compose.runOnIdle { bridge.cursorBottomPx }
            assertTrue(cursorAtBottom.top < initial.top)
            assertTrue(cursorAtBottom.top + cursorBottom <= initial.top + initial.height - keyboardTravel)
            assertEquals(initial.copy(top = cursorAtBottom.top), cursorAtBottom)

            val handle = compose.onNodeWithContentDescription(
                compose.activity.getString(R.string.remote_terminal_pan_frame)
            )
            handle.performTouchInput { swipeDown() }
            val dragged = snapshot(bridge)
            assertTrue(dragged.top > cursorAtBottom.top)
            assertEquals(initial.copy(top = dragged.top), dragged)

            handle.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -initial.height.toFloat()) }
            assertEquals(initial, snapshot(bridge))
            compose.runOnIdle { bridge.sendChar('x') }
            assertEquals(cursorAtBottom, snapshot(bridge))

            handle.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, initial.height.toFloat()) }
            assertEquals(initial.copy(top = initial.top - keyboardTravel), snapshot(bridge))
            dispatchKeyboard(0)
            assertEquals(initial, snapshot(bridge))
        } finally {
            compose.runOnIdle { bridge.close() }
        }
    }

    private fun snapshot(bridge: RemoteTerminalBridge) = compose.runOnIdle {
        val terminal = checkNotNull(bridge.view)
        val emulator = checkNotNull(bridge.session?.emulator)
        val location = IntArray(2)
        terminal.getLocationInWindow(location)
        TerminalLayout(location[1], terminal.width, terminal.height, emulator.mColumns, emulator.mRows, terminal.topRow)
    }

    private data class TerminalLayout(
        val top: Int,
        val width: Int,
        val height: Int,
        val columns: Int,
        val rows: Int,
        val topRow: Int
    )
}
