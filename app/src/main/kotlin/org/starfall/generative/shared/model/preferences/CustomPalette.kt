package org.starfall.generative.shared.model.preferences

import kotlinx.serialization.Serializable

/** User-selected color seeds. Roles are toned for readable light/dark Material palettes. */
@Serializable
data class CustomPalette(
    val primary: String = "#6B4FA0",
    val secondary: String = "#537A80",
    val tertiary: String = "#B26C60",
    val neutral: String = "#73717A"
) {
    fun withColor(role: Int, hex: String): CustomPalette = when (role) {
        0 -> copy(primary = hex)
        1 -> copy(secondary = hex)
        2 -> copy(tertiary = hex)
        else -> copy(neutral = hex)
    }
}
