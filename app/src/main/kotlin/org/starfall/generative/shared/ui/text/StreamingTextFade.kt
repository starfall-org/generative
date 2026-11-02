package org.starfall.generative.shared.ui.text

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

internal val LocalStreamingTextFade = compositionLocalOf { false }

/**
 * Read in the draw phase only. While it returns true (the user is dragging or flinging the list)
 * the fade is skipped: clipPath + saveLayer per range on every scroll frame made scrolling stutter.
 */
internal val LocalStreamingFadePaused = compositionLocalOf<() -> Boolean> { { false } }
internal const val STREAM_FADE_DURATION_MS = 120L

internal data class StreamingFadeRange(val start: Int, val end: Int, val startedAt: Long)

/** Keep each chunk's original start time when a newer chunk arrives. */
internal class StreamingFadeTracker {
    private var previousText = ""
    private var ranges = emptyList<StreamingFadeRange>()

    fun update(text: String, now: Long): List<StreamingFadeRange> {
        val commonLength = previousText.commonPrefixWith(text).length
        ranges = ranges.mapNotNull { range ->
            val end = minOf(range.end, commonLength)
            if (range.start < end && now - range.startedAt < STREAM_FADE_DURATION_MS) {
                range.copy(end = end)
            } else null
        }
        if (commonLength < text.length) {
            ranges = ranges + StreamingFadeRange(commonLength, text.length, now)
        }
        previousText = text
        return ranges
    }
}

internal data class StreamingTextPresentation(
    val modifier: Modifier,
    val onTextLayout: (TextLayoutResult) -> Unit
)

internal fun shouldRevealStreamingText(localEnabled: Boolean, systemAnimationsEnabled: Boolean): Boolean =
    localEnabled && systemAnimationsEnabled

@Composable
internal fun rememberStreamingTextPresentation(text: String): StreamingTextPresentation {
    val enabled = LocalStreamingTextFade.current
    if (!shouldRevealStreamingText(enabled, ValueAnimator.areAnimatorsEnabled())) {
        return StreamingTextPresentation(Modifier, {})
    }

    val fadePaused = LocalStreamingFadePaused.current
    val tracker = remember { StreamingFadeTracker() }
    val ranges = remember(text) { tracker.update(text, SystemClock.uptimeMillis()) }
    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    val frameTime = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(ranges) {
        val finishAt = ranges.maxOfOrNull { it.startedAt + STREAM_FADE_DURATION_MS } ?: return@LaunchedEffect
        // Advance from the Compose frame clock. Under a virtual animation clock,
        // SystemClock.uptimeMillis() need not advance, which otherwise spins forever.
        val initialUptime = SystemClock.uptimeMillis()
        var firstFrameNanos = -1L
        do {
            withFrameNanos { nanos ->
                if (firstFrameNanos < 0L) firstFrameNanos = nanos
                frameTime.longValue = initialUptime + (nanos - firstFrameNanos) / 1_000_000L
            }
        } while (frameTime.longValue < finishAt)
    }

    val modifier = Modifier.drawWithCache {
        val layout = layoutResult.value
        val layoutReady = layout?.layoutInput?.text?.text == text
        val paths = if (layoutReady) {
            ranges.map { range -> range to layout.getPathForRange(range.start, range.end) }
        } else emptyList()
        // A new Markdown layout can take another frame. Never show the unmasked new text in
        // that frame: preserve only the already measured prefix until its layout is ready.
        val pendingLayout = !layoutReady && ranges.isNotEmpty()
        val oldPrefixPath = if (pendingLayout && layout != null) {
            val prefixEnd = minOf(ranges.minOf { it.start }, layout.layoutInput.text.length)
            if (prefixEnd > 0) layout.getPathForRange(0, prefixEnd) else null
        } else null
        val paint = Paint()
        val featherPx = 12.dp.toPx()
        onDrawWithContent {
            if (fadePaused()) {
                drawContent()
                return@onDrawWithContent
            }
            if (pendingLayout) {
                if (oldPrefixPath != null) clipPath(oldPrefixPath) { this@onDrawWithContent.drawContent() }
                return@onDrawWithContent
            }
            val now = frameTime.longValue
            val active = paths.filter { (range, _) -> now - range.startedAt < STREAM_FADE_DURATION_MS }
            if (active.isEmpty()) {
                drawContent()
            } else {
                val fadingPath = Path().apply { active.forEach { (_, path) -> addPath(path) } }
                clipPath(fadingPath, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                active.forEach { (range, path) ->
                    val progress = FastOutSlowInEasing.transform(
                        ((now - range.startedAt).toFloat() / STREAM_FADE_DURATION_MS).coerceIn(0f, 1f)
                    )
                    val bounds = path.getBounds()
                    if (bounds.width <= 0f || bounds.height <= 0f) return@forEach
                    val revealRight = bounds.left + bounds.width * progress
                    val feather = (featherPx * (1f - progress)).coerceAtLeast(0.5f)
                    paint.alpha = progress
                    clipPath(path) {
                        clipRect(left = bounds.left, top = bounds.top, right = revealRight, bottom = bounds.bottom) {
                            drawContext.canvas.saveLayer(bounds, paint)
                            this@onDrawWithContent.drawContent()
                            // An advancing feathered edge reveals the new text; it is masked from
                            // its very first frame, instead of popping into place before fading.
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.Black, 1f to Color.Transparent,
                                    startX = revealRight - feather,
                                    endX = revealRight
                                ),
                                blendMode = BlendMode.DstIn
                            )
                            drawContext.canvas.restore()
                        }
                    }
                }
            }
        }
    }
    return StreamingTextPresentation(modifier) { layoutResult.value = it }
}
