package org.starfall.generative.shared.ui.screens

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout

/** The front page crosses the screen while the page underneath recedes slightly. */
internal fun screenSlideOffset(active: Boolean, front: Boolean): Float = when {
    active -> 0f
    front -> 1f
    else -> -0.12f
}

internal fun screenScale(active: Boolean, front: Boolean): Float =
    if (active || front) 1f else 0.94f

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
