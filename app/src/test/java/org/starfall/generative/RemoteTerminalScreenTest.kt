package org.starfall.generative

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.theme.GenerativeTheme
import org.starfall.generative.shared.ui.terminal.RemoteTerminalScreen

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class RemoteTerminalScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sessionsOpenOnTheRightAndDisconnectClosesWhileConnecting() {
        var visible by mutableStateOf(true)
        var cancelled = false
        var disconnected: String? = null
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                if (visible) RemoteTerminalScreen(
                    machine = RemoteMachine("connecting", "Test"),
                    openTerminal = { _, _ -> try { awaitCancellation() } finally { cancelled = true } },
                    disconnect = { disconnected = it },
                    onDismiss = { visible = false }
                )
            }
        }
        val disconnect = compose.onNodeWithContentDescription("Disconnect and close terminal")
        val sessions = compose.onNodeWithContentDescription("Show or hide terminal sessions")
        assertTrue(sessions.getUnclippedBoundsInRoot().left > disconnect.getUnclippedBoundsInRoot().right)
        sessions.performClick()
        compose.onNodeWithText("Sessions").assertIsDisplayed()
        disconnect.performClick()
        compose.runOnIdle {
            assertFalse(visible)
            assertTrue(cancelled)
            assertEquals("connecting", disconnected)
        }
    }

    @Test
    fun appBarBackLeavesWithoutDisconnecting() {
        var visible by mutableStateOf(true)
        var disconnected = false
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                if (visible) RemoteTerminalScreen(
                    machine = RemoteMachine("toolbar-back", "Test"),
                    openTerminal = { _, _ -> awaitCancellation() },
                    disconnect = { disconnected = true },
                    onDismiss = { visible = false }
                )
            }
        }
        val back = compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        val disconnect = compose.onNodeWithContentDescription("Disconnect and close terminal").assertIsDisplayed()
        assertTrue(back.getUnclippedBoundsInRoot().right <= disconnect.getUnclippedBoundsInRoot().left)
        back.performClick()
        compose.runOnIdle {
            assertFalse(visible)
            assertFalse(disconnected)
        }
    }

    @Test
    fun systemBackLeavesWithoutDisconnectingAndCancelsPendingConnection() {
        var visible by mutableStateOf(true)
        var cancelled = false
        var disconnected = false
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                if (visible) RemoteTerminalScreen(
                    machine = RemoteMachine("back", "Test"),
                    openTerminal = { _, _ -> try { awaitCancellation() } finally { cancelled = true } },
                    disconnect = { disconnected = true },
                    onDismiss = { visible = false }
                )
            }
        }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertFalse(visible)
            assertFalse(disconnected)
            assertTrue(cancelled)
        }
    }
}
