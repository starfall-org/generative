package org.starfall.generative

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
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
import org.starfall.generative.shared.ui.screens.screenTransitionSpec
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
        // Forward: camera 0 -> 1. Back: camera 1 -> 0.
        assertEquals(1f, screenSlideOffset(depth = 1f, camera = 0f), 0f)
        assertEquals(-0.12f, screenSlideOffset(depth = 0f, camera = 1f), 0f)
        assertEquals(0f, screenSlideOffset(depth = 1f, camera = 1f), 0f)
        assertEquals(0f, screenSlideOffset(depth = 0f, camera = 0f), 0f)
        assertEquals(1f, screenScale(depth = 1f, camera = 0f), 0f)
        assertEquals(0.94f, screenScale(depth = 0f, camera = 1f), 0f)
        assertEquals(1f, screenScale(depth = 0f, camera = 0f), 0f)
    }

    @Test fun poppedPageAndRevealedPageMoveSmoothlyWithoutOvershoot() {
        // Sample the *actual* tween used by the host, including its final frame.
        // The entering page must not grow past 100% and then snap back.
        val animation = TargetBasedAnimation(
            animationSpec = screenTransitionSpec,
            typeConverter = Float.VectorConverter,
            initialValue = 2f,
            targetValue = 1f
        )
        var previousCamera = 2f
        var previousOffset = -0.12f
        var previousScale = 0.94f
        for (frame in 0..32) {
            val camera = animation.getValueFromNanos(animation.durationNanos * frame / 32)
            val offset = screenSlideOffset(depth = 1f, camera = camera)
            val scale = screenScale(depth = 1f, camera = camera)
            assertTrue("Camera must move monotonically toward the popped-to page", camera <= previousCamera + 0.00001f)
            assertTrue("Revealed page cannot jump backwards", offset >= previousOffset - 0.00001f)
            assertTrue("Revealed page cannot shrink while returning", scale >= previousScale - 0.00001f)
            assertTrue("Revealed page cannot exceed full size", scale in 0.94f..1f)
            assertTrue("Outgoing page cannot overshoot the edge", screenSlideOffset(depth = 2f, camera = camera) in 0f..1f)
            previousCamera = camera
            previousOffset = offset
            previousScale = scale
        }
        assertEquals(0f, previousOffset, 0.00001f)
        assertEquals(1f, previousScale, 0.00001f)
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
