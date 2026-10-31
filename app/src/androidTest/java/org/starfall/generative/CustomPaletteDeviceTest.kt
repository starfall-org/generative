package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Rule
import org.starfall.generative.R
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.customization.ui.CustomizationScreen
import org.starfall.generative.features.settings.ui.AppearanceSettingsView
import org.starfall.generative.shared.storage.preferences.AppPreferences
import org.starfall.generative.shared.storage.preferences.UserProfile

/** Verify that the palette mixer handles Android pointer input and commits only on Save. */
@RunWith(AndroidJUnit4::class)
class CustomPaletteDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun colorsAndInputAreaHaveAlignedLeadingIcons() {
        compose.setContent { MaterialTheme {
            CustomizationScreen(profile = UserProfile(), onChange = {}, onBack = {})
        } }
        val list = compose.onNodeWithTag("customization-list")
        list.performScrollToKey("colors")
        val colorIcon = compose.onNodeWithTag("customization-colors-setting-icon", useUnmergedTree = true)
            .assertExists().fetchSemanticsNode().boundsInRoot
        list.performScrollToKey("input-area")
        val inputIcon = compose.onNodeWithTag("customization-input-setting-icon", useUnmergedTree = true)
            .assertExists().fetchSemanticsNode().boundsInRoot
        assertEquals(colorIcon.left, inputIcon.left, 1f)
        assertEquals(colorIcon.width, inputIcon.width, 1f)
    }

    @Test fun opacityAndColorsAreOwnedByCustomizationNotAppearance() {
        var preferences by mutableStateOf(AppPreferences(useDynamicColor = false))
        compose.setContent { MaterialTheme {
            CustomizationScreen(
                profile = UserProfile(), onChange = {}, onBack = {}, appearance = preferences,
                onDynamicColorChange = { preferences = preferences.copy(useDynamicColor = it) },
                onColorSchemeChange = { preferences = preferences.copy(colorSchemeName = it) },
                onItemTransparencyChange = { preferences = preferences.copy(itemTransparency = it) }
            )
        } }
        compose.onNodeWithTag("customization-list").performScrollToKey("colors")
        compose.onNodeWithTag("customization-colors-setting").performClick()
        compose.onNodeWithTag("palette-preset-ORANGE").assertExists().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("ORANGE", preferences.colorSchemeName) }
        compose.onNodeWithTag("customization-opacity-slider").performScrollTo()
        compose.onNodeWithTag("customization-opacity-preview").assertDoesNotExist()
        compose.onNodeWithTag("customization-opacity-slider").assertIsDisplayed()
        compose.onNodeWithTag("customization-opacity-slider").performTouchInput {
            swipe(Offset(width * 0.15f, center.y), Offset(width * 0.85f, center.y), 350)
        }
        compose.runOnIdle { org.junit.Assert.assertTrue(preferences.itemTransparency > 0.75f) }
    }

    @Test fun customizationSwitchDisablesEditingWithoutChangingSavedSettings() {
        var enabled by mutableStateOf(true)
        var commits = 0
        compose.setContent { MaterialTheme {
            CustomizationScreen(
                profile = UserProfile(photo = "stored-photo-image", chatBackground = "stored-chat-image"),
                onChange = { commits++ }, onBack = {},
                customizationEnabled = enabled,
                onCustomizationEnabledChange = { enabled = it }
            )
        } }
        compose.onNodeWithTag("customization-enabled-switch").assertIsOn().performClick()
        compose.onNodeWithTag("customization-enabled-switch").assertIsOff()
        compose.onNodeWithTag("customization-disabled-overlay").assertExists()
        compose.onNodeWithTag("background-setting-chat").performClick()
        compose.onNodeWithTag("background-dialog-chat").assertDoesNotExist()
        compose.onNodeWithTag("customization-list").performScrollToKey("photo")
        compose.onNodeWithContentDescription(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.user_profile_photo_options)
        ).performClick()
        val removeLabel = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.common_remove)
        compose.onNodeWithText(removeLabel).assertExists().performClick()
        compose.runOnIdle {
            assertEquals(1, commits)
            assertTrue(!enabled)
        }
        compose.onNodeWithTag("customization-enabled-switch").performClick()
        compose.onNodeWithTag("customization-enabled-switch").assertIsOn()
        compose.onNodeWithTag("customization-disabled-overlay").assertDoesNotExist()
        compose.onNodeWithTag("customization-list").assertExists()
    }

    @Test fun paletteMixerOpensFromCustomizationAndDraggingChangesSavedAccent() {
        var preferences by mutableStateOf(AppPreferences(useDynamicColor = false))
        var saves = 0
        val before = preferences.customPalette.tertiary
        compose.setContent { MaterialTheme {
            CustomizationScreen(
                profile = UserProfile(), onChange = {}, onBack = {},
                appearance = preferences, onDynamicColorChange = {},
                onColorSchemeChange = { preferences = preferences.copy(colorSchemeName = it) },
                onCustomPaletteChange = { updated ->
                    saves++
                    preferences = preferences.copy(colorSchemeName = "CUSTOM", customPalette = updated)
                }
            )
        } }
        compose.onNodeWithTag("customization-list").performScrollToKey("colors")
        compose.onNodeWithTag("customization-colors-setting").performClick()
        val presets = listOf("ORANGE", "VIOLET", "SLATE", "CUSTOM").map {
            compose.onNodeWithTag("palette-preset-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        assertTrue("Four presets must occupy one horizontal row",
            presets.zipWithNext().all { (a, b) -> abs(a.top - b.top) < 2f && a.right <= b.left })
        assertTrue("Four presets must have equal widths",
            presets.maxOf { it.width } - presets.minOf { it.width } < 2f)
        compose.onNodeWithTag("palette-preset-CUSTOM").performClick()
        compose.onNodeWithTag("palette-role-group").assertIsDisplayed()
        val segments = (0..3).map {
            compose.onNodeWithTag("palette-role-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        assertTrue("Four color roles must share one horizontal row",
            segments.zipWithNext().all { (a, b) -> abs(a.top - b.top) < 2f })
        assertTrue("Color role segments must have equal widths",
            segments.maxOf { it.width } - segments.minOf { it.width } < 3f)
        // The live preview and every mixer control must fit without scrolling on a phone.
        for (tag in listOf("palette-preview", "palette-color-plane", "palette-hue",
            "palette-saturation", "palette-brightness", "palette-hex")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        compose.onNodeWithTag("palette-role-2").performClick()
        compose.onNodeWithTag("palette-color-plane").performTouchInput { click(center) }
        compose.onNodeWithTag("palette-save").performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals("CUSTOM", preferences.colorSchemeName)
            assertNotEquals(before, preferences.customPalette.tertiary)
        }
        compose.onNodeWithTag("palette-preset-CUSTOM").performClick()
        compose.onNodeWithTag("palette-role-1").performClick()
        compose.onNodeWithTag("palette-color-plane").performTouchInput { click(center) }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.common_close)).performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }
}
