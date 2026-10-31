package org.starfall.generative

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.starfall.generative.features.menu.ui.AppMenuScreen
import org.starfall.generative.features.menu.ui.GroupBottomShape
import org.starfall.generative.features.menu.ui.GroupInnerCorner
import org.starfall.generative.features.menu.ui.GroupMiddleShape
import org.starfall.generative.features.menu.ui.GroupSingleShape
import org.starfall.generative.features.menu.ui.GroupTopShape
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.theme.GenerativeTheme
import org.starfall.generative.shared.theme.ThemePresets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppMenuScreenGroupUiTest {
    @get:Rule val compose = createComposeRule()

    private fun maxChannelDelta(a: Int, b: Int): Int = max(
        max(abs(AndroidColor.red(a) - AndroidColor.red(b)), abs(AndroidColor.green(a) - AndroidColor.green(b))),
        abs(AndroidColor.blue(a) - AndroidColor.blue(b))
    )

    private fun tapAt(offset: Offset) {
        compose.onRoot().performTouchInput { click(offset) }
        compose.waitForIdle()
    }

    @Test
    fun groupShapesUseLargeOuterCornersAndSlightInnerAdjacentCorners() {
        val density = Density(2f)
        val size = Size(600f, 160f)
        val outerPx = minOf(size.width, size.height) / 2f
        val innerPx = with(density) { GroupInnerCorner.toPx() }
        assertTrue(innerPx > 0f && innerPx < outerPx)

        val top = (GroupTopShape.createOutline(size, LayoutDirection.Ltr, density) as Outline.Rounded).roundRect
        assertEquals(outerPx, top.topLeftCornerRadius.x, 0.01f)
        assertEquals(outerPx, top.topRightCornerRadius.x, 0.01f)
        assertEquals(innerPx, top.bottomLeftCornerRadius.x, 0.01f)
        assertEquals(innerPx, top.bottomRightCornerRadius.x, 0.01f)

        val middle = (GroupMiddleShape.createOutline(size, LayoutDirection.Ltr, density) as Outline.Rounded).roundRect
        assertEquals(innerPx, middle.topLeftCornerRadius.x, 0.01f)
        assertEquals(innerPx, middle.topRightCornerRadius.x, 0.01f)
        assertEquals(innerPx, middle.bottomLeftCornerRadius.x, 0.01f)
        assertEquals(innerPx, middle.bottomRightCornerRadius.x, 0.01f)

        val single = (GroupSingleShape.createOutline(size, LayoutDirection.Ltr, density) as Outline.Rounded).roundRect
        assertEquals(outerPx, single.topLeftCornerRadius.x, 0.01f)
        assertEquals(outerPx, single.topRightCornerRadius.x, 0.01f)
        assertEquals(outerPx, single.bottomLeftCornerRadius.x, 0.01f)
        assertEquals(outerPx, single.bottomRightCornerRadius.x, 0.01f)
        val taller = Size(600f, 240f)
        val tallerSingle = (GroupSingleShape.createOutline(taller, LayoutDirection.Ltr, density) as Outline.Rounded).roundRect
        assertEquals(120f, tallerSingle.topLeftCornerRadius.x, 0.01f)

        val bottom = (GroupBottomShape.createOutline(size, LayoutDirection.Ltr, density) as Outline.Rounded).roundRect
        assertEquals(innerPx, bottom.topLeftCornerRadius.x, 0.01f)
        assertEquals(innerPx, bottom.topRightCornerRadius.x, 0.01f)
        assertEquals(outerPx, bottom.bottomLeftCornerRadius.x, 0.01f)
        assertEquals(outerPx, bottom.bottomRightCornerRadius.x, 0.01f)
    }

    @Test
    fun groupedRowsRespectRoundedCornersAndIndependentGroupBoundaries() {
        val clicked = mutableListOf<String>()
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                AppMenuScreen(
                    onNavigateToChatOptions = { clicked += "Configuration" },
                    onNavigateToProviders = { clicked += "Providers" },
                    onNavigateToSpeech = { clicked += "Speech" },
                    onNavigateToToolsEditor = { clicked += "Tools" },
                    onNavigateToMcp = { clicked += "MCP" },
                    onNavigateToRemoteMachines = { clicked += "Remote" }
                )
            }
        }
        compose.waitForIdle()

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Configuration"))
        compose.waitForIdle()
        val first = compose.onNodeWithTag("app-menu-chat-options").fetchSemanticsNode().boundsInRoot
        val middle = compose.onNodeWithTag("app-menu-providers").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("app-menu-speech").fetchSemanticsNode().boundsInRoot
        tapAt(Offset(first.left + 4f, first.top + 4f))
        tapAt(Offset(first.right - 4f, first.top + 4f))
        tapAt(Offset(middle.left + 1f, middle.top + 1f))
        tapAt(Offset(last.left + 4f, last.bottom - 4f))
        tapAt(Offset(last.right - 4f, last.bottom - 4f))
        assertEquals(emptyList<String>(), clicked)
        tapAt(Offset(first.left + 40f, first.top + 40f))
        tapAt(Offset(middle.left + 24f, middle.top + 24f))
        tapAt(Offset(last.left + 40f, last.bottom - 40f))

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Built-in tools"))
        compose.waitForIdle()
        val tools = compose.onNodeWithTag("app-menu-feature-tools").fetchSemanticsNode().boundsInRoot
        val mcp = compose.onNodeWithTag("app-menu-mcp").fetchSemanticsNode().boundsInRoot
        tapAt(Offset(tools.left + 4f, tools.top + 4f))
        tapAt(Offset(mcp.left + 4f, mcp.bottom - 4f))
        tapAt(Offset(tools.left + 40f, tools.top + 40f))
        tapAt(Offset(mcp.left + 40f, mcp.bottom - 40f))

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Remote services"))
        compose.waitForIdle()
        val remote = compose.onNodeWithTag("app-menu-remote").fetchSemanticsNode().boundsInRoot
        tapAt(Offset(remote.left + 4f, remote.top + 4f))
        tapAt(Offset(remote.right - 4f, remote.bottom - 4f))
        tapAt(Offset(remote.left + 40f, remote.top + 40f))
        assertEquals(listOf("Configuration", "Providers", "Speech", "Tools", "MCP", "Remote"), clicked)
    }

    @Test
    fun screenStaysOpaqueWhileItemsAreTranslucentOrOutlinedInAmoled() {
        var themeMode by mutableStateOf("LIGHT")
        var amoledMode by mutableStateOf(false)
        var presetName by mutableStateOf("DEFAULT")

        var screenBackground = Color.Unspecified
        var buttonColor = Color.Unspecified
        var buttonBorder: BorderStroke? = null
        var expectedOutlineVariant = Color.Unspecified

        compose.setContent {
            GenerativeTheme(
                themeMode = themeMode,
                amoledMode = amoledMode,
                dynamicColor = false,
                colorSchemeName = presetName
            ) {
                screenBackground = MaterialTheme.colorScheme.background
                buttonColor = prominentContainerColor()
                buttonBorder = prominentContainerBorder(1.5.dp)
                expectedOutlineVariant = MaterialTheme.colorScheme.outlineVariant
                AppMenuScreen()
            }
        }
        compose.waitForIdle()

        for (preset in ThemePresets) {
            presetName = preset.name

            // Light mode (even if amoled preference is toggled on, Light mode is not AMOLED)
            for (amoledPref in listOf(false, true)) {
                themeMode = "LIGHT"
                amoledMode = amoledPref
                compose.waitForIdle()
                assertNull("Light mode (${preset.name}) should not use border", buttonBorder)
                assertEquals("Screen must stay opaque", 1f, screenBackground.alpha, 0.001f)
                assertEquals("Light mode item fill must be 50% opaque", 0.5f, buttonColor.alpha, 1f / 255f)
            }

            // Dark mode (non-AMOLED)
            themeMode = "DARK"
            amoledMode = false
            compose.waitForIdle()
            assertNull("Dark mode (${preset.name}) should not use border", buttonBorder)
            assertEquals("Screen must stay opaque", 1f, screenBackground.alpha, 0.001f)
            assertEquals("Dark mode item fill must be 50% opaque", 0.5f, buttonColor.alpha, 1f / 255f)
            assertTrue(
                "Dark mode (${preset.name}) background should not be AMOLED black",
                maxChannelDelta(AndroidColor.BLACK, screenBackground.toArgb()) >= 12
            )

            // AMOLED mode
            themeMode = "DARK"
            amoledMode = true
            compose.waitForIdle()
            assertEquals(Color.Black, screenBackground)
            assertEquals("AMOLED item is transparent; only its outline remains", Color.Transparent, buttonColor)
            val border = buttonBorder
            assertNotNull("AMOLED mode (${preset.name}) must use a clear border", border)
            assertEquals(1.5.dp, border!!.width)
            assertEquals(SolidColor(expectedOutlineVariant), border.brush)
            assertTrue(
                "AMOLED border (${preset.name}) must be clearly visible on black",
                maxChannelDelta(AndroidColor.BLACK, expectedOutlineVariant.toArgb()) >= 50
            )
        }
    }
}
