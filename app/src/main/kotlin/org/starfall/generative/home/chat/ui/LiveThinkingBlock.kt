package org.starfall.generative.home.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.exp
import kotlin.math.max
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.FluentIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.home.chat.ui.markdown.toComposeFontFamily

/** Time constant of the exponential follow; the remaining distance shrinks ~63% per interval. */
private const val THINKING_FOLLOW_TIME_CONSTANT_SECONDS = 0.1f
/** Floor speed so the follow always finishes instead of crawling through the last pixels. */
private const val THINKING_FOLLOW_MIN_SPEED_DP_PER_SECOND = 480f

/**
 * Keep the newest thinking line in view with a continuous, per-frame ease toward the bottom.
 *
 * The follower is stateless (it only looks at the current distance to the bottom), so a new
 * measured line can restart it at any moment without the stop/start stutter that restarting a
 * fixed-duration `animateScrollTo` caused. A user drag stops following until the next new line.
 */
@Composable
internal fun rememberLiveThinkingScrollState(active: Boolean): ScrollState {
    val state = rememberScrollState()
    val minSpeedPx = with(LocalDensity.current) { THINKING_FOLLOW_MIN_SPEED_DP_PER_SECOND.dp.toPx() }
    LaunchedEffect(active, state) {
        if (active) snapshotFlow { state.maxValue }.collectLatest { target ->
            // The scroll range is unknown until the first measurement.
            if (target !in 1 until Int.MAX_VALUE) return@collectLatest
            var lastFrame = withFrameNanos { it }
            while (!state.isScrollInProgress) {
                val frame = withFrameNanos { it }
                val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
                lastFrame = frame
                // Re-read the bound each frame: more lines can arrive while easing.
                val distance = (state.maxValue - state.value).toFloat()
                if (distance <= 0f) break
                val eased = distance * (1f - exp(-seconds / THINKING_FOLLOW_TIME_CONSTANT_SECONDS))
                state.dispatchRawDelta(max(eased, minSpeedPx * seconds).coerceAtMost(distance))
            }
        }
    }
    return state
}

@Composable
internal fun LiveThinkingBlock(reasoning: String, active: Boolean, showHeader: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    val scrollState = rememberLiveThinkingScrollState(active && !expanded)
    val density = LocalDensity.current
    val preferences = LocalCodeRenderingPreferences.current
    val bodySize = preferences.effectiveMessageFontSize.sp
    val messageFont = preferences.messageFontFamily.toComposeFontFamily()
    val bodyLineHeight = bodySize * 1.375f
    val previewHeight = with(density) { (bodyLineHeight * 5f).toDp() }
    val fadeHeight = with(density) { bodyLineHeight.toPx() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        if (showHeader) Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(if (active) R.string.thinking_streaming else R.string.processed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.weight(1f))
            if (active) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(
                    Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.width(6.dp))
            FluentIcon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
        }
        if (showHeader) Spacer(Modifier.height(4.dp))
        val fadingEdges = if (!active) Modifier else Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (size.height > 0f && scrollState.maxValue in 1 until Int.MAX_VALUE) {
                    val edge = fadeHeight.coerceAtMost(size.height / 3f).coerceAtLeast(1f)
                    val topAlpha = 1f - (scrollState.value / edge).coerceIn(0f, 1f)
                    val bottomAlpha = 1f - ((scrollState.maxValue - scrollState.value) / edge).coerceIn(0f, 1f)
                    val mask = Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = topAlpha),
                        (edge / size.height) to Color.Black,
                        (1f - edge / size.height) to Color.Black,
                        1f to Color.Black.copy(alpha = bottomAlpha)
                    )
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
            }
        val previewModifier = if (expanded) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.fillMaxWidth().heightIn(max = previewHeight)
        }
        val scrollModifier = if (expanded) Modifier else Modifier.verticalScroll(scrollState)
        // While the preview is live and collapsed, animating its height would run at the same time
        // as the scroll follower (the viewport is briefly smaller than the content), so the two
        // fight and the text shudders. Size animation is kept for expand/collapse and when idle.
        val animateSize = !active || expanded
        Box(
            Modifier
                .then(if (animateSize) Modifier.animateContentSize() else Modifier)
                .then(previewModifier)
                .testTag("live-thinking-preview")
                .then(fadingEdges)
                .then(scrollModifier)
                .clickable { expanded = !expanded }
        ) {
            // No per-token fade here: it redraws the whole text once per recent token and double-draws
            // anti-aliased glyph edges, which shows as flicker (worst with light text on dark). The
            // preview's own edge mask and smooth scroll already provide the soft entrance.
            Text(
                reasoning,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = messageFont,
                    fontSize = bodySize,
                    lineHeight = bodyLineHeight
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
        }
    }
}
