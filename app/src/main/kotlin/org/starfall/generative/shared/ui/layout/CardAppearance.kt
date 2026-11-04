package org.starfall.generative.shared.ui.layout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.theme.LocalAmoledMode

/** Appearance-controlled item fill; optional boost preserves extra contrast for overlay controls. */
@Composable
fun appearanceContainerColor(base: Color, opacityBoost: Float = 0f): Color =
    if (LocalAmoledMode.current) Color.Transparent else base.copy(
        alpha = (1f - LocalItemTransparency.current.coerceIn(0f, 1f) + opacityBoost).coerceIn(0f, 1f)
    )

/** Shared Appearance policy for container outlines in AMOLED mode. */
@Composable
fun appearanceContainerBorder(width: Dp = 1.dp): BorderStroke? =
    if (LocalAmoledMode.current) BorderStroke(width, MaterialTheme.colorScheme.outlineVariant) else null

/** Opaque modal/drawer surface: images are placed behind content, never painted through the surface. */
@Composable
fun modalContainerColor(base: Color): Color =
    if (LocalAmoledMode.current) Color.Black else base.copy(alpha = 1f)

/**
 * Container fill for primary cards, tiles, and list items:
 * configurable translucent item fill in normal Light/Dark themes; no fill in AMOLED dark mode.
 */
@Composable
fun prominentContainerColor(opacityBoost: Float = 0f): Color =
    appearanceContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh, opacityBoost)

/**
 * Container border for primary cards, tiles, and list items:
 * no routine border in normal Light/Dark themes, visible outline border in AMOLED dark mode.
 */
@Composable
fun prominentContainerBorder(width: Dp = 1.dp): BorderStroke? = appearanceContainerBorder(width)

/**
 * Container fill for interactive or elevated sub-containers nested inside a [prominentContainerColor] card,
 * or standalone code/media blocks.
 */
@Composable
fun nestedContainerColor(): Color = appearanceContainerColor(MaterialTheme.colorScheme.surfaceContainerHighest)

/**
 * Container border for nested sub-containers and code/media blocks:
 * no routine border in normal Light/Dark themes, visible outline border in AMOLED dark mode.
 */
@Composable
fun nestedContainerBorder(width: Dp = 1.dp): BorderStroke? = appearanceContainerBorder(width)

/**
 * Shape for an item in a vertically stacked group: [outerCorner] on the group's exposed top/bottom
 * edges, and [innerCorner] where adjacent items meet.
 */
fun groupedItemShape(
    index: Int,
    count: Int,
    outerCorner: Dp = 24.dp,
    innerCorner: Dp = 6.dp
): RoundedCornerShape = when {
    count <= 1 -> RoundedCornerShape(outerCorner)
    index == 0 -> RoundedCornerShape(
        topStart = outerCorner,
        topEnd = outerCorner,
        bottomStart = innerCorner,
        bottomEnd = innerCorner
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = innerCorner,
        topEnd = innerCorner,
        bottomStart = outerCorner,
        bottomEnd = outerCorner
    )
    else -> RoundedCornerShape(innerCorner)
}

/** Appearance-neutral shape used by ComponentGroup: a full capsule on exposed edges. */
fun componentGroupItemShape(index: Int, count: Int): RoundedCornerShape {
    val outer = CornerSize(50)
    val inner = CornerSize(6.dp)
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(
            topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner
        )
        index == count - 1 -> RoundedCornerShape(
            topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer
        )
        else -> RoundedCornerShape(inner)
    }
}
