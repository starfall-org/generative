package org.starfall.generative

import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.generative.database.preferences.UserProfile
import org.starfall.generative.database.preferences.UserProfileImageSlot
import org.starfall.generative.shared.ui.background.backgroundPhotoAlpha

/** A user-profile slider changes only the photo alpha, never item fills or modal surfaces. */
class UserProfileBackgroundTransparencyTest {
    @Test
    fun transparencyChangesOnlyPhotoAlphaWithoutChangingItemOpacity() {
        assertEquals(1f, backgroundPhotoAlpha(0f), 0.0001f) // 0% transparent: full photo
        assertEquals(0.5f, backgroundPhotoAlpha(0.5f), 0.0001f)
        assertEquals(0f, backgroundPhotoAlpha(1f), 0.0001f) // 100% transparent: solid parent visible
        assertEquals(1f, backgroundPhotoAlpha(-2f), 0.0001f)
        assertEquals(0f, backgroundPhotoAlpha(2f), 0.0001f)
    }

    @Test
    fun individualBackgroundPhotoTransparencyIsPersistedIndependently() {
        val initial = UserProfile()
        val changed = UserProfileImageSlot.MENU.withImageTransparency(initial, 0.9f)
        assertEquals(0.9f, UserProfileImageSlot.MENU.imageTransparency(changed), 0.0001f)
        for (slot in listOf(
            UserProfileImageSlot.CHAT, UserProfileImageSlot.DRAWER,
            UserProfileImageSlot.SHEET, UserProfileImageSlot.DIALOG
        )) {
            assertEquals(0.5f, slot.imageTransparency(changed), 0.0001f)
        }
        val restored = UserProfileImageSlot.MENU.withImageTransparency(changed, 0.5f)
        assertEquals(initial, restored)
    }
}
