package org.starfall.generative.home.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.starfall.generative.shared.ui.icons.EntityIcon

/** The model icon rolls (like a real wheel) to the screen center and back, repeatedly. */
@Composable
internal fun GenerationLoadingIndicator(
    modelName: String,
    modifier: Modifier = Modifier,
    modelId: String? = null,
    modelIcon: String? = null
) {
    val iconSize = 26.dp
    val iconSizePx = with(LocalDensity.current) { iconSize.toPx() }
    val travel = remember { Animatable(0f) } // 0 = start, 1 = screen center
    var containerWidthPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            travel.animateTo(1f, tween(durationMillis = 2_000))
            delay(500)
            travel.animateTo(0f, tween(durationMillis = 2_000))
            delay(500)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { containerWidthPx = it.width.toFloat() },
        contentAlignment = Alignment.CenterStart
    ) {
        EntityIcon(
            image = modelIcon,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    val maxTravel = (containerWidthPx - iconSizePx).coerceAtLeast(0f)
                    val dx = travel.value * maxTravel / 2f
                    translationX = dx
                    // True rolling: arc length = radius * angle.
                    rotationZ = Math.toDegrees((dx / (iconSizePx / 2f)).toDouble()).toFloat()
                }
                .clip(CircleShape), // after graphicsLayer so the clip follows the icon
            text = modelName,
            matchName = modelName,
            fallbackMatchName = modelId,
            model = true
        )
    }
}
