package org.starfall.generative.shared.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** A dashed speech bubble distinguishing temporary chat from the regular compose action. */
@Composable
fun TemporaryChatIcon(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Canvas(
        modifier = modifier.semantics {
            contentDescription?.let { this.contentDescription = it }
        }
    ) {
        val scale = size.minDimension / 24f
        // A single continuous outline keeps the dashes flowing through the speech tail.
        val bubble = Path().apply {
            moveTo(7.25f * scale, 4.5f * scale)
            lineTo(16.75f * scale, 4.5f * scale)
            cubicTo(19.4f * scale, 4.5f * scale, 21f * scale, 6.2f * scale, 21f * scale, 8.65f * scale)
            lineTo(21f * scale, 14.1f * scale)
            cubicTo(21f * scale, 16.7f * scale, 19.25f * scale, 18.5f * scale, 16.75f * scale, 18.5f * scale)
            lineTo(9f * scale, 18.5f * scale)
            lineTo(4.25f * scale, 21f * scale)
            lineTo(5.45f * scale, 17.45f * scale)
            cubicTo(3.95f * scale, 16.55f * scale, 3f * scale, 15.2f * scale, 3f * scale, 13.45f * scale)
            lineTo(3f * scale, 8.65f * scale)
            cubicTo(3f * scale, 6.2f * scale, 4.6f * scale, 4.5f * scale, 7.25f * scale, 4.5f * scale)
            close()
        }
        drawPath(
            path = bubble,
            color = tint,
            style = Stroke(
                width = 1.55f * scale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.6f * scale, 2.8f * scale)),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
