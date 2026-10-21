package org.starfall.generative.home.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.ui.icons.EntityIcon
import kotlin.math.PI
import kotlin.math.sin

private const val MIN_DEGREES_PER_SECOND = 60f
private const val MAX_DEGREES_PER_SECOND = 720f
private const val SPEED_CYCLE_SECONDS = 2.4f

/** The model icon spins in place, continuously speeding up and slowing down. */
@Composable
internal fun GenerationLoadingIndicator(
    modelName: String,
    modifier: Modifier = Modifier,
    modelId: String? = null,
    modelIcon: String? = null
) {
    var angle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        var elapsed = 0f
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    elapsed += dt
                    // Speed eases smoothly between slow and fast; the angle is integrated so it never jumps.
                    val wave = (sin(2.0 * PI * elapsed / SPEED_CYCLE_SECONDS).toFloat() + 1f) / 2f
                    val speed = MIN_DEGREES_PER_SECOND + (MAX_DEGREES_PER_SECOND - MIN_DEGREES_PER_SECOND) * wave
                    angle = (angle + speed * dt) % 360f
                }
                last = now
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        EntityIcon(
            image = modelIcon,
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer { rotationZ = angle }
                .clip(CircleShape), // after graphicsLayer so the clip follows the icon
            text = modelName,
            matchName = modelName,
            fallbackMatchName = modelId,
            model = true
        )
    }
}
