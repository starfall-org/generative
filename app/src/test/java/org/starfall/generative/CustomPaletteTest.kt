package org.starfall.generative

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.database.preferences.UserProfile
import org.starfall.generative.shared.ui.theme.CustomPalette
import org.starfall.generative.shared.ui.theme.ThemePresets
import org.starfall.generative.shared.ui.theme.customColorScheme
import org.starfall.generative.shared.ui.theme.parsePaletteHex
import org.starfall.generative.shared.ui.theme.toPaletteHex

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class CustomPaletteTest {
    @Test fun onlyThreeFixedPresetsRemainAndHexRoundTrips() {
        assertEquals(listOf("ORANGE", "VIOLET", "SLATE"), ThemePresets.map { it.name })
        assertEquals("#00ABF3", parsePaletteHex("#00abf3")!!.toPaletteHex())
        assertEquals("#FFFFFF", Color.White.toPaletteHex())
        assertNull(parsePaletteHex("#GG0000"))
        assertNull(parsePaletteHex("#123"))
        assertNull(parsePaletteHex("#12345678"))
    }

    @Test fun customColorSchemeCreatesReadableLightAndDarkRoles() {
        val palette = CustomPalette(primary = "#00FF00", secondary = "#0000FF",
            tertiary = "#FF00FF", neutral = "#5D4C89")
        for (dark in listOf(false, true)) {
            val colors = customColorScheme(palette, dark)
            for ((background, foreground) in listOf(
                colors.primary to colors.onPrimary,
                colors.primaryContainer to colors.onPrimaryContainer,
                colors.secondary to colors.onSecondary,
                colors.tertiary to colors.onTertiary,
                colors.background to colors.onBackground,
                colors.surface to colors.onSurface
            )) {
                assertTrue("Unreadable custom role in dark=$dark",
                    ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb()) >= 4.5)
            }
            assertNotEquals(colors.primary, colors.secondary)
            assertNotEquals(colors.secondary, colors.tertiary)
        }
    }

    @Test fun disablingCustomizationIgnoresStoredAppearanceAndRestoresItWhenReenabled() = runBlocking {
        val repository = AppPreferencesRepository(ApplicationProvider.getApplicationContext())
        val palette = CustomPalette(primary = "#ED6622", secondary = "#228B7E",
            tertiary = "#D9A020", neutral = "#445566")
        val profile = UserProfile(photo = "profile-image", chatBackground = "chat-image",
            sheetBackground = "sheet-image", dialogBackground = "dialog-image")
        val customized = AppPreferences(
            themeMode = "DARK", useAmoled = true, useDynamicColor = false,
            colorSchemeName = "CUSTOM", customPalette = palette,
            itemTransparency = 0.8f, inputAreaTransparency = 0.6f,
            inputAreaBlur = 12f, userProfile = profile, featuresLogoImage = profile.photo,
            continueLastConversation = true
        )
        try {
            assertTrue(AppPreferences().customizationEnabled)
            repository.replace(customized)
            repository.setCustomizationEnabled(false)
            val inactive = AppPreferencesRepository(ApplicationProvider.getApplicationContext())
                .appPreferencesFlow.first()
            val defaults = AppPreferences()
            assertFalse(inactive.customizationEnabled)
            assertTrue(inactive.continueLastConversation) // Non-appearance settings stay available.
            assertEquals("DARK", inactive.themeMode)
            assertTrue(inactive.useAmoled)
            assertTrue(inactive.useDynamicColor)
            assertEquals(defaults.colorSchemeName, inactive.colorSchemeName)
            assertEquals(defaults.customPalette, inactive.customPalette)
            assertEquals(defaults.itemTransparency, inactive.itemTransparency)
            assertEquals(defaults.inputAreaTransparency, inactive.inputAreaTransparency)
            assertEquals(defaults.inputAreaBlur, inactive.inputAreaBlur)
            assertEquals(defaults.userProfile.copy(photo = profile.photo), inactive.userProfile)
            assertEquals(profile.photo, inactive.featuresLogoImage)

            // Backup must retain saved appearance while it is not used for rendering.
            val backup = repository.backupSnapshot()
            assertFalse(backup.customizationEnabled)
            assertEquals(profile, backup.userProfile)
            assertEquals(palette, backup.customPalette)
            assertEquals("DARK", backup.themeMode)
            assertEquals(profile.photo, repository.storedFeaturesLogoImage())
            repository.updateUserProfile { it.copy(photo = "new-profile-image") }
            val inactiveWithUpdatedPhoto = AppPreferencesRepository(ApplicationProvider.getApplicationContext())
                .appPreferencesFlow.first()
            assertEquals("new-profile-image", inactiveWithUpdatedPhoto.userProfile.photo)
            assertEquals(defaults.userProfile.copy(photo = "new-profile-image"), inactiveWithUpdatedPhoto.userProfile)
            assertEquals("new-profile-image", inactiveWithUpdatedPhoto.featuresLogoImage)
            assertEquals("new-profile-image", repository.storedFeaturesLogoImage())
            repository.setCustomizationEnabled(true)
            val restored = AppPreferencesRepository(ApplicationProvider.getApplicationContext())
                .appPreferencesFlow.first()
            assertTrue(restored.customizationEnabled)
            assertEquals(customized.themeMode, restored.themeMode)
            assertEquals(customized.useAmoled, restored.useAmoled)
            assertEquals(customized.useDynamicColor, restored.useDynamicColor)
            assertEquals(customized.customPalette, restored.customPalette)
            assertEquals(customized.itemTransparency, restored.itemTransparency)
            assertEquals(customized.inputAreaTransparency, restored.inputAreaTransparency)
            assertEquals(customized.inputAreaBlur, restored.inputAreaBlur)
            assertEquals(profile.copy(photo = "new-profile-image"), restored.userProfile)
        } finally {
            repository.replace(AppPreferences())
        }
    }

    @Test fun themeAndAmoledRemainIndependentAndEditableWhenCustomizationIsOff() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppPreferencesRepository(context)
        try {
            repository.replace(AppPreferences(themeMode = "DARK", useAmoled = true))
            repository.setCustomizationEnabled(false)
            val disabled = AppPreferencesRepository(context).appPreferencesFlow.first()
            assertFalse(disabled.customizationEnabled)
            assertEquals("DARK", disabled.themeMode)
            assertTrue(disabled.useAmoled)

            repository.setThemeMode("LIGHT")
            repository.setUseAmoled(false)
            val updated = AppPreferencesRepository(context).appPreferencesFlow.first()
            assertFalse(updated.customizationEnabled)
            assertEquals("LIGHT", updated.themeMode)
            assertFalse(updated.useAmoled)

            repository.setThemeMode("DARK")
            repository.setUseAmoled(true)
            val restored = AppPreferencesRepository(context).appPreferencesFlow.first()
            assertFalse(restored.customizationEnabled)
            assertEquals("DARK", restored.themeMode)
            assertTrue(restored.useAmoled)
        } finally {
            repository.replace(AppPreferences())
        }
    }

    @Test fun customPalettePersistsAndIsIncludedInRestoredPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppPreferencesRepository(context)
        val palette = CustomPalette(primary = "#ED6622", secondary = "#228B7E",
            tertiary = "#D9A020", neutral = "#445566")
        try {
            repository.setCustomPalette(palette)
            val restored = AppPreferencesRepository(context).appPreferencesFlow.first()
            assertEquals("CUSTOM", restored.colorSchemeName)
            assertFalse(restored.useDynamicColor)
            assertEquals(palette, restored.customPalette)
            val backup = Json.decodeFromString<AppPreferences>(Json.encodeToString(restored))
            assertEquals(palette, backup.customPalette)
            repository.replace(backup)
            assertEquals(palette, AppPreferencesRepository(context).appPreferencesFlow.first().customPalette)
        } finally {
            repository.replace(AppPreferences())
        }
    }
}
