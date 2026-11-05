package org.starfall.generative.shared.ui.layout

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import org.starfall.generative.shared.ui.theme.LocalAmoledMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Use Scaffold's safe layout padding as scroll CONTENT padding, rather than clipping the
 * scrolling viewport to it. Items can travel behind the system bars and app bar, while
 * the first and last items still settle outside the gesture/tappable system areas.
 */
@Composable
fun scrollableScaffoldPadding(
    scaffoldPadding: PaddingValues,
    horizontal: Dp = 0.dp,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp
): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = scaffoldPadding.calculateStartPadding(direction) + horizontal,
        top = scaffoldPadding.calculateTopPadding() + top,
        end = scaffoldPadding.calculateEndPadding(direction) + horizontal,
        bottom = scaffoldPadding.calculateBottomPadding() + bottom
    )
}

/**
 * AppBars (other than the standalone ChatAppBar) follow Appearance > Component transparency.
 * The color is applied only to the bar surface, not its text, icons, or the screen below it.
 */
internal fun appBarSurfaceColor(surface: Color, transparency: Float, amoled: Boolean): Color =
    if (amoled) Color.Transparent else surface.itemFill(transparency)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun edgeToEdgeTopAppBarColors(): TopAppBarColors {
    val color = appBarSurfaceColor(
        MaterialTheme.colorScheme.surface,
        LocalItemTransparency.current,
        LocalAmoledMode.current
    )
    return TopAppBarDefaults.topAppBarColors(
        containerColor = color,
        scrolledContainerColor = color
    )
}
