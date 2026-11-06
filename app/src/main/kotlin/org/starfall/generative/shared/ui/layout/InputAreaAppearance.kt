package org.starfall.generative.shared.ui.layout

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/** Composer appearance is independent of component and background-photo transparency. */
data class InputAreaAppearance(val transparency: Float = 0.1f, val blur: Float = 2f) {
    val alpha: Float get() = 1f - transparency.coerceIn(0f, 1f)
    val blurRadius: Float get() = blur.coerceIn(0f, 32f)
    fun surfaceColor(color: Color): Color = color.copy(alpha = alpha)
}

val LocalInputAreaAppearance = compositionLocalOf { InputAreaAppearance() }

/** Records only the content behind the composer; never records the composer itself. */
class InputAreaBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var revision by mutableIntStateOf(0)
}

@Composable
fun rememberInputAreaBackdrop(): InputAreaBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { InputAreaBackdrop(layer) }
}

fun Modifier.inputAreaBackdropSource(backdrop: InputAreaBackdrop, enabled: Boolean): Modifier =
    if (!enabled) this else onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
            Snapshot.withoutReadObservation { backdrop.revision++ }
        }

/** Blur only this background layer, leaving text, icons and hit targets untouched. */
@Composable
fun InputAreaBackdropLayer(
    backdrop: InputAreaBackdrop?,
    modifier: Modifier = Modifier,
    appearance: InputAreaAppearance = LocalInputAreaAppearance.current
) {
    if (backdrop == null || appearance.blurRadius <= 0f || Build.VERSION.SDK_INT < 31) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }
        .graphicsLayer {
            val radius = appearance.blurRadius * density
            renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
        }
        .drawWithContent {
            // A scrolling source must invalidate this layer even when the composer stays still.
            backdrop.revision
            val offset = backdrop.origin - origin
            translate(offset.x, offset.y) { drawLayer(backdrop.layer) }
        })
}
