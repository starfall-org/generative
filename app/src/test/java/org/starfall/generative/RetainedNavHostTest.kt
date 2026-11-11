package org.starfall.generative

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.navigation.RetainedNavHost
import org.starfall.generative.navigation.movesMenuBackground
import org.starfall.generative.shared.ui.screens.screenSlideOffset
import org.starfall.generative.shared.ui.screens.screenScale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetainedNavHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backStackRetainsCompositionsAndPopDestroysOnlyPoppedEntries() {
        lateinit var nav: NavHostController
        val mounts = mutableMapOf<String, Int>()
        val disposals = mutableMapOf<String, Int>()
        compose.setContent {
            nav = rememberNavController()
            RetainedNavHost(nav, startDestination = "chat") {
                listOf("chat", "features", "providers").forEach { route ->
                    composable(route) {
                        DisposableEffect(Unit) {
                            mounts[route] = (mounts[route] ?: 0) + 1
                            onDispose { disposals[route] = (disposals[route] ?: 0) + 1 }
                        }
                        Box(Modifier.fillMaxSize().testTag(route))
                    }
                }
            }
        }
        compose.onNodeWithTag("chat").assertIsDisplayed()
        compose.runOnIdle { nav.navigate("features") }
        compose.onNodeWithTag("features").assertIsDisplayed()
        compose.runOnIdle { nav.navigate("providers") }
        compose.onNodeWithTag("providers").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, mounts["chat"])
            assertEquals(1, mounts["features"])
            assertEquals(1, mounts["providers"])
            assertEquals(null, disposals["chat"])
            assertEquals(null, disposals["features"])
        }
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithTag("features").assertIsDisplayed()
        compose.waitForIdle() // The popped page stays mounted until its slide completes.
        compose.runOnIdle {
            assertEquals(1, disposals["providers"])
            assertEquals(1, mounts["features"])
        }
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithTag("chat").assertIsDisplayed()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, disposals["features"])
            assertEquals(1, mounts["chat"])
            assertEquals(null, disposals["chat"])
        }
    }

    @Test fun onlyHomeAndAppMenuMoveTheSharedPhoto() {
        assertTrue(movesMenuBackground("chat", "features"))
        assertTrue(movesMenuBackground("features", "chat"))
        assertFalse(movesMenuBackground("features", "providers"))
        assertFalse(movesMenuBackground("providers", "features"))
        assertFalse(movesMenuBackground("chat", "chat_options"))
        assertFalse(movesMenuBackground("providers", "chat"))
    }

    @Test fun frontPageSlidesOverARecedingBackPage() {
        assertEquals(1f, screenSlideOffset(active = false, front = true), 0f)
        assertEquals(-0.12f, screenSlideOffset(active = false, front = false), 0f)
        assertEquals(0f, screenSlideOffset(active = true, front = true), 0f)
        assertEquals(0f, screenSlideOffset(active = true, front = false), 0f)
        assertEquals(1f, screenScale(active = false, front = true), 0f)
        assertEquals(0.94f, screenScale(active = false, front = false), 0f)
        assertEquals(1f, screenScale(active = true, front = false), 0f)
    }

    @Test fun menuFamilyRetainsOneSharedBackgroundDuringNestedNavigation() {
        lateinit var nav: NavHostController
        var activeBackgrounds = 0
        compose.setContent {
            nav = rememberNavController()
            RetainedNavHost(nav, startDestination = "chat", menuBackground = {
                DisposableEffect(Unit) {
                    activeBackgrounds++
                    onDispose { activeBackgrounds-- }
                }
                Box(Modifier.fillMaxSize().testTag("shared-menu-background"))
            }) {
                listOf("chat", "features", "providers").forEach { route ->
                    composable(route) { Box(Modifier.fillMaxSize().testTag(route)) }
                }
            }
        }
        compose.runOnIdle { nav.navigate("features") }
        compose.onNodeWithTag("features").assertIsDisplayed()
        compose.runOnIdle { nav.navigate("providers") }
        compose.onNodeWithTag("providers").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithTag("features").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, activeBackgrounds) }
    }
}
