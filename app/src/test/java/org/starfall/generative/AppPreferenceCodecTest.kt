package org.starfall.generative

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.database.preferences.SelectedModel
import org.starfall.generative.database.preferences.UserProfile
import org.starfall.generative.database.preferences.decodePreferences
import org.starfall.generative.database.preferences.restorePreferences
import org.starfall.generative.shared.model.AgentSkills

class AppPreferenceCodecTest {
    @Test
    fun restoreKeepsNotificationHistoryAndClearsObsoleteSettings() {
        val notification = booleanPreferencesKey("background_requested")
        val obsolete = stringPreferencesKey("selected_provider_id")
        val stored = mutablePreferencesOf(notification to true, obsolete to "obsolete-provider")
        val snapshot = AppPreferences(
            selectedModel = SelectedModel("provider", "model"),
            autoScroll = true,
            selectedLanguage = "vi",
            useSystemLanguage = false,
            toolRoundLimitEnabled = true,
            toolRoundLimit = 7
        )

        restorePreferences(stored, snapshot)

        assertTrue(stored[notification] == true)
        assertFalse(stored.contains(obsolete))
        assertEquals(snapshot, decodePreferences(stored, snapshot.agentSkills))
    }

    @Test
    fun exportRetainsAppearanceWhenRenderingCustomizationIsDisabled() {
        val snapshot = AppPreferences(
            customizationEnabled = false,
            itemTransparency = 0.85f,
            inputAreaTransparency = 0.6f,
            inputAreaBlur = 12f,
            featuresLogoImage = "profile-photo",
            userProfile = UserProfile(photo = "profile-photo")
        )
        val stored = mutablePreferencesOf()
        restorePreferences(stored, snapshot)

        val rendered = decodePreferences(stored, AgentSkills())
        assertEquals(AppPreferences().itemTransparency, rendered.itemTransparency)
        assertEquals("profile-photo", rendered.userProfile.photo)
        assertEquals(snapshot, decodePreferences(stored, snapshot.agentSkills, includeCustomization = true))
    }
}
