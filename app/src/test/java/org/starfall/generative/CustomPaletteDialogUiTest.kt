package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.customization.ui.CustomizationScreen
import org.starfall.generative.shared.model.preferences.AppPreferences
import org.starfall.generative.shared.model.preferences.UserProfile
import org.starfall.generative.shared.model.preferences.CustomPalette
import org.starfall.generative.shared.ui.theme.GenerativeTheme
import org.starfall.generative.shared.ui.theme.customColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w420dp-h920dp-xhdpi", application = LibraryTestApplication::class)
class CustomPaletteDialogUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun installedCustomThemeUsesAccentAndAmoledKeepsBlackSurfaces() {
        val palette = CustomPalette(primary = "#225577", secondary = "#995522",
            tertiary = "#8844CC", neutral = "#554466")
        var dark by mutableStateOf(false)
        var amoled by mutableStateOf(false)
        var background = Color.Unspecified
        var accent = Color.Unspecified
        compose.setContent {
            GenerativeTheme(darkTheme = dark, amoledMode = amoled,
                dynamicColor = false, colorSchemeName = "CUSTOM", customPalette = palette) {
                background = MaterialTheme.colorScheme.background
                accent = MaterialTheme.colorScheme.tertiary
            }
        }
        compose.runOnIdle {
            assertEquals(customColorScheme(palette, false).tertiary, accent)
        }
        compose.runOnIdle { dark = true; amoled = true }
        compose.runOnIdle {
            assertEquals(Color.Black, background)
            assertEquals(customColorScheme(palette, true).tertiary, accent)
        }
    }

    @Test fun customizeOpensMixerAndOnlySaveCommitsChanges() {
        var preferences by mutableStateOf(AppPreferences(useDynamicColor = false, colorSchemeName = "SLATE"))
        var commits = 0
        compose.setContent { MaterialTheme {
            CustomizationScreen(
                profile = UserProfile(), onChange = {}, onBack = {},
                appearance = preferences, onDynamicColorChange = {},
                onColorSchemeChange = { preferences = preferences.copy(colorSchemeName = it) },
                onCustomPaletteChange = {
                    commits++
                    preferences = preferences.copy(customPalette = it, colorSchemeName = "CUSTOM")
                }
            )
        } }
        compose.onNodeWithTag("customization-list").performScrollToKey("colors")
        compose.onNodeWithTag("customization-colors-setting").performClick()
        compose.onNodeWithTag("palette-preset-ORANGE").assertExists()
        compose.onNodeWithTag("palette-preset-VIOLET").assertExists()
        compose.onNodeWithTag("palette-preset-SLATE").assertExists()
        compose.onNodeWithTag("palette-preset-CUSTOM").performClick()
        compose.onNodeWithTag("palette-color-plane").assertIsDisplayed()
        compose.onNodeWithTag("palette-role-2").performClick()
        compose.onNodeWithTag("palette-hex").performTextClearance()
        compose.onNodeWithTag("palette-hex").performTextInput("#FF4499")
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle {
            assertEquals(0, commits)
            assertEquals("SLATE", preferences.colorSchemeName)
        }
        compose.onNodeWithTag("palette-preset-CUSTOM").performClick()
        compose.onNodeWithTag("palette-role-2").performClick()
        compose.onNodeWithTag("palette-hex").performTextClearance()
        compose.onNodeWithTag("palette-hex").performTextInput("#FF4499")
        compose.onNodeWithTag("palette-save").performClick()
        compose.runOnIdle {
            assertEquals(1, commits)
            assertEquals("CUSTOM", preferences.colorSchemeName)
            assertEquals("#FF4499", preferences.customPalette.tertiary)
        }
    }

    @Test fun invalidHexBlocksSaveUntilValidAndPreviewSwitchesModes() {
        var saved: CustomPalette? = null
        compose.setContent { MaterialTheme {
            CustomizationScreen(
                profile = UserProfile(), onChange = {}, onBack = {},
                appearance = AppPreferences(useDynamicColor = false),
                onDynamicColorChange = {},
                onColorSchemeChange = {}, onCustomPaletteChange = { saved = it }
            )
        } }
        compose.onNodeWithTag("customization-list").performScrollToKey("colors")
        compose.onNodeWithTag("customization-colors-setting").performClick()
        compose.onNodeWithTag("palette-preset-CUSTOM").performClick()
        compose.onNodeWithTag("palette-hex").performTextClearance()
        compose.onNodeWithTag("palette-hex").performTextInput("XYZ")
        compose.onNodeWithTag("palette-save").assertIsNotEnabled()
        compose.onNodeWithTag("palette-hex").performTextClearance()
        compose.onNodeWithTag("palette-hex").performTextInput("#2266AA")
        compose.onNodeWithTag("palette-save").assertIsEnabled()
        compose.onNodeWithTag("palette-preview-dark").performClick()
        compose.onNodeWithTag("palette-preview").assertExists()
        compose.onNodeWithTag("palette-save").performClick()
        compose.runOnIdle { assertEquals("#2266AA", saved?.primary) }
    }
}
