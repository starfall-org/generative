package org.starfall.generative.home.chat.ui

import org.starfall.generative.shared.ui.text.LocalStreamingTextFade
import org.starfall.generative.shared.ui.text.rememberStreamingTextPresentation
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.FluentIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.home.chat.ui.markdown.toComposeFontFamily

/** Follow measured new lines; tokens that stay on the same line do not restart scrolling. */
@Composable
internal fun rememberLiveThinkingScrollState(active: Boolean): ScrollState {
    val state = rememberScrollState()
    LaunchedEffect(active, state) {
        if (active) snapshotFlow { state.maxValue }.collectLatest { target ->
            // The scroll range is unknown until the first measurement.
            if (target in 1 until Int.MAX_VALUE) {
                state.animateScrollTo(target, animationSpec = tween(240, easing = LinearOutSlowInEasing))
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
        Box(
            Modifier
                .animateContentSize()
                .then(previewModifier)
                .testTag("live-thinking-preview")
                .then(fadingEdges)
                .then(scrollModifier)
                .clickable { expanded = !expanded }
        ) {
            CompositionLocalProvider(LocalStreamingTextFade provides active) {
                val presentation = rememberStreamingTextPresentation(reasoning)
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = messageFont,
                        fontSize = bodySize,
                        lineHeight = bodyLineHeight
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = presentation.modifier,
                    onTextLayout = presentation.onTextLayout
                )
            }
        }
    }
}
