package org.starfall.generative.shared.model.preferences

import kotlinx.serialization.Serializable

/** Persisted together as one user-owned appearance profile. Image references are app-private. */
@Serializable
data class UserProfile(
    val photo: String? = null,
    val chatBackground: String? = null,
    val drawerBackground: String? = null,
    val menuBackground: String? = null,
    val sheetBackground: String? = null,
    val dialogBackground: String? = null,
    // Persisted BACKGROUND PHOTO transparency: 0 = fully visible, 1 = hidden.
    // Field names are retained to keep previously saved UserProfile JSON compatible.
    val chatTransparency: Float = 0.5f,
    val drawerTransparency: Float = 0.5f,
    val menuTransparency: Float = 0.5f,
    val sheetTransparency: Float = 0.5f,
    val dialogTransparency: Float = 0.5f
)

enum class UserProfileImageSlot {
    PHOTO, CHAT, DRAWER, MENU, SHEET, DIALOG;

    fun get(profile: UserProfile): String? = when (this) {
        PHOTO -> profile.photo
        CHAT -> profile.chatBackground
        DRAWER -> profile.drawerBackground
        MENU -> profile.menuBackground
        SHEET -> profile.sheetBackground
        DIALOG -> profile.dialogBackground
    }

    fun imageTransparency(profile: UserProfile): Float = when (this) {
        PHOTO -> 0.5f
        CHAT -> profile.chatTransparency
        DRAWER -> profile.drawerTransparency
        MENU -> profile.menuTransparency
        SHEET -> profile.sheetTransparency
        DIALOG -> profile.dialogTransparency
    }

    fun withImageTransparency(profile: UserProfile, value: Float): UserProfile = when (this) {
        PHOTO -> profile
        CHAT -> profile.copy(chatTransparency = value.coerceIn(0f, 1f))
        DRAWER -> profile.copy(drawerTransparency = value.coerceIn(0f, 1f))
        MENU -> profile.copy(menuTransparency = value.coerceIn(0f, 1f))
        SHEET -> profile.copy(sheetTransparency = value.coerceIn(0f, 1f))
        DIALOG -> profile.copy(dialogTransparency = value.coerceIn(0f, 1f))
    }

    fun put(profile: UserProfile, path: String?): UserProfile = when (this) {
        PHOTO -> profile.copy(photo = path)
        CHAT -> profile.copy(chatBackground = path)
        DRAWER -> profile.copy(drawerBackground = path)
        MENU -> profile.copy(menuBackground = path)
        SHEET -> profile.copy(sheetBackground = path)
        DIALOG -> profile.copy(dialogBackground = path)
    }
}
