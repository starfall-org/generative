package org.starfall.generative.features.home.chat.ui.markdown

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * The bottom edge is the actual clipping boundary of the streaming content's layout.
 * Children are measured at their natural size and remain in place while growth of the
 * parent layout is animated; text below that boundary is genuinely clipped, not covered
 * by an animated mask or a second layer.
 *
 * Track the largest *measured* child height so a transient Markdown reflow cannot make
 * the edge travel upward. This state is scoped to the renderer's composition/generation.
 */
@Composable
internal fun StreamGrowthViewport(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    content: @Composable () -> Unit
) {
    var largestMeasuredHeightPx by remember { mutableIntStateOf(0) }
    val minimumMeasuredHeight = with(LocalDensity.current) { largestMeasuredHeightPx.toDp() }

    Box(
        modifier = modifier.fillMaxWidth().then(
            if (active) Modifier.clipToBounds().animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                alignment = Alignment.TopStart
            ) else Modifier // Completion removes the clip immediately, without unmounting any child.
        )
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = if (active) minimumMeasuredHeight else 0.dp)) {
            Box(Modifier.fillMaxWidth().onSizeChanged { measured ->
                if (measured.height > largestMeasuredHeightPx) {
                    largestMeasuredHeightPx = measured.height
                }
            }) {
                content()
            }
        }
    }
}
