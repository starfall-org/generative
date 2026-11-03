package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.home.drawer.ui.DrawerQuickShortcuts
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.remote.RemoteTerminalSession
import org.starfall.generative.shared.terminal.RemoteTerminalSessions
import org.starfall.generative.shared.theme.GenerativeTheme
import org.starfall.generative.features.home.shared.ui.LocalToolControls
import org.starfall.generative.shared.ui.RuntimeAppLocale
import org.starfall.generative.features.home.shared.ui.ToolControls
import org.starfall.generative.features.machines.shared.ui.terminal.RemoteTerminalScreen
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Runs the actual Compose terminal / selection UIs on the connected Android device. */
class RemoteNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun toolbarBackPreservesOpenTerminalAndReconnectUsesSameSession() {
        val machine = RemoteMachine("device-terminal-back-regression", "Session test")
        val session = FakeOpenTerminal()
        var visible by mutableStateOf(true)
        var disconnectCalls = 0
        var openCalls = 0
        try {
            compose.setContent {
                EnglishRemoteTestTheme {
                    if (visible) {
                        RemoteTerminalScreen(
                            machine = machine,
                            openTerminal = { _, _ ->
                                openCalls++
                                session
                            },
                            disconnect = { disconnectCalls++ },
                            onDismiss = { visible = false }
                        )
                    }
                }
            }
            compose.waitUntil(15_000) {
                RemoteTerminalSessions.slotsFor(machine.id).size == 1
            }
            val back = compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            val disconnect = compose.onNodeWithContentDescription("Disconnect and close terminal")
                .assertIsDisplayed()
            assertTrue("Back must be before Disconnect",
                back.getUnclippedBoundsInRoot().right <= disconnect.getUnclippedBoundsInRoot().left)
            back.performClick()
            compose.runOnIdle {
                assertFalse(visible)
                assertTrue("Back must not close the terminal channel", session.isOpen)
                assertEquals(0, disconnectCalls)
                assertEquals(1, RemoteTerminalSessions.slotsFor(machine.id).size)
                visible = true
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("Reopening must reuse the same shell", 1, openCalls)
                assertTrue(session.isOpen)
                assertEquals(1, RemoteTerminalSessions.slotsFor(machine.id).size)
            }
            compose.onNodeWithContentDescription("Disconnect and close terminal").performClick()
            compose.runOnIdle {
                assertFalse(visible)
                assertFalse("Disconnect must close the terminal channel", session.isOpen)
                assertEquals(1, disconnectCalls)
                assertTrue(RemoteTerminalSessions.slotsFor(machine.id).isEmpty())
            }
        } finally {
            compose.runOnIdle {
                RemoteTerminalSessions.closeAll(machine.id)
            }
            session.close()
        }
    }

    @Test
    fun drawerSelectsOneMachineAndShowsPortForwardAndUnset() {
        val machines = listOf(
            RemoteMachine("device-drawer-a", "Alpha"),
            RemoteMachine("device-drawer-b", "Beta")
        )
        var selectedId by mutableStateOf<String?>(null)
        var forwarded: RemoteMachine? = null
        compose.setContent {
            EnglishRemoteTestTheme {
                CompositionLocalProvider(
                    LocalToolControls provides ToolControls(
                        machines = machines,
                        settings = ToolSettings(
                            machinesEnabled = selectedId != null,
                            enabledMachines = machines.associate { it.id to (it.id == selectedId) }
                        ),
                        selectMachine = { selectedId = it }
                    )
                ) {
                    DrawerQuickShortcuts(
                        onOpenMediaStorage = {},
                        onOpenRemoteMachines = {},
                        onPortForward = { forwarded = it },
                        onSaveMachineTools = {},
                        maxExpandedHeight = 400.dp
                    )
                }
            }
        }
        compose.onNodeWithText("Remote Services").performClick()
        compose.onNodeWithText("Select server").assertIsDisplayed()
        compose.onNodeWithTag("drawer_machine_picker_trigger").performClick()
        compose.onNodeWithTag("drawer_machine_picker_item_device-drawer-b").performClick()
        compose.runOnIdle { assertEquals("device-drawer-b", selectedId) }
        compose.onNodeWithTag("drawer_machine_options_device-drawer-b").performClick()
        compose.onNodeWithText("Port Forward").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(machines[1], forwarded) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Port Forward").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("drawer_machine_options_device-drawer-b").performClick()
        compose.onNodeWithText("Unset").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(null, selectedId) }
        compose.onNodeWithText("Select server").assertIsDisplayed()
    }

    private class FakeOpenTerminal : RemoteTerminalSession {
        override val id = "fake-open-terminal"
        private val inputStream = PipedInputStream()
        private val writer = PipedOutputStream(inputStream)
        override val input: InputStream = inputStream
        override val output: OutputStream = ByteArrayOutputStream()
        @Volatile private var alive = true
        override val isOpen: Boolean get() = alive
        override val exitStatus: Int get() = -1
        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() {
            alive = false
            runCatching { writer.close() }
            runCatching { inputStream.close() }
            runCatching { output.close() }
        }
    }
}

@Composable
private fun EnglishRemoteTestTheme(content: @Composable () -> Unit) {
    RuntimeAppLocale(useSystemLanguage = false, selectedLanguage = "en") {
        GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false, content = content)
    }
}
