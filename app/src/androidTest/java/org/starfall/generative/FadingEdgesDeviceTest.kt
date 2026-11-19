package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.ui.layout.fadingBarSurface
import org.starfall.generative.shared.ui.layout.fadingVerticalScroll

class FadingEdgesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun scrollFadeRevealsBackgroundOnlyAtClippedEdges() {
        lateinit var scroll: ScrollState
        lateinit var scope: CoroutineScope
        compose.setContent {
            scroll = rememberScrollState()
            scope = rememberCoroutineScope()
            Box(Modifier.size(120.dp).background(Color.Blue).testTag("fade-viewport")) {
                Column(Modifier.fillMaxSize().fadingVerticalScroll(scroll)) {
                    Box(Modifier.fillMaxWidth().height(360.dp).background(Color.Red))
                }
            }
        }

        fun assertEdges(topFades: Boolean, bottomFades: Boolean) {
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("fade-viewport").captureToImage().toPixelMap()
            val x = pixels.width / 2
            val top = pixels[x, 1]
            val bottom = pixels[x, pixels.height - 2]
            val middle = pixels[x, pixels.height / 2]
            assertTrue(middle.red > 0.95f)
            assertTrue(if (topFades) top.blue > 0.7f else top.red > 0.95f)
            assertTrue(if (bottomFades) bottom.blue > 0.7f else bottom.red > 0.95f)
        }

        assertEdges(topFades = false, bottomFades = true)
        compose.runOnIdle { scope.launch { scroll.scrollTo(scroll.maxValue / 2) } }
        assertEdges(topFades = true, bottomFades = true)
        compose.runOnIdle { scope.launch { scroll.scrollTo(scroll.maxValue) } }
        assertEdges(topFades = true, bottomFades = false)
    }

    @Test
    fun headerAndFooterFadeTheirSurfaceWithoutFadingControls() {
        compose.setContent {
            Column(Modifier.background(Color.Blue).testTag("bars")) {
                Box(Modifier.size(120.dp, 64.dp).fadingBarSurface(Color.Red)) {
                    Box(Modifier.size(10.dp).background(Color.Green))
                }
                Box(Modifier.size(120.dp, 64.dp).fadingBarSurface(Color.Red, footer = true))
            }
        }
        val pixels = compose.onNodeWithTag("bars").captureToImage().toPixelMap()
        val x = pixels.width / 2
        val middle = pixels.height / 2
        assertTrue(pixels[x, 1].red > 0.95f)
        assertTrue(pixels[x, middle - 2].blue > 0.7f)
        assertTrue(pixels[x, middle + 1].blue > 0.7f)
        assertTrue(pixels[x, pixels.height - 2].red > 0.95f)
        assertTrue(pixels[1, 1].green > 0.95f)
    }
}
