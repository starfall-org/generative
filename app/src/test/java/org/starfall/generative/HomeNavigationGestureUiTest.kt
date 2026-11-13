package org.starfall.generative

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.navigation.LocalRetainedScreenVisible
import org.starfall.generative.navigation.RetainedNavHost

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w480dp-h960dp-xhdpi")
class HomeNavigationGestureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingAppMenuDoesNotHideTheDrawerBeforeChatSlidesAway() {
        lateinit var nav: NavHostController
        lateinit var scope: CoroutineScope
        lateinit var drawer: DrawerState
        lateinit var back: OnBackPressedDispatcher
        var displayedDrawerOpen = false
        compose.setContent { MaterialTheme {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            nav = rememberNavController()
            scope = rememberCoroutineScope()
            drawer = rememberDrawerState(DrawerValue.Closed)
            RetainedNavHost(nav, startDestination = "chat") {
                composable("chat") {
                    val parkedDrawer = rememberDrawerState(DrawerValue.Closed)
                    val displayedDrawer = if (LocalRetainedScreenVisible.current) drawer else parkedDrawer
                    val isDrawerOpen = displayedDrawer.isOpen
                    SideEffect { displayedDrawerOpen = isDrawerOpen }
                    ModalNavigationDrawer(
                        drawerState = displayedDrawer,
                        drawerContent = { ModalDrawerSheet {
                            Text("Conversations")
                            Button(onClick = { nav.navigate("features") }) { Text("Open AppMenu") }
                        } }
                    ) { Box(Modifier.fillMaxSize().testTag("chat-root")) }
                }
                composable("features") { Box(Modifier.fillMaxSize().testTag("menu-root")) }
            }
        } }

        compose.runOnIdle { scope.launch { drawer.open() } }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(displayedDrawerOpen) }

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Open AppMenu").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("features", nav.currentDestination?.route)
            assertTrue("The outgoing Chat must retain its open drawer while sliding", displayedDrawerOpen)
        }

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("The parked drawer must not intercept Back", displayedDrawerOpen)
            assertTrue("Keep the real drawer state for the return to Chat", drawer.isOpen)
        }
        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("chat", nav.currentDestination?.route)
            assertTrue("Returning to Chat restores the drawer", displayedDrawerOpen)
        }
    }

    @Test fun horizontalGesturesDoNotNavigateAndDrawerKeepsNativeSwipe() {
        lateinit var nav: NavHostController
        lateinit var scope: CoroutineScope
        lateinit var drawer: DrawerState
        var drawerOpen = false
        compose.setContent { MaterialTheme {
            nav = rememberNavController()
            scope = rememberCoroutineScope()
            val entry by nav.currentBackStackEntryAsState()
            drawer = rememberDrawerState(DrawerValue.Closed)
            drawerOpen = drawer.isOpen
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = entry?.destination?.route == "chat",
                drawerContent = { ModalDrawerSheet { Text("Conversations") } }
            ) {
                RetainedNavHost(nav, startDestination = "chat") {
                    composable("chat") { Box(Modifier.fillMaxSize().testTag("chat-root")) }
                    composable("features") { Box(Modifier.fillMaxSize().testTag("menu-root")) }
                }
            }
        } }
        compose.onNodeWithTag("chat-root").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("chat-root").assertIsDisplayed()
        compose.onNodeWithTag("chat-root").performTouchInput {
            swipe(Offset(1f, height / 2f), Offset(width * .8f, height / 2f), 300)
        }
        compose.waitForIdle()
        assertTrue("Drawer retains its native edge swipe", drawerOpen)
        compose.runOnIdle { scope.launch { drawer.close() } }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate("features") }
        compose.waitForIdle()
        compose.onNodeWithTag("menu-root").assertIsDisplayed()
        compose.onNodeWithTag("menu-root").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals("Horizontal dragging cannot navigate", "features", nav.currentDestination?.route)
    }
}
