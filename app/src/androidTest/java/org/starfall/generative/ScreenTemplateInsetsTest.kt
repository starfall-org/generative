package org.starfall.generative

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.ui.layout.enableTransparentSystemBars
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate
import org.starfall.generative.shared.ui.screens.ListScreenTemplate

/** Check the scroll viewport and last interactive row independently of the device's navigation mode. */
@SdkSuppress(minSdkVersion = 30)
class ScreenTemplateInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun listDrawsBehindNavigationButLastRowStaysSafe() = checkInsets(editor = false, keyboard = false)
    @Test fun editorDrawsBehindNavigationButLastRowStaysSafe() = checkInsets(editor = true, keyboard = false)
    @Test fun listConsumesKeyboardWithoutAddingNavigationPaddingAgain() = checkInsets(editor = false, keyboard = true)
    @Test fun editorConsumesKeyboardWithoutAddingNavigationPaddingAgain() = checkInsets(editor = true, keyboard = true)

    private fun checkInsets(editor: Boolean, keyboard: Boolean) {
        lateinit var view: View
        compose.activityRule.scenario.onActivity { it.enableTransparentSystemBars() }
        compose.setContent {
            val localView = LocalView.current
            SideEffect { view = localView }
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    if (editor) {
                        EditorScreenTemplate(title = "Editor", onBack = {}) { padding ->
                            LazyColumn(Modifier.fillMaxSize().testTag("rows"), contentPadding = padding) {
                                items(40) { Text("Row $it", Modifier.height(48.dp).testTag("row-$it")) }
                            }
                        }
                    } else {
                        ListScreenTemplate(title = "List", onBack = {}, bottomPadding = 0.dp) { padding ->
                            LazyColumn(Modifier.fillMaxSize().testTag("rows"), contentPadding = padding) {
                                items(40) { Text("Row $it", Modifier.height(48.dp).testTag("row-$it")) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val navigationHeight = 72
        val keyboardHeight = if (keyboard) 280 else 0
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder(ViewCompat.getRootWindowInsets(view)!!)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationHeight))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardHeight))
                .setVisible(WindowInsetsCompat.Type.ime(), keyboard)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("rows").performScrollToIndex(39)
        val root = compose.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("rows").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("row-39").fetchSemanticsNode().boundsInRoot
        assertEquals("Viewport draws behind navigation, but stops at the keyboard",
            root.bottom - keyboardHeight, viewport.bottom, 1f)
        assertEquals("Last row stays safe without counting navigation and IME twice",
            root.bottom - maxOf(navigationHeight, keyboardHeight), last.bottom, 1f)
    }
}
