package org.starfall.generative.shared.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout

/** A single, non-overshooting timeline for every full-screen transition. */
internal val screenTransitionSpec = tween<Float>(durationMillis = 320, easing = FastOutSlowInEasing)

/**
 * Each destination has a fixed stack depth. Moving the shared camera between
 * depths slides the front page and gently recedes the page underneath.
 */
internal fun screenSlideOffset(depth: Float, camera: Float): Float {
    val distance = (depth - camera).coerceIn(-1f, 1f)
    return if (distance >= 0f) distance else distance * 0.12f
}

internal fun screenScale(depth: Float, camera: Float): Float =
    1f - (camera - depth).coerceIn(0f, 1f) * 0.06f

internal fun Modifier.screenSlide(offset: () -> Float, scale: () -> Float = { 1f }): Modifier = graphicsLayer {
    translationX = size.width * offset()
    scaleX = scale()
    scaleY = scale()
}

/** Hidden content stays composed but is not placed, drawn, or touchable. */
fun Modifier.retainWhen(visible: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        if (visible) placeable.place(0, 0)
    }
}
