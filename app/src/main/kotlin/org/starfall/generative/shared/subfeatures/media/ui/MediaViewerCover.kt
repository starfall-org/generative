package org.starfall.generative.shared.subfeatures.media.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import org.starfall.generative.shared.ui.layout.ScreenCover

/**
 * Edge-to-edge media cover with a single content instance. Zooming never swaps the content tree:
 * it only fades a black backdrop in behind it, so gesture/image state survives the whole pinch.
 */
@Composable
internal fun MediaViewerCover(
    mediaKey: Any,
    onDismiss: () -> Unit,
    actions: List<MediaViewerAction>,
    content: @Composable BoxScope.(onZoomChanged: (Boolean) -> Unit) -> Unit
) {
    var zoomed by remember(mediaKey) { mutableStateOf(false) }
    val backdrop by animateFloatAsState(if (zoomed) 1f else 0f, label = "mediaZoomBackdrop")

    ScreenCover(onDismiss = onDismiss) {
        Box(
            Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = backdrop)) },
            contentAlignment = Alignment.Center
        ) {
            content { zoomed = it }
            MediaViewerMenu(actions = actions, onDismiss = onDismiss)
        }
    }
}

/** Non-zoomable media (video, audio, text): taps inside its bounds don't reach the dismissing scrim. */
@Composable
internal fun MediaTapShield(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center,
        content = content
    )
}
