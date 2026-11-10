package org.starfall.generative.shared.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import org.starfall.generative.shared.model.preferences.CustomPalette

fun CustomPalette.color(role: Int): Color = parsePaletteHex(when (role) {
    0 -> primary
    1 -> secondary
    2 -> tertiary
    else -> neutral
}) ?: Color(0xFF73717A)

/** Hex is always an opaque #RRGGBB string; reject invalid persisted/imported values. */
fun parsePaletteHex(hex: String): Color? {
    val raw = hex.removePrefix("#")
    if (raw.length != 6 || !raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return Color((0xFF000000L or raw.toLong(16)).toInt())
}

fun Color.toPaletteHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

private fun tone(color: Color, lightness: Float, maxSaturation: Float = 0.85f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = hsl[1].coerceAtMost(maxSaturation)
    hsl[2] = lightness
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Move vivid seeds only as far as necessary to keep text contrast at least 4.5:1. */
private fun accessibleAccent(seed: Color, dark: Boolean): Color {
    val foreground = if (dark) tone(seed, 0.12f) else Color.White
    var lightness = if (dark) 0.80f else 0.35f
    var output = tone(seed, lightness)
    while (ColorUtils.calculateContrast(foreground.toArgb(), output.toArgb()) < 4.5 &&
        lightness in 0.05f..0.95f) {
        lightness += if (dark) 0.02f else -0.02f
        output = tone(seed, lightness)
    }
    return output
}

/** Material roles share the same selected hues, but use contrast-safe tones in each mode. */
fun customColorScheme(palette: CustomPalette, dark: Boolean): ColorScheme {
    val primary = palette.color(0)
    val secondary = palette.color(1)
    val tertiary = palette.color(2)
    val neutral = palette.color(3)
    val primaryAccent = accessibleAccent(primary, dark)
    val secondaryAccent = accessibleAccent(secondary, dark)
    val tertiaryAccent = accessibleAccent(tertiary, dark)
    return if (dark) darkColorScheme(
        primary = primaryAccent, onPrimary = tone(primary, 0.12f),
        primaryContainer = tone(primary, 0.28f), onPrimaryContainer = tone(primary, 0.94f),
        secondary = secondaryAccent, onSecondary = tone(secondary, 0.12f),
        secondaryContainer = tone(secondary, 0.28f), onSecondaryContainer = tone(secondary, 0.94f),
        tertiary = tertiaryAccent, onTertiary = tone(tertiary, 0.12f),
        tertiaryContainer = tone(tertiary, 0.28f), onTertiaryContainer = tone(tertiary, 0.94f),
        background = tone(neutral, 0.075f, 0.10f), onBackground = tone(neutral, 0.92f, 0.10f),
        surface = tone(neutral, 0.075f, 0.10f), onSurface = tone(neutral, 0.92f, 0.10f),
        surfaceVariant = tone(neutral, 0.26f, 0.14f), onSurfaceVariant = tone(neutral, 0.80f, 0.14f)
    ) else lightColorScheme(
        primary = primaryAccent, onPrimary = Color.White,
        primaryContainer = tone(primary, 0.90f), onPrimaryContainer = tone(primary, 0.13f),
        secondary = secondaryAccent, onSecondary = Color.White,
        secondaryContainer = tone(secondary, 0.90f), onSecondaryContainer = tone(secondary, 0.13f),
        tertiary = tertiaryAccent, onTertiary = Color.White,
        tertiaryContainer = tone(tertiary, 0.90f), onTertiaryContainer = tone(tertiary, 0.13f),
        background = tone(neutral, 0.975f, 0.10f), onBackground = tone(neutral, 0.12f, 0.10f),
        surface = tone(neutral, 0.975f, 0.10f), onSurface = tone(neutral, 0.12f, 0.10f),
        surfaceVariant = tone(neutral, 0.89f, 0.14f), onSurfaceVariant = tone(neutral, 0.29f, 0.14f)
    )
}
