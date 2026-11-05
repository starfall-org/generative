package org.starfall.generative.shared.subfeatures.modelpicker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.shared.model.language.DEFAULT_REASONING_EFFORTS
import org.starfall.generative.shared.model.language.REASONING_EFFORT_ACTIVE
import org.starfall.generative.shared.model.language.SELECTABLE_REASONING_EFFORTS

/** Labels for the built-in reasoning options. Defaults keep plain English for non-Compose callers. */
internal data class ReasoningEffortLabels(
    val none: String = "None",
    val default: String = "Default",
    val active: String = "Active",
    val xhigh: String = "X-High",
)

@Composable
internal fun localizedReasoningEffortLabels(): ReasoningEffortLabels = ReasoningEffortLabels(
    none = stringResource(R.string.reasoning_level_none),
    default = stringResource(R.string.reasoning_level_default),
    active = stringResource(R.string.reasoning_level_active),
    xhigh = stringResource(R.string.reasoning_level_xhigh),
)

internal fun reasoningEffortLabel(
    effort: String,
    labels: ReasoningEffortLabels = ReasoningEffortLabels()
): String = when (effort) {
    "xhigh" -> labels.xhigh
    else -> effort.replaceFirstChar { it.uppercase() }
}

private fun isReasoningLevel(effort: String?): Boolean =
    effort != null && reasoningEffortEnabled(effort) && effort != REASONING_EFFORT_ACTIVE

/** Only the highest available reasoning level gets the maximum-effort colour effect. */
internal fun reasoningEffortTier(index: Int, options: List<ReasoningEffortOption>): Int {
    val option = options.getOrNull(index) ?: return 1
    if (!reasoningEffortEnabled(option.effort)) return 0
    if (option.effort == null) return 1
    if (option.effort == REASONING_EFFORT_ACTIVE) return 6
    val levels = options.filter { isReasoningLevel(it.effort) }
    val position = levels.indexOf(option)
    return if (position == levels.lastIndex) 6 else minOf(2 + position, 4)
}

internal fun reasoningEffortAccent(tier: Int, default: Color): Color = when (tier) {
    6 -> Color(0xFFB91C1C)
    else -> default
}

internal fun reasoningEffortBurstDuration(tier: Int): Int = when (tier) {
    0, 1 -> 850
    2 -> 1050
    3 -> 1250
    4, 5 -> 1500
    else -> 4200
}

internal data class ReasoningEffortOption(val effort: String?, val label: String)

internal fun reasoningEffortOptions(
    supportedEfforts: List<String> = DEFAULT_REASONING_EFFORTS,
    labels: ReasoningEffortLabels = ReasoningEffortLabels()
): List<ReasoningEffortOption> {
    val levels = SELECTABLE_REASONING_EFFORTS.filter { it in supportedEfforts }.map { effort ->
        ReasoningEffortOption(effort, reasoningEffortLabel(effort, labels))
    }
    // Active asks the server to enable reasoning without choosing a configured level.
    val active = if (levels.isEmpty()) listOf(ReasoningEffortOption(REASONING_EFFORT_ACTIVE, labels.active)) else emptyList()
    return listOf(ReasoningEffortOption("none", labels.none), ReasoningEffortOption(null, labels.default)) +
        if (levels.isEmpty()) active else levels
}

internal fun reasoningEffortEnabled(effort: String?): Boolean =
    effort?.trim()?.lowercase() !in listOf("none", "off")

/** Anything the selector does not offer is shown as Default, which is also what gets sent. */
internal fun reasoningEffortIndex(
    effort: String?,
    options: List<ReasoningEffortOption> = reasoningEffortOptions()
): Int {
    val normalized = effort?.trim()?.lowercase()?.let { if (it == "off") "none" else it }
    val defaultIndex = options.indexOfFirst { it.effort == null }.coerceAtLeast(0)
    if (normalized.isNullOrEmpty()) return defaultIndex
    return options.indexOfFirst { it.effort == normalized }.takeIf { it >= 0 } ?: defaultIndex
}

/** Snap only on release; use a continuous slider while the finger is moving. */
internal fun nearestReasoningEffortIndex(value: Float, optionCount: Int): Int =
    value.roundToInt().coerceIn(0, (optionCount - 1).coerceAtLeast(0))

/** Changes only the conversation override; model capabilities remain untouched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningEffortControl(
    effort: String?,
    onEffortChange: (String?) -> Unit,
    onPreviewValueChange: (Float) -> Unit = {},
    options: List<ReasoningEffortOption> = reasoningEffortOptions(labels = localizedReasoningEffortLabels()),
    onColorBurst: (Offset, Int) -> Unit = { _, _ -> }
) {
    var sliderValue by remember(options) {
        mutableFloatStateOf(reasoningEffortIndex(effort, options).toFloat())
    }
    var lastCommittedEffort by remember(options) { mutableStateOf(effort) }
    val maximumValue = options.lastIndex.toFloat()
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val layoutDirection = LocalLayoutDirection.current
    val interactionSource = remember { MutableInteractionSource() }
    val dragged by interactionSource.collectIsDraggedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val thumbScale by animateFloatAsState(if (dragged || pressed) 1.1f else 1f,
        tween(160), label = "thinking-thumb-scale")
    val burst = rememberThinkingColorBurst()
    var trackBounds by remember { mutableStateOf(Rect.Zero) }
    fun colorBand(value: Float) = reasoningEffortTier(value.roundToInt(), options)
    val band = colorBand(sliderValue)
    val defaultColor = MaterialTheme.colorScheme.primary
    val defaultInactive = MaterialTheme.colorScheme.primaryContainer
    fun accentFor(tier: Int) = reasoningEffortAccent(tier, defaultColor)
    val accent = accentFor(band)
    val thumbColor by animateColorAsState(accent, tween(500), label = "thinking-thumb-color")
    val sliderColors = SliderDefaults.colors(thumbColor = thumbColor)
    val labelColor = LocalContentColor.current.copy(alpha = 0.85f)
    val sliderState = remember(options.size, maximumValue) {
        SliderState(value = sliderValue, steps = 0, trackRange = 0f..maximumValue)
    }
    sliderState.value = sliderValue
    fun updatePreview(value: Float) {
        val previousBand = colorBand(sliderValue)
        sliderValue = value
        onPreviewValueChange(value)
        val nextBand = colorBand(value)
        if (nextBand != previousBand && trackBounds.width > 0f) {
            // A departing maximum tier retracts at the end of the track.
            val fraction = (if (previousBand == 6) 1f else value / maximumValue).coerceIn(0f, 1f)
            val physicalFraction = if (layoutDirection == LayoutDirection.Rtl) 1f - fraction else fraction
            val origin = Offset(trackBounds.width * physicalFraction, trackBounds.height / 2f)
            if (nextBand == 6) {
                burst.start(origin, accentFor(6), reasoningEffortBurstDuration(6))
                onColorBurst(trackBounds.topLeft + origin, nextBand)
            } else if (previousBand == 6) {
                burst.retract(origin)
                onColorBurst(trackBounds.topLeft + origin, nextBand)
            }
        }
    }
    LaunchedEffect(effort, options) {
        if (effort != lastCommittedEffort) {
            settleJob?.cancel()
            lastCommittedEffort = effort
            updatePreview(reasoningEffortIndex(effort, options).toFloat())
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Slider(
                state = sliderState,
                onValueChange = {
                    settleJob?.cancel()
                    updatePreview(it)
                },
                onValueChangeFinished = {
                    val targetIndex = nearestReasoningEffortIndex(sliderValue, options.size)
                    val selectedEffort = options[targetIndex].effort
                    lastCommittedEffort = selectedEffort
                    onEffortChange(selectedEffort)
                    settleJob?.cancel()
                    settleJob = scope.launch {
                        Animatable(sliderValue).animateTo(
                            targetValue = targetIndex.toFloat(),
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) {
                            updatePreview(value)
                        }
                    }
                },
                colors = sliderColors,
                interactionSource = interactionSource,
                thumb = {
                    Canvas(
                         modifier = Modifier.size(28.dp).graphicsLayer {
                            val pulse = burst.progress.value
                            val scale = thumbScale + 0.12f * (1f - pulse)
                             scaleX = scale
                             scaleY = scale
                              alpha = 1f
                          }.shadow(3.dp, CircleShape)
                    ) {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(lerp(thumbColor, Color.White, 0.28f), thumbColor),
                                center = center - Offset(3.dp.toPx(), 3.dp.toPx()),
                                radius = size.minDimension
                            )
                        )
                    }
                },
                track = {
                    Box(Modifier.fillMaxWidth().height(12.dp)
                        .onGloballyPositioned {
                             trackBounds = Rect(it.localToScreen(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                        }
                        .clip(CircleShape).drawBehind {
                             val radius = CornerRadius(size.height / 2f)
                             fun paintDefaultTrack() {
                                 drawRoundRect(defaultInactive, cornerRadius = radius)
                                 val width = size.width * (sliderValue / maximumValue).coerceIn(0f, 1f)
                                 drawRoundRect(defaultColor,
                                     topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, 0f),
                                     size = Size(width, size.height), cornerRadius = radius)
                             }
                             fun paintMaxTrack() {
                                 drawRoundRect(Brush.horizontalGradient(
                                     listOf(Color(0xFF991B1B), Color(0xFF160406))
                                 ), cornerRadius = radius)
                             }
                             paintDefaultTrack()
                             if (band == 6 || burst.isRetracting) {
                                 revealThinkingColor(burst) { paintMaxTrack() }
                             }
                            // Subtle detents remain visible beneath the settling color.
                            repeat(options.size) { index ->
                                val x = size.width * index / options.lastIndex
                                  drawCircle(Color.White.copy(alpha = 0.32f), 1.25.dp.toPx(), Offset(x, size.height / 2f))
                             }
                              if (band == 6) drawThinkingColorBurst(burst)
                        })
                },
                modifier = Modifier.fillMaxWidth().testTag("reasoning-slider")
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                options.forEachIndexed { index, option ->
                    Text(option.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (index == sliderValue.roundToInt()) labelColor else labelColor.copy(alpha = 0.65f))
                }
            }
        }
    }
}

/** Max-effort card: only black, purple, red and orange (light streaks are drawn on top of it). */
internal fun maximumEffortCardBrush(): Brush = Brush.horizontalGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFF0A0A0C),
        0.26f to Color(0xFF3B1275),
        0.52f to Color(0xFFB91C1C),
        0.78f to Color(0xFFEA580C),
        1.00f to Color(0xFF1A0A06)
    )
)
