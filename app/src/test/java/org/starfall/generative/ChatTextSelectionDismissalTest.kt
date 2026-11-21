package org.starfall.generative

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard as ComposeClipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.starfall.generative.shared.ui.text.PlatformTextSelection
import org.starfall.generative.shared.ui.text.PlatformTextSelectionRegion
import org.starfall.generative.shared.ui.text.SelectionClipboard
import org.starfall.generative.shared.ui.text.SelectionContainer

@RunWith(RobolectricTestRunner::class)
// API 27 avoids the native magnifier, which needs a real Android Surface.
@Config(sdk = [27], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatTextSelectionDismissalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun touchingBlankSpaceOrAnotherTargetReleasesSelectionAndStillClicks() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                PlatformTextSelection {
                    Column(Modifier.fillMaxSize()) {
                        PlatformTextSelectionRegion {
                            Column {
                                SelectionContainer {
                                    Text("First message to select", Modifier.testTag("message"))
                                }
                                Spacer(Modifier.fillMaxWidth().height(120.dp).testTag("blank"))
                                Button(onClick = { clicks++ }) { Text("Action") }
                                SelectionContainer {
                                    Text("Another message", Modifier.testTag("other"))
                                }
                            }
                        }
                        var input by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("editable input") }
                        BasicTextField(input, { input = it }, Modifier.testTag("input"))
                    }
                }
            }
        }

        fun selectMessage() {
            compose.onNodeWithTag("message").performTouchInput { longClick() }
            compose.onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(2)
        }
        fun assertSelectionDismissed() {
            // Selection handles are separate Compose popup roots.
            compose.onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
        }

        selectMessage()
        compose.onNodeWithTag("blank").performTouchInput { click() }
        assertSelectionDismissed()

        selectMessage()
        compose.onNodeWithText("Action").performTouchInput { click() }
        assertSelectionDismissed()
        compose.runOnIdle { assertEquals(1, clicks) }

        selectMessage()
        compose.onNodeWithTag("other").performTouchInput { click() }
        assertSelectionDismissed()
    }

    @Test fun editableTextKeepsNativeClipboardOutsideReadOnlySelectionRegion() {
        lateinit var editableClipboard: ComposeClipboard
        lateinit var messageClipboard: ComposeClipboard
        lateinit var editableToolbar: TextToolbar
        lateinit var messageToolbar: TextToolbar

        compose.setContent {
            MaterialTheme {
                PlatformTextSelection {
                    Column {
                        PlatformTextSelectionRegion {
                            messageClipboard = LocalClipboard.current
                            messageToolbar = LocalTextToolbar.current
                            SelectionContainer { Text("Read-only message", Modifier.testTag("read-only")) }
                        }
                        editableClipboard = LocalClipboard.current
                        editableToolbar = LocalTextToolbar.current
                        var value by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf("editable media prompt")
                        }
                        BasicTextField(value, { value = it }, Modifier.testTag("editable"))
                    }
                }
            }
        }

        compose.runOnIdle {
            assertTrue(messageClipboard is SelectionClipboard)
            assertFalse(editableClipboard is SelectionClipboard)
            assertNotSame(messageClipboard, editableClipboard)
            assertNotSame(messageToolbar, editableToolbar)
        }
        compose.onNodeWithTag("editable").performTouchInput { longClick() }
        compose.onNodeWithTag("editable").assertExists()
    }
}
