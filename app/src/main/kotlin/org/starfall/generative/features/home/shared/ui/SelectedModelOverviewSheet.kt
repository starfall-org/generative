package org.starfall.generative.features.home.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.starfall.generative.R
import org.starfall.generative.shared.ui.model.LocalThinkingScreenBurst
import org.starfall.generative.shared.ui.model.ThinkingScreenBurstLayer
import org.starfall.generative.shared.ui.model.ReasoningEffortControl
import org.starfall.generative.shared.ui.model.localizedReasoningEffortLabels
import org.starfall.generative.shared.ui.model.maximumEffortCardBrush
import org.starfall.generative.shared.ui.model.reasoningEffortOptions
import org.starfall.generative.shared.ui.model.reasoningEffortIndex
import org.starfall.generative.shared.ui.model.reasoningEffortTier
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.language.supportedReasoningEfforts
import org.starfall.generative.shared.ui.sheets.BottomSheet
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.EntityIcon
import org.starfall.generative.shared.ui.model.modelInitial
import org.starfall.generative.shared.ui.model.ModelPickerCard
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.sheets.SlideSheetContent
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.model.drawMaximumEffortStreaks

/** Sheet background that follows the reasoning tier: the maximum effort tier gets a red brush. */
internal class SelectedModelSheetStyle(
    val color: Color,
    val brush: Brush?,
    val navigationBarColor: Color,
    val overlay: (DrawScope.() -> Unit)?
)

@Composable
internal fun selectedModelSheetStyle(maxEffort: Boolean, retracting: Boolean = false): SelectedModelSheetStyle {
    val brush = if (maxEffort || retracting) maximumEffortCardBrush() else null
    val color = if (maxEffort) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow
    return SelectedModelSheetStyle(
        color = color,
        brush = brush,
        navigationBarColor = if (maxEffort) Color(0xFF1A0A06) else color,
        overlay = if (maxEffort || retracting) ({ drawMaximumEffortStreaks(1f) }) else null
    )
}

/** Tier previewed by the effort slider; hoisted so a host sheet can restyle itself while it moves. */
@Composable
internal fun rememberPreviewTier(config: ModelConfiguration, conversationReasoningEffort: String?): MutableIntState {
    val reasoningLabels = localizedReasoningEffortLabels()
    val effortOptions = remember(config.supportedReasoningEfforts, reasoningLabels) {
        reasoningEffortOptions(config.supportedReasoningEfforts, reasoningLabels)
    }
    return remember(conversationReasoningEffort, effortOptions) {
        mutableIntStateOf(reasoningEffortTier(reasoningEffortIndex(conversationReasoningEffort, effortOptions), effortOptions))
    }
}

/** Standalone sheet; the chat uses [SelectedModelOverviewContent] as a page of a shared model sheet instead. */
@Composable
internal fun SelectedModelOverviewSheet(
    provider: ModelProvider? = null,
    folderName: String? = null,
    modelId: String,
    config: ModelConfiguration,
    conversationReasoningEffort: String?,
    onSetReasoningEffort: (String?) -> Unit,
    onSetThinkingPassback: ((ThinkingPassback) -> Unit)? = null,
    onSetModelTools: ((List<String>) -> Unit)? = null,
    onOpenModelPicker: () -> Unit,
    onDismiss: () -> Unit
) {
    val previewTier = rememberPreviewTier(config, conversationReasoningEffort)
    val screenBurst = LocalThinkingScreenBurst.current
    var showOptions by remember { mutableStateOf(false) }
    val style = selectedModelSheetStyle(
        !showOptions && previewTier.intValue == 6 && config.supportsThinking,
        !showOptions && screenBurst?.isRetracting == true
    )
    BottomSheet(onDismissRequest = {
        screenBurst?.hide()
        onDismiss()
    }, dismissOnBack = !showOptions, containerColor = style.color, containerBrush = style.brush,
        navigationBarColor = style.navigationBarColor,
        // Read in the draw phase: the reveal animates every frame and must not recompose the sheet.
        containerBrushRevealOrigin = { screenBurst?.origin?.takeIf { it != Offset.Zero } },
        containerBrushRevealProgress = { screenBurst?.progress?.value ?: 1f },
        containerBrushRetractionProgress = { screenBurst?.takeIf { it.isRetracting }?.retractionProgress?.value },
        containerOverlay = style.overlay
    ) {
        // The sheet is its own window, so the screen-owned star overlay must also be attached to it;
        // otherwise the stars are only drawn on the activity window, underneath the sheet and cards.
        screenBurst?.let { ThinkingScreenBurstLayer(it) }
        SlideSheetContent(
            page = if (showOptions) true else null,
            onBack = { showOptions = false },
            label = "Selected model options"
        ) { optionsPage ->
            val pageActive = LocalScreenTransitionActive.current
            if (optionsPage == true) {
                SelectedModelOptionsSheetContent(
                    modelId = modelId,
                    config = config,
                    providerName = provider?.name,
                    onBack = { if (pageActive) showOptions = false },
                    onSetThinkingPassback = onSetThinkingPassback,
                    onSetModelTools = onSetModelTools
                )
            } else {
                SelectedModelOverviewContent(
                    provider = provider,
                    folderName = folderName,
                    modelId = modelId,
                    config = config,
                    conversationReasoningEffort = conversationReasoningEffort,
                    previewTier = previewTier.intValue,
                    onPreviewTierChange = { previewTier.intValue = it },
                    onSetReasoningEffort = onSetReasoningEffort,
                    onOpenModelPicker = onOpenModelPicker,
                    onOpenModelOptions = { if (pageActive) { screenBurst?.hide(); showOptions = true } }
                )
            }
        }
    }
}

@Composable
internal fun SelectedModelOverviewContent(
    provider: ModelProvider? = null,
    folderName: String? = null,
    modelId: String,
    config: ModelConfiguration,
    conversationReasoningEffort: String?,
    previewTier: Int,
    onPreviewTierChange: (Int) -> Unit,
    onSetReasoningEffort: (String?) -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenModelOptions: () -> Unit = {},
    contextWindowContent: (@Composable (onDarkBackground: Boolean) -> Unit)? = null,
    onSummarize: (() -> Unit)? = null
) {
    val screenBurst = LocalThinkingScreenBurst.current
    val reasoningLabels = localizedReasoningEffortLabels()
    val effortOptions = remember(config.supportedReasoningEfforts, reasoningLabels) {
        reasoningEffortOptions(config.supportedReasoningEfforts, reasoningLabels)
    }
    var previewValue by remember(effortOptions) {
        mutableFloatStateOf(reasoningEffortIndex(conversationReasoningEffort, effortOptions).toFloat())
    }
    LaunchedEffect(previewTier, screenBurst) {
        when {
            previewTier != 6 && screenBurst?.keepAfterCompletion == true && !screenBurst.isRetracting -> screenBurst.hide()
            previewTier == 6 && config.supportsThinking && screenBurst != null && !screenBurst.keepAfterCompletion ->
                screenBurst.restoreSettled()
        }
    }

    val maxEffortSheet = previewTier == 6 && config.supportsThinking
    val contentColor = if (maxEffortSheet) Color.White else MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (onSummarize != null) Arrangement.SpaceBetween else Arrangement.End
            ) {
                if (onSummarize != null) {
                    FilledTonalButton(
                        onClick = {
                            screenBurst?.hide()
                            onSummarize()
                        },
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                        modifier = Modifier.testTag("summarize-conversation-button")
                    ) {
                        SharedIcon(Icons.Outlined.Summarize, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.summarize_now), maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                FilledTonalButton(
                    onClick = {
                        screenBurst?.hide()
                        onOpenModelPicker()
                    },
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp),
                    modifier = Modifier.weight(1f, fill = false).testTag("open-model-picker-button")
                ) {
                    EntityIcon(
                        image = config.icon,
                        modifier = Modifier.size(24.dp).testTag("selected-model-button-icon"),
                        text = modelInitial(modelId),
                        matchName = config.displayName.ifBlank { modelId },
                        fallbackMatchName = modelId,
                        model = true
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.provider_models),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                }
            }

            contextWindowContent?.invoke(maxEffortSheet)

            if (config.supportsThinking) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.reasoning_effort_title),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(effortOptions[previewValue.roundToInt().coerceIn(effortOptions.indices)].label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (maxEffortSheet) Color.White.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ReasoningEffortControl(
                    effort = conversationReasoningEffort,
                    onEffortChange = onSetReasoningEffort,
                    onPreviewValueChange = {
                        previewValue = it
                        onPreviewTierChange(reasoningEffortTier(it.roundToInt(), effortOptions))
                    },
                    options = effortOptions,
                    onColorBurst = { origin, tier ->
                        if (tier == 6) {
                            screenBurst?.start(
                                origin = origin,
                                color = Color(0xFFDC2626),
                                durationMillis = 6500,
                                keepAfterCompletion = true,
                                starCount = 180
                            )
                        } else {
                            screenBurst?.retract(origin)
                        }
                    }
                )
            }

            Box(Modifier.fillMaxWidth().testTag("selected-model-card")) {
                ModelPickerCard(
                    modelId = modelId,
                    config = config,
                    isSelected = true,
                    providerName = provider?.name,
                    folderName = folderName,
                    conversationReasoningEffort = conversationReasoningEffort,
                    onSetReasoningEffort = onSetReasoningEffort,
                    showReasoningEffort = false,
                    embedded = true,
                    drawBackground = false,
                    onClick = {
                        screenBurst?.hide()
                        onOpenModelOptions()
                    },
                    trailingContent = {
                        SharedIcon(Icons.Outlined.ChevronRight, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                    },
                    modifier = Modifier.testTag("selected-model-card-action")
                )
            }
        }
    }
}
