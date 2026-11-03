package org.starfall.generative

import android.content.Context
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.remote.TerminalInputMode
import org.starfall.generative.shared.model.remote.RemoteTerminalSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.starfall.generative.shared.terminal.RemoteTerminalBridge
import org.starfall.generative.shared.theme.GenerativeTheme
import org.starfall.generative.features.machines.shared.ui.terminal.TerminalExtraKeys

private val DpRect.center: Offset
    get() = Offset((left.value + right.value) / 2f, (top.value + bottom.value) / 2f)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class RemoteTerminalExtraKeysTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun toolbarKeysReachRemoteInBothModesWithoutChangingTheLocalDraft() {
        val outputBytes = ByteArrayOutputStream()
        val terminal = object : RemoteTerminalSession {
            override val id = "toolbar-test"
            override val input: InputStream = ByteArrayInputStream(byteArrayOf())
            override val output: OutputStream = outputBytes
            override val isOpen = true
            override val exitStatus = 0
            override fun resize(columns: Int, rows: Int) = Unit
            override fun close() = Unit
        }
        val bridge = RemoteTerminalBridge(
            ApplicationProvider.getApplicationContext<Context>(),
            TerminalInputMode.LOCAL
        )
        try {
            bridge.attachRemote(terminal)
            bridge.updateLocalImeDraft("pending command", 4)
            compose.setContent {
                GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                    TerminalExtraKeys(bridge)
                }
            }
            for (mode in TerminalInputMode.entries) {
                compose.runOnIdle {
                    bridge.setInputMode(mode)
                    outputBytes.reset()
                }
                compose.onNodeWithText("Esc").performTouchInput { click() }
                compose.onNodeWithText("Tab").performTouchInput { click() }
                compose.onNodeWithText("/").performTouchInput { click() }
                compose.onNodeWithText("←").performTouchInput { click() }
                compose.waitUntil(3_000) {
                    outputBytes.toString("UTF-8") == "\u001b\t/\u001b[D"
                }
                compose.runOnIdle {
                    assertEquals("pending command", bridge.localInputForIme)
                    assertEquals(4, bridge.localCursorForIme)
                }
            }
        } finally {
            bridge.close()
        }
    }

    @Test
    fun extraKeysKeepTerminalControlsAndPlaceUpAboveTheOtherArrows() {
        val bridge = RemoteTerminalBridge(
            ApplicationProvider.getApplicationContext<Context>(),
            TerminalInputMode.REMOTE
        )
        try {
            compose.setContent {
                GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                    TerminalExtraKeys(bridge)
                }
            }

            listOf("Esc", "Tab", "Shift", "Ctrl", "Alt", "Home", "End", "P↑", "P↓", "/")
                .forEach { compose.onNodeWithText(it).assertExists() }
            listOf("|", "~", "-", "\\", "_", "$", "&", "*", "<", ">")
                .forEach { compose.onNodeWithText(it).assertDoesNotExist() }

            val up = compose.onNodeWithText("↑").getUnclippedBoundsInRoot()
            val left = compose.onNodeWithText("←").getUnclippedBoundsInRoot()
            val down = compose.onNodeWithText("↓").getUnclippedBoundsInRoot()
            val right = compose.onNodeWithText("→").getUnclippedBoundsInRoot()
            assertTrue(up.bottom <= down.top)
            assertEquals(down.center.x, up.center.x, 1f)
            assertEquals(left.center.y, down.center.y, 1f)
            assertEquals(right.center.y, down.center.y, 1f)
            assertTrue(left.right <= down.left)
            assertTrue(down.right <= right.left)
            val shift = compose.onNodeWithText("Shift").getUnclippedBoundsInRoot()
            val esc = compose.onNodeWithText("Esc").getUnclippedBoundsInRoot()
            val ctrl = compose.onNodeWithText("Ctrl").getUnclippedBoundsInRoot()
            val tab = compose.onNodeWithText("Tab").getUnclippedBoundsInRoot()
            val alt = compose.onNodeWithText("Alt").getUnclippedBoundsInRoot()
            val slash = compose.onNodeWithText("/").getUnclippedBoundsInRoot()
            val pageUp = compose.onNodeWithText("P↑").getUnclippedBoundsInRoot()
            val pageDown = compose.onNodeWithText("P↓").getUnclippedBoundsInRoot()
            val home = compose.onNodeWithText("Home").getUnclippedBoundsInRoot()
            val end = compose.onNodeWithText("End").getUnclippedBoundsInRoot()
            assertEquals(esc.center.x, ctrl.center.x, 1f)
            assertEquals(tab.center.x, alt.center.x, 1f)
            assertEquals(shift.center.x, left.center.x, 1f)
            assertEquals(slash.center.x, right.center.x, 1f)
            assertEquals(pageUp.center.x, pageDown.center.x, 1f)
            assertEquals(home.center.x, end.center.x, 1f)
            assertEquals(esc.center.y, home.center.y, 1f)
            assertEquals(ctrl.center.y, end.center.y, 1f)
        } finally {
            bridge.close()
        }
    }
}
