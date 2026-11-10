package org.starfall.generative.shared.ui.theme

import org.starfall.generative.shared.model.preferences.CustomPalette
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext

// Orange palette
private val OrangeLight = lightColorScheme(
    primary = Color(0xFFA04000),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF380D00),
    secondary = Color(0xFF77574C),
    onSecondary = Color.White,
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231916),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231916)
)

private val OrangeDark = darkColorScheme(
    primary = Color(0xFFFFB59D),
    onPrimary = Color(0xFF5B1B00),
    primaryContainer = Color(0xFF7D2C00),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BEAF),
    onSecondary = Color(0xFF442A21),
    surface = Color(0xFF1A110E),
    onSurface = Color(0xFFF1DFD9),
    background = Color(0xFF1A110E),
    onBackground = Color(0xFFF1DFD9)
)

// Violet palette
private val VioletLight = lightColorScheme(
    primary = Color(0xFF6B4FA0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF25025A),
    secondary = Color(0xFF635B70),
    onSecondary = Color.White,
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20)
)

private val VioletDark = darkColorScheme(
    primary = Color(0xFFD4BBFF),
    onPrimary = Color(0xFF3C1D70),
    primaryContainer = Color(0xFF533687),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFCDC2DB),
    onSecondary = Color(0xFF342D40),
    surface = Color(0xFF151218),
    onSurface = Color(0xFFE7E0E8),
    background = Color(0xFF151218),
    onBackground = Color(0xFFE7E0E8)
)

// Slate palette
private val SlateLight = lightColorScheme(
    primary = Color(0xFF202124), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E3E3), onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFF5F6368), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EAED), onSecondaryContainer = Color(0xFF202124),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE5E5E5), onSurfaceVariant = Color(0xFF464646),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF1F1F1F)
)

private val SlateDark = darkColorScheme(
    primary = Color(0xFFE8EAED), onPrimary = Color(0xFF202124),
    primaryContainer = Color(0xFF3C4043), onPrimaryContainer = Color(0xFFF1F3F4),
    secondary = Color(0xFFBDC1C6), onSecondary = Color(0xFF292A2D),
    secondaryContainer = Color(0xFF444746), onSecondaryContainer = Color(0xFFE8EAED),
    surface = Color(0xFF121212), onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF353535), onSurfaceVariant = Color(0xFFC7C7C7),
    background = Color(0xFF121212), onBackground = Color(0xFFE8EAED)
)

data class ThemePreset(val name: String, val preview: Color)

val ThemePresets = listOf(
    ThemePreset("ORANGE", Color(0xFFA04000)),
    ThemePreset("VIOLET", Color(0xFF6B4FA0)),
    ThemePreset("SLATE", Color(0xFF444746))
)

private fun completeScheme(source: ColorScheme, dark: Boolean, custom: Boolean = false): ColorScheme = source.copy(
    tertiary = if (custom) source.tertiary else source.secondary,
    onTertiary = if (custom) source.onTertiary else source.onSecondary,
    tertiaryContainer = if (custom) source.tertiaryContainer else source.secondaryContainer,
    onTertiaryContainer = if (custom) source.onTertiaryContainer else source.onSecondaryContainer,
    error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
    onError = if (dark) Color(0xFF690005) else Color.White,
    errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6),
    onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF410002),
    outline = if (dark) Color(0xFF8E918F) else Color(0xFF747775),
    outlineVariant = if (dark) Color(0xFF444746) else Color(0xFFC4C7C5),
    scrim = Color.Black,
    inverseSurface = source.onSurface,
    inverseOnSurface = source.surface,
    inversePrimary = source.primaryContainer,
    surfaceDim = if (dark) source.surface else source.surfaceVariant,
    surfaceBright = if (dark) source.surfaceVariant else source.surface,
    surfaceContainerLowest = if (dark) Color(0xFF090909) else Color.White,
    surfaceContainerLow = if (dark) source.surface.copy(alpha = 1f) else source.surface,
    surfaceContainer = source.surfaceVariant.copy(alpha = if (dark) 0.45f else 0.55f).compositeOver(source.surface.copy(alpha = 1f)),
    surfaceContainerHigh = source.surfaceVariant.copy(alpha = if (dark) 0.7f else 0.8f).compositeOver(source.surface.copy(alpha = 1f)),
    surfaceContainerHighest = source.surfaceVariant
)

private fun ColorScheme.withAmoledSurfaces(): ColorScheme = copy(
    background = Color.Black, surface = Color.Black,
    surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black, surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black, surfaceContainerHighest = Color.Black,
    surfaceBright = Color.Black, surfaceVariant = Color.Black
)

internal val LocalAmoledMode = staticCompositionLocalOf { false }
internal val LocalDarkMode = staticCompositionLocalOf { false }
internal val AmoledModalScrim = Color(0xFF606060)

/**
 * Warning amber for states that deserve caution without the failure meaning of `colorScheme.error`,
 * such as a provider that answered a connection probe with an empty response. No Material role carries
 * that meaning, so the pair is kept here with the other semantic colors and tuned per light and dark surfaces.
 */
@Composable
fun warningColor(): Color = if (LocalDarkMode.current) Color(0xFFFFD54F) else Color(0xFFA05F00)

@Composable
internal fun modalScrimColor(default: Color): Color =
    when {
        // AMOLED must keep empty background areas at true black. The normal black
        // scrim dims content already drawn behind the modal without lifting black.
        LocalAmoledMode.current -> default
        LocalDarkMode.current -> AmoledModalScrim.copy(alpha = default.alpha)
        else -> default
    }

@Composable
fun GenerativeTheme(
    themeMode: String = "SYSTEM",
    /** Compatibility override for tests and older callers; settings use [themeMode]. */
    darkTheme: Boolean? = null,
    amoledMode: Boolean = false,
    dynamicColor: Boolean = true,
    colorSchemeName: String = "SLATE",
    customPalette: CustomPalette = CustomPalette(),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = darkTheme ?: when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> systemDark
    }
    val isAmoled = amoledMode && isDark

    val selectedScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        colorSchemeName == "CUSTOM" -> customColorScheme(customPalette, isDark)
        colorSchemeName == "ORANGE" || colorSchemeName == "SUNSET" -> if (isDark) OrangeDark else OrangeLight
        colorSchemeName == "VIOLET" -> if (isDark) VioletDark else VioletLight
        else -> if (isDark) SlateDark else SlateLight
    }
    // Keep screen and modal surface tokens opaque. Only individual items apply
    // LocalItemTransparency when drawing their own backgrounds.
    val colorScheme = completeScheme(selectedScheme, isDark, custom = colorSchemeName == "CUSTOM" &&
        !(dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)).let {
        if (isAmoled) it.withAmoledSurfaces() else it
    }

    CompositionLocalProvider(
        LocalAmoledMode provides isAmoled,
        LocalDarkMode provides isDark
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            shapes = Shapes(extraSmall = RoundedCornerShape(16.dp)),
            content = content
        )
    }
}
