package org.starfall.generative.shared.ui.layout

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max

/** The side of a screen-edge-anchored surface that faces the content and keeps its border. */
internal enum class AmoledBorderEdge { Top, End }

/**
 * Draws [shape]'s outline but keeps only the part facing the content: the straight [edge] plus
 * its rounded corners. Everything that sits flush against the screen edge stays hidden.
 */
private fun DrawScope.drawAmoledEdgeBorder(shape: Shape, color: Color, strokePx: Float, edge: AmoledBorderEdge) {
    val inner = Size(size.width - strokePx, size.height - strokePx)
    if (inner.width <= 0f || inner.height <= 0f) return
    val outline = shape.createOutline(inner, layoutDirection, this)
    val rounded = (outline as? Outline.Rounded)?.roundRect
    val rtl = layoutDirection == LayoutDirection.Rtl
    val depth = strokePx + when (edge) {
        AmoledBorderEdge.Top -> rounded?.let { max(it.topLeftCornerRadius.y, it.topRightCornerRadius.y) } ?: 0f
        AmoledBorderEdge.End -> rounded?.let {
            if (rtl) max(it.topLeftCornerRadius.x, it.bottomLeftCornerRadius.x)
            else max(it.topRightCornerRadius.x, it.bottomRightCornerRadius.x)
        } ?: 0f
    }
    val visible = when (edge) {
        AmoledBorderEdge.Top -> Rect(0f, 0f, size.width, depth)
        AmoledBorderEdge.End -> if (rtl) Rect(0f, 0f, depth, size.height) else Rect(size.width - depth, 0f, size.width, size.height)
    }
    clipRect(visible.left, visible.top, visible.right, visible.bottom) {
        translate(strokePx / 2f, strokePx / 2f) {
            drawOutline(outline, color, style = Stroke(strokePx))
        }
    }
}

/** Border for an already-positioned edge surface (scaffold sheet or side drawer).
 * Keeps only its content-facing edge and rounded corners, not the screen-edge sides.
 */
internal fun Modifier.amoledEdgeBorder(
    shape: Shape,
    color: Color,
    width: Dp,
    edge: AmoledBorderEdge
): Modifier = drawWithContent {
    drawContent()
    drawAmoledEdgeBorder(shape, color, width.toPx(), edge)
}
