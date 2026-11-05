package org.starfall.generative.shared.ui.background

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import org.starfall.generative.shared.subfeatures.icons.logic.AppImages

/** Alpha for the background photo ONLY; the screen/sheet/dialog/drawer remains opaque. */
internal fun backgroundPhotoAlpha(transparency: Float): Float = 1f - transparency.coerceIn(0f, 1f)

@Composable
fun ProfileBackground(image: String?, modifier: Modifier = Modifier.fillMaxSize(), transparency: Float = 0.5f) {
    if (image.isNullOrBlank()) return
    AsyncImage(
        model = AppImages.source(image),
        contentDescription = null,
        // Respect matchParentSize() when placed below modal content; appending fillMaxSize()
        // here would override that parent-data sizing and may crop the image unexpectedly.
        modifier = modifier.graphicsLayer(alpha = backgroundPhotoAlpha(transparency)),
        contentScale = ContentScale.Crop
    )
}
