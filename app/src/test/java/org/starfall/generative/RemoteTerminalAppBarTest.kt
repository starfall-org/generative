package org.starfall.generative

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.remote.TerminalInputMode
import org.starfall.generative.shared.terminal.RemoteTerminalBridge
import org.starfall.generative.shared.theme.GenerativeTheme
import org.starfall.generative.features.machines.shared.ui.terminal.TerminalAppBar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class RemoteTerminalAppBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun inputModeOpensMenuAndImeIsOnlyAvailableInLocalMode() {
        val bridge = RemoteTerminalBridge(ApplicationProvider.getApplicationContext<Context>(), TerminalInputMode.REMOTE)
        var modeChanges = 0
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                TerminalAppBar(
                    bridge = bridge,
                    onToggleSessions = {},
                    onBack = {},
                    onDisconnect = {},
                    onInputModeChange = { bridge.setInputMode(it); modeChanges++ },
                    onToggleIme = { bridge.updateImeEnabled(!bridge.imeEnabled) },
                    onToggleForceMouse = { bridge.forceMouse = !bridge.forceMouse }
                )
            }
        }
        compose.onNodeWithContentDescription("Enable IME composition").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithContentDescription("Terminal input mode: Remote").performClick()
        compose.runOnIdle { assertEquals(0, modeChanges) }
        compose.onNodeWithText("Local").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(TerminalInputMode.LOCAL, bridge.inputMode) }
        compose.onNodeWithContentDescription("Enable IME composition").performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(TerminalInputMode.LOCAL, bridge.inputMode)
            bridge.updateLocalImeDraft("unsent command", 5)
        }
        compose.onNodeWithContentDescription("Terminal input mode: Local").performClick()
        compose.onNodeWithText("Remote").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("unsent command", bridge.localInputForIme) }
        compose.onNodeWithContentDescription("Enable IME composition").assertIsNotEnabled().assertIsOff()
        compose.runOnIdle { assertEquals(false, bridge.imeEnabled) }
        compose.onNodeWithContentDescription("Terminal input mode: Remote").performClick()
        compose.onNodeWithText("Local").performClick()
        compose.onNodeWithContentDescription("Enable IME composition").assertIsEnabled().assertIsOff()
        compose.onNodeWithContentDescription("Mouse events · tap = click, drag = wheel").performClick().assertIsOn()
        compose.onNodeWithContentDescription("Show or hide terminal sessions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Terminal font size").assertDoesNotExist()
        bridge.close()
    }

    @Test
    fun appBarDoesNotHaveKeyboardDismissButton() {
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                TerminalAppBar(
                    bridge = null, onToggleSessions = {}, onBack = {}, onDisconnect = {},
                    onInputModeChange = {},
                    onToggleIme = {}, onToggleForceMouse = {}
                )
            }
        }
        compose.onNodeWithContentDescription("Hide keyboard").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show or hide terminal sessions").assertIsDisplayed()
    }

    @Test
    fun backIsLeftOfDisconnectAndDoesNotInvokeDisconnect() {
        var backedOut = 0
        var disconnected = 0
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                TerminalAppBar(
                    bridge = null,
                    onToggleSessions = {},
                    onBack = { backedOut++ },
                    onDisconnect = { disconnected++ },
                    onInputModeChange = {},
                    onToggleIme = {},
                    onToggleForceMouse = {}
                )
            }
        }
        val back = compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        val disconnect = compose.onNodeWithContentDescription("Disconnect and close terminal").assertIsDisplayed()
        assertEquals(true, back.fetchSemanticsNode().boundsInRoot.right <=
            disconnect.fetchSemanticsNode().boundsInRoot.left)
        back.performClick()
        compose.runOnIdle {
            assertEquals(1, backedOut)
            assertEquals(0, disconnected)
        }
    }

    @Test
    fun disconnectAlsoClosesTheScreenWithoutAnActiveSession() {
        var closed = false
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                TerminalAppBar(
                    bridge = null,
                    onToggleSessions = {},
                    onBack = {},
                    onDisconnect = { closed = true },
                    onInputModeChange = {},
                    onToggleIme = {},
                    onToggleForceMouse = {}
                )
            }
        }
        compose.onNodeWithContentDescription("Disconnect and close terminal").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, closed) }
    }
}
