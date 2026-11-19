package org.starfall.generative.shared.ui.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ContentFadeDepth = 16.dp
private val BarFadeDepth = 24.dp

/** Draw the bar surface, not its controls, with a transparent edge facing the body. */
fun Modifier.fadingBarSurface(
    color: Color,
    footer: Boolean = false,
    depth: Dp = BarFadeDepth
): Modifier = if (color.alpha == 0f) this else drawWithCache {
    val fraction = if (depth == Dp.Unspecified) 1f
        else (depth.toPx() / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val transparent = color.copy(alpha = 0f)
    val brush = if (fraction == 1f) {
        Brush.verticalGradient(if (footer) listOf(transparent, color) else listOf(color, transparent))
    } else if (footer) Brush.verticalGradient(
        0f to transparent, fraction to color, 1f to color
    ) else Brush.verticalGradient(
        0f to color, (1f - fraction) to color, 1f to transparent
    )
    onDrawBehind { drawRect(brush) }
}

/**
 * Reveal the actual background (including photos and modal decoration) at a clipped
 * scrolling edge. Keep the first/last item fully visible when settled at either end.
 * The isolated layer is only needed while at least one edge can scroll.
 */
fun Modifier.fadingScrollEdges(
    state: ScrollableState,
    reverseLayout: Boolean = false,
    depth: Dp = ContentFadeDepth
): Modifier = drawWithCache {
    val edge = depth.toPx().coerceIn(0f, size.height / 2f)
    val topMask = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color.Black), startY = 0f, endY = edge
    )
    val bottomMask = Brush.verticalGradient(
        colors = listOf(Color.Black, Color.Transparent), startY = size.height - edge, endY = size.height
    )
    val bounds = Rect(Offset.Zero, size)
    val paint = Paint()
    onDrawWithContent {
        val top = if (reverseLayout) state.canScrollForward else state.canScrollBackward
        val bottom = if (reverseLayout) state.canScrollBackward else state.canScrollForward
        if ((!top && !bottom) || edge <= 0f || size.width <= 0f) {
            drawContent()
        } else {
            val canvas = drawContext.canvas
            canvas.saveLayer(bounds, paint)
            try {
                drawContent()
                if (top) drawRect(topMask, blendMode = BlendMode.DstIn)
                if (bottom) drawRect(bottomMask, blendMode = BlendMode.DstIn)
            } finally {
                canvas.restore()
            }
        }
    }
}

fun Modifier.fadingVerticalScroll(state: ScrollState): Modifier =
    fadingScrollEdges(state).verticalScroll(state)
