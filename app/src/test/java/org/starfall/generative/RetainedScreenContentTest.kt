package org.starfall.generative

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.screens.RetainedScreenContent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetainedScreenContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parentStaysComposedUntilEditorClosesAndEditorIsDisposed() {
        var editor by mutableStateOf<String?>(null)
        var parentMounts = 0
        var editorMounts = 0
        var editorDisposals = 0
        compose.setContent {
            RetainedScreenContent(editor = editor) { page ->
                if (page == null) {
                    DisposableEffect(Unit) {
                        parentMounts++
                        onDispose { }
                    }
                    Box(Modifier.fillMaxSize().testTag("parent"))
                } else {
                    DisposableEffect(Unit) {
                        editorMounts++
                        onDispose { editorDisposals++ }
                    }
                    Box(Modifier.fillMaxSize().testTag("editor"))
                }
            }
        }
        compose.onNodeWithTag("parent").assertIsDisplayed()
        compose.runOnIdle { editor = "details" }
        compose.onNodeWithTag("editor").assertIsDisplayed()
        compose.runOnIdle { editor = null }
        compose.onNodeWithTag("parent").assertIsDisplayed()
        compose.waitForIdle() // Editor leaves composition after the closing slide.
        compose.runOnIdle {
            assertEquals(1, parentMounts)
            assertEquals(1, editorMounts)
            assertEquals(1, editorDisposals)
        }
    }
}
