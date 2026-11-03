package org.starfall.generative.shared.ui.text

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

internal val LocalStreamingTextFade = compositionLocalOf { false }

internal const val STREAM_FADE_DURATION_MS = 120L

/**
 * A single giant Text node requires an offscreen GPU layer of the entire node.
 * Large paragraphs still stream immediately, but skip the decorative reveal mask.
 */
internal const val STREAM_FADE_MAX_NODE_CHARS = 2_048

internal data class StreamingFadeRange(val start: Int, val end: Int, val startedAt: Long)

/** Keep each chunk's original start time when a newer chunk arrives. */
internal class StreamingFadeTracker {
    private var previousText = ""
    private var ranges = emptyList<StreamingFadeRange>()

    fun update(text: String, now: Long): List<StreamingFadeRange> {
        // commonPrefixWith allocates a growing substring on every streamed chunk. Only an
        // integer boundary is needed; retaining the two immutable texts is sufficient.
        var commonLength = 0
        val limit = minOf(previousText.length, text.length)
        while (commonLength < limit && previousText[commonLength] == text[commonLength]) {
            commonLength++
        }
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

/** Index of the oldest range still fading at [now], or -1. Ranges are in start-time order. */
internal fun firstFadingRange(ranges: List<StreamingFadeRange>, limit: Int, now: Long): Int {
    for (i in 0 until minOf(limit, ranges.size)) {
        if (now - ranges[i].startedAt < STREAM_FADE_DURATION_MS) return i
    }
    return -1
}

internal data class StreamingTextPresentation(
    val modifier: Modifier,
    val onTextLayout: (TextLayoutResult) -> Unit
)

internal fun shouldRevealStreamingText(localEnabled: Boolean, systemAnimationsEnabled: Boolean): Boolean =
    localEnabled && systemAnimationsEnabled

internal fun shouldAnimateStreamingTextNode(
    enabled: Boolean,
    systemAnimationsEnabled: Boolean,
    textLength: Int
): Boolean = shouldRevealStreamingText(enabled, systemAnimationsEnabled) &&
    textLength <= STREAM_FADE_MAX_NODE_CHARS

@Composable
internal fun rememberStreamingTextPresentation(text: String): StreamingTextPresentation {
    val enabled = LocalStreamingTextFade.current
    if (!shouldAnimateStreamingTextNode(enabled, ValueAnimator.areAnimatorsEnabled(), text.length)) {
        return StreamingTextPresentation(Modifier, {})
    }

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
        // Everything that depends only on text and layout is built here, once per update.
        // The per-frame draw below only reads the clock and issues draw calls.
        val rangePaths = if (layoutReady) {
            ranges.map { range -> layout.getPathForRange(range.start, range.end) }
        } else emptyList()
        val rangeBounds = rangePaths.map { it.getBounds() }
        // A new Markdown layout can take another frame. Never show the unmasked new text in
        // that frame: preserve only the already measured prefix until its layout is ready.
        val pendingLayout = !layoutReady && ranges.isNotEmpty()
        val oldPrefixPath = if (pendingLayout && layout != null) {
            val prefixEnd = minOf(ranges.minOf { it.start }, layout.layoutInput.text.length)
            if (prefixEnd > 0) layout.getPathForRange(0, prefixEnd) else null
        } else null
        val layerPaint = Paint()
        val featherPx = 12.dp.toPx()
        val nodeBounds = size.toRect()
        onDrawWithContent {
            if (pendingLayout) {
                if (oldPrefixPath != null) clipPath(oldPrefixPath) { this@onDrawWithContent.drawContent() }
                return@onDrawWithContent
            }
            val now = frameTime.longValue
            val first = firstFadingRange(ranges, rangePaths.size, now)
            if (first < 0) {
                drawContent()
                return@onDrawWithContent
            }
            // The text is rendered exactly once, into one layer. The fresh ranges are then
            // masked in place (DstIn) with a feathered edge that advances right and carries the
            // range's own opacity in the mask colour. Settled text lies outside every mask clip.
            drawContext.canvas.saveLayer(nodeBounds, layerPaint)
            drawContent()
            for (i in first until rangePaths.size) {
                val bounds = rangeBounds[i]
                if (bounds.width <= 0f || bounds.height <= 0f) continue
                val progress = FastOutSlowInEasing.transform(
                    ((now - ranges[i].startedAt).toFloat() / STREAM_FADE_DURATION_MS).coerceIn(0f, 1f)
                )
                val revealRight = bounds.left + bounds.width * progress
                val feather = (featherPx * (1f - progress)).coerceAtLeast(0.5f)
                clipPath(rangePaths[i]) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = progress), 1f to Color.Transparent,
                            startX = revealRight - feather,
                            endX = revealRight
                        ),
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        blendMode = BlendMode.DstIn
                    )
                }
            }
            drawContext.canvas.restore()
        }
    }
    return StreamingTextPresentation(modifier) { layoutResult.value = it }
}
