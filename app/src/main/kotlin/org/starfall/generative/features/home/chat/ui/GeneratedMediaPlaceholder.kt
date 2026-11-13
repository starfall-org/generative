package org.starfall.generative.features.home.chat.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.helpers.media.imagePreviewAspectRatio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.shared.ui.layout.windowHeightIn
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

/** An ambient, icon-free media placeholder; image previews follow the requested ratio. */
@Composable
internal fun GeneratedMediaPlaceholder(activity: ToolActivity, modifier: Modifier = Modifier) {
    val video = activity.name == "generate_video"
    val failed = activity.status == "error"
    val label = stringResource(when {
        failed && video -> R.string.media_video_generation_failed
        failed -> R.string.media_image_generation_failed
        video -> R.string.tool_generating_video
        else -> R.string.tool_generating_image
    })
    val scheme = MaterialTheme.colorScheme
    val phase = if (failed) 0f else {
        val transition = rememberInfiniteTransition(label = "media-preview-shimmer")
        val animatedPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(5600, easing = LinearEasing), RepeatMode.Restart),
            label = "media-preview-glow"
        )
        animatedPhase
    }
    val shape = RoundedCornerShape(16.dp)
    val previewRatio = activity.previewAspectRatio?.takeIf { it.isFinite() && it > 0f }
    val needsArguments = !video && previewRatio == null
    // Arguments already in RAM are read directly; stored ones load off the UI thread.
    val resident = if (needsArguments) activity.arguments.peek() else null
    val loadedRatio by produceState<Float?>(null, activity.arguments, needsArguments, resident) {
        value = if (!needsArguments || resident != null) null else try {
            val raw = activity.arguments.text()
            withContext(Dispatchers.Default) {
                runCatching { imagePreviewAspectRatio(Json.parseToJsonElement(raw) as JsonObject) }.getOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val aspectRatio = if (video) 1f else (
        previewRatio
            ?: resident?.let { raw ->
                runCatching { imagePreviewAspectRatio(Json.parseToJsonElement(raw) as JsonObject) }.getOrNull()
            }
            ?: loadedRatio ?: 1f
    )

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.5f),
    ) {
        // Fit the selected image ratio within both the screen width and the preview height cap.
        val width = minOf(maxWidth, maxHeight * aspectRatio)
        val height = width / aspectRatio
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(shape)
                .border(1.dp, (if (failed) scheme.error else scheme.outlineVariant).copy(alpha = 0.5f), shape)
                .semantics { contentDescription = label }
                .testTag("media-generation-placeholder_${activity.id}"),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cycle = phase * (2f * PI.toFloat())
                val pulse = sin(cycle)
                val palette = if (failed) scheme.error else scheme.primary
                val accent = if (failed) scheme.errorContainer else scheme.tertiary

                drawRect(Brush.linearGradient(listOf(scheme.surfaceVariant, scheme.surface)))

                // Soft, overlapping light clouds make a slowly drifting aurora, not a spinner.
                fun haze(color: Color, x: Float, y: Float, radius: Float, opacity: Float) {
                    val center = Offset(w * x, h * y)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(color.copy(alpha = opacity), color.copy(alpha = opacity * 0.32f), Color.Transparent),
                            center = center, radius = radius
                        ),
                        center = center, radius = radius
                    )
                }
                val spread = size.maxDimension * 0.8f
                haze(palette, 0.22f + 0.14f * pulse, 0.27f + 0.12f * cos(cycle), spread, 0.36f)
                haze(accent, 0.78f - 0.13f * pulse, 0.7f - 0.13f * cos(cycle), spread, 0.29f)
                haze(scheme.secondary, 0.56f + 0.1f * cos(cycle), 0.47f, spread * 0.72f, 0.17f)

                // Two translucent ribbons undulate across the entire shape, with no central icon.
                if (!failed) {
                    val wave = Path().apply {
                        moveTo(-w * 0.15f, h * (0.44f + pulse * 0.06f))
                        cubicTo(w * 0.22f, h * (0.12f + pulse * 0.07f),
                            w * 0.67f, h * (0.8f - pulse * 0.08f), w * 1.15f, h * 0.36f)
                        lineTo(w * 1.15f, h * 0.72f)
                        cubicTo(w * 0.62f, h * (1.02f - pulse * 0.07f),
                            w * 0.3f, h * (0.42f + pulse * 0.07f), -w * 0.15f, h * 0.86f)
                        close()
                    }
                    drawPath(wave, Brush.verticalGradient(listOf(
                        scheme.primary.copy(alpha = 0.02f),
                        scheme.tertiary.copy(alpha = 0.19f),
                        scheme.secondary.copy(alpha = 0.05f)
                    )))
                    val secondWave = Path().apply {
                        moveTo(-w * 0.1f, h * (0.15f - pulse * 0.07f))
                        cubicTo(w * 0.32f, h * 0.66f, w * 0.72f, -h * 0.05f, w * 1.1f, h * 0.55f)
                        lineTo(w * 1.1f, h * 0.7f)
                        cubicTo(w * 0.67f, h * 0.14f, w * 0.31f, h * 0.85f, -w * 0.1f, h * 0.29f)
                        close()
                    }
                    drawPath(secondWave, Brush.horizontalGradient(listOf(
                        Color.Transparent, scheme.primary.copy(alpha = 0.12f),
                        scheme.tertiary.copy(alpha = 0.14f), Color.Transparent
                    )))

                    // A few delicate glints move with the same animation cycle.
                    val glints = listOf(
                        Offset(0.16f, 0.21f), Offset(0.73f, 0.18f), Offset(0.85f, 0.56f),
                        Offset(0.34f, 0.77f), Offset(0.58f, 0.43f), Offset(0.18f, 0.57f)
                    )
                    glints.forEachIndexed { index, point ->
                        val twinkle = (0.5f + 0.5f * sin(cycle + index * 1.75f))
                        val center = Offset(w * point.x, h * point.y)
                        drawCircle(scheme.onSurface.copy(alpha = 0.08f + 0.23f * twinkle),
                            radius = 1.1.dp.toPx() + twinkle * 0.7.dp.toPx(), center = center)
                    }
                    val sweep = w * (phase * 2.4f - 0.7f)
                    drawRect(Brush.linearGradient(
                        listOf(Color.Transparent, scheme.onSurface.copy(alpha = 0.07f), Color.Transparent),
                        start = Offset(sweep - w * 0.38f, 0f), end = Offset(sweep + w * 0.26f, h)
                    ))
                }
            }
        }
    }
}
