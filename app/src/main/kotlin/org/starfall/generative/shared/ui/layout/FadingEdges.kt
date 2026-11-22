package org.starfall.generative.shared.ui.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
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
 * Only the clipped edges need offscreen buffers. The rest draws directly.
 * The inner graphics layer reuses the content's drawing commands across clips.
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
    val topBounds = Rect(0f, 0f, size.width, edge)
    val bottomBounds = Rect(0f, size.height - edge, size.width, size.height)
    val paint = Paint()
    onDrawWithContent {
        val top = if (reverseLayout) state.canScrollForward else state.canScrollBackward
        val bottom = if (reverseLayout) state.canScrollBackward else state.canScrollForward
        if ((!top && !bottom) || edge <= 0f || size.width <= 0f) {
            drawContent()
        } else {
            if (top) drawFadedEdge(topBounds, topMask, paint)
            if (bottom) drawFadedEdge(bottomBounds, bottomMask, paint)
            val centerTop = if (top) topBounds.bottom else 0f
            val centerBottom = if (bottom) bottomBounds.top else size.height
            clipRect(top = centerTop, bottom = centerBottom) {
                this@onDrawWithContent.drawContent()
            }
        }
    }
}.graphicsLayer()

private fun ContentDrawScope.drawFadedEdge(bounds: Rect, mask: Brush, paint: Paint) {
    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
        val canvas = drawContext.canvas
        canvas.saveLayer(bounds, paint)
        try {
            this@drawFadedEdge.drawContent()
            drawRect(mask, topLeft = bounds.topLeft, size = bounds.size, blendMode = BlendMode.DstIn)
        } finally {
            canvas.restore()
        }
    }
}

fun Modifier.fadingVerticalScroll(state: ScrollState): Modifier =
    fadingScrollEdges(state).verticalScroll(state)
