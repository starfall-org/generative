package org.starfall.generative

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.screens.ScreenDestination
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate
import org.starfall.generative.navigation.RetainedNavHost
import org.starfall.generative.navigation.RegisteredScreenRoute

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenDestinationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parentStaysComposedUntilEditorClosesAndEditorIsDisposed() {
        var editor by mutableStateOf<String?>(null)
        var parentMounts = 0
        var editorMounts = 0
        var editorDisposals = 0
        compose.setScreenContent {
            ScreenDestination(page = editor, onClosed = { editor = null }) { page ->
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

    @Test fun childAndRegularScreensShareTheNavigationBackStack() {
        lateinit var nav: NavHostController
        var editor by mutableStateOf<String?>(null)
        compose.setContent {
            nav = rememberNavController()
            RetainedNavHost(nav, startDestination = "parent") {
                composable("parent") {
                    ScreenDestination(editor, onClosed = { editor = null }) { page ->
                        Box(Modifier.fillMaxSize().testTag(page ?: "parent"))
                    }
                }
                composable("other") { Box(Modifier.fillMaxSize().testTag("other")) }
            }
        }
        compose.runOnIdle { editor = "child" }
        compose.onNodeWithTag("child").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(RegisteredScreenRoute, nav.currentDestination?.route)
            assertEquals("parent", nav.previousBackStackEntry?.destination?.route)
            nav.navigate("other")
        }
        compose.onNodeWithTag("other").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("child", editor)
            nav.popBackStack()
        }
        compose.onNodeWithTag("child").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithTag("parent").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, editor) }
        // Navigation cleared the selection, so the same editor can be opened again.
        compose.runOnIdle { editor = "child" }
        compose.onNodeWithTag("child").assertIsDisplayed()
    }

    @Test fun replacingAnEditorDoesNotClearTheNewSelection() {
        var editor by mutableStateOf<String?>(null)
        var closes = 0
        compose.setScreenContent {
            ScreenDestination(editor, onClosed = { closes++; editor = null }) { page ->
                Box(Modifier.fillMaxSize().testTag(page ?: "parent"))
            }
        }
        compose.runOnIdle { editor = "first" }
        compose.onNodeWithTag("first").assertIsDisplayed()
        compose.runOnIdle { editor = "second" }
        compose.onNodeWithTag("second").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("second", editor)
            assertEquals(0, closes)
        }
        compose.runOnIdle { editor = null }
        compose.onNodeWithTag("parent").assertIsDisplayed()
    }

    @Test fun registeredScreenInheritsFeatureLocalsAndReceivesLiveUpdates() {
        val localLabel = staticCompositionLocalOf { "missing" }
        var editor by mutableStateOf<String?>(null)
        var label by mutableStateOf("before")
        compose.setScreenContent {
            CompositionLocalProvider(localLabel provides "feature") {
                ScreenDestination(editor, onClosed = { editor = null }) { page ->
                    if (page != null) Text("${localLabel.current}: $label")
                }
            }
        }
        compose.runOnIdle { editor = "details" }
        compose.onNodeWithText("feature: before").assertIsDisplayed()
        compose.runOnIdle { label = "after" }
        compose.onNodeWithText("feature: after").assertIsDisplayed()
    }

    @Test fun systemBackBelongsOnlyToTheForegroundScreen() {
        lateinit var dispatcher: OnBackPressedDispatcher
        var editor by mutableStateOf<String?>(null)
        var parentBacks = 0
        var childBacks = 0
        compose.setScreenContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            ScreenDestination(editor, onClosed = { editor = null }) { page ->
                EditorScreenTemplate(
                    title = page ?: "parent",
                    onBack = {
                        if (page == null) parentBacks++ else { childBacks++; editor = null }
                    }
                ) { _ -> Box(Modifier.fillMaxSize().testTag(page ?: "parent")) }
            }
        }
        compose.runOnIdle { editor = "child" }
        compose.onNodeWithTag("child").assertIsDisplayed()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithTag("parent").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, childBacks)
            assertEquals(0, parentBacks)
            dispatcher.onBackPressed()
            assertEquals(1, parentBacks)
        }
    }

    @Test fun restoringARegisteredScreenPreservesDraftWithoutPushingADuplicate() {
        lateinit var nav: NavHostController
        lateinit var openEditor: () -> Unit
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            nav = rememberNavController()
            RetainedNavHost(nav, startDestination = "parent") {
                composable("parent") {
                    var editor by rememberSaveable { mutableStateOf<String?>(null) }
                    openEditor = { editor = "child" }
                    ScreenDestination(editor, onClosed = { editor = null }) { page ->
                        if (page != null) {
                            var count by rememberSaveable { mutableStateOf(0) }
                            Button(onClick = { count++ }) { Text("Draft $count") }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { openEditor() }
        compose.onNodeWithText("Draft 0").performClick()
        compose.onNodeWithText("Draft 1").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Draft 1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(RegisteredScreenRoute, nav.currentDestination?.route)
            assertEquals("parent", nav.previousBackStackEntry?.destination?.route)
        }
    }
}
