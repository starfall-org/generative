package org.starfall.generative.shared.subfeatures.modelpicker.ui

import org.starfall.generative.shared.subfeatures.modelpicker.logic.compactModelLabel
import org.starfall.generative.shared.subfeatures.modelpicker.logic.modelInitial

import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.modelpicker.ui.LocalThinkingScreenBurst
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ReasoningEffortControl
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ThinkingScreenBurstLayer
import org.starfall.generative.shared.subfeatures.modelpicker.ui.localizedReasoningEffortLabels
import org.starfall.generative.shared.subfeatures.modelpicker.ui.maximumEffortCardBrush
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortBurstDuration
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortIndex
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortOptions
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortTier
import org.starfall.generative.shared.subfeatures.modelpicker.ui.rememberThinkingColorBurst
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.DEFAULT_CONTEXT_WINDOW_TOKENS
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.language.passbackMode
import org.starfall.generative.shared.model.language.supportedReasoningEfforts
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.sheets.BottomSheet
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.theme.LocalAmoledMode
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.subfeatures.icons.ui.providerInitials
import org.starfall.generative.shared.subfeatures.modelpicker.ui.revealThinkingColor
import org.starfall.generative.shared.subfeatures.modelpicker.ui.drawMaximumEffortStreaks

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ModelPickerCard(
    modelId: String,
    config: ModelConfiguration,
    isSelected: Boolean,
    providerName: String? = null,
    folderName: String? = null,
    conversationReasoningEffort: String?,
    onSetReasoningEffort: (String?) -> Unit,
    showReasoningEffort: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    onPreviewTierChange: (Int) -> Unit = {},
    embedded: Boolean = false,
    drawBackground: Boolean = true,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val supportedEfforts = config.supportedReasoningEfforts
    val reasoningLabels = localizedReasoningEffortLabels()
    val effortOptions = remember(supportedEfforts, reasoningLabels) {
        reasoningEffortOptions(supportedEfforts, reasoningLabels)
    }
    var previewReasoningValue by remember(conversationReasoningEffort, isSelected, effortOptions) {
        mutableFloatStateOf(reasoningEffortIndex(conversationReasoningEffort, effortOptions).toFloat())
    }
    val tier = reasoningEffortTier(previewReasoningValue.roundToInt(), effortOptions)
    val enhancedThinking = isSelected && showReasoningEffort && config.supportsThinking && tier == 6
    val cardBurst = rememberThinkingColorBurst()
    val cardTint by animateFloatAsState(
        if (enhancedThinking || cardBurst.isRetracting) 1f else 0f,
        tween(1800, easing = FastOutSlowInEasing),
        label = "thinkingCardTint"
    )
    val cardShape = RoundedCornerShape(12.dp)
    val embeddedShape = RoundedCornerShape(12.dp)
    val amoled = LocalAmoledMode.current
    val cardBase = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
        else prominentContainerColor()
    val cardBrush = maximumEffortCardBrush()
    val screenBurst = LocalThinkingScreenBurst.current
    var cardOrigin by remember { mutableStateOf(Offset.Zero) }
    val cardContentColor = if (!embedded && enhancedThinking) Color.White else MaterialTheme.colorScheme.onSurface
    val cardGesture = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    val cardModifier = modifier.fillMaxWidth().then(
        if (embedded) Modifier else Modifier.clip(cardShape).then(cardGesture)
    )
    val cardContentModifier = Modifier
        .fillMaxWidth()
        .onGloballyPositioned { cardOrigin = it.localToScreen(Offset.Zero) }
        .then(
            if (drawBackground) Modifier.drawBehind {
                revealThinkingColor(cardBurst) {
                    if (tier == 6 || cardBurst.isRetracting) {
                        drawRect(cardBrush, alpha = cardTint)
                        drawMaximumEffortStreaks(cardTint)
                    }
                }
            } else Modifier
        )
        .padding(if (embedded) 10.dp else 7.dp)
    val cardContent: @Composable ColumnScope.() -> Unit = {
            Row(
                modifier = Modifier.fillMaxWidth().then(if (embedded) {
                    Modifier
                        .clip(embeddedShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .then(
                            if (amoled && !(enhancedThinking)) {
                                Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, embeddedShape)
                            } else Modifier
                        )
                        .then(cardGesture)
                        .padding(10.dp)
                } else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EntityIcon(config.icon, Modifier.size(if (embedded) 40.dp else 32.dp), text = modelInitial(modelId), matchName = config.displayName.ifBlank { modelId }, fallbackMatchName = modelId, model = true)

                Spacer(Modifier.width(if (embedded) 10.dp else 8.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (embedded) 4.dp else 2.dp)
                ) {
                    Text(
                        text = compactModelLabel(config.displayName.ifBlank { modelId }),
                        style = (if (embedded) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                            .copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (config.displayName.isNotBlank() && config.displayName != modelId) {
                        Text(
                            text = stringResource(R.string.model_label_id, compactModelLabel(modelId)),
                            style = MaterialTheme.typography.labelSmall,
                             color = if (!embedded && enhancedThinking) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!providerName.isNullOrBlank() || !folderName.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            providerName?.takeIf { it.isNotBlank() }?.let {
                                ModelContextChip(
                                    text = it,
                                    modifier = Modifier.weight(1f),
                                    containerAlpha = if (embedded) 0.5f else 0.78f,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            folderName?.takeIf { it.isNotBlank() }?.let {
                                ModelContextChip(
                                    text = stringResource(R.string.model_label_group, it),
                                    modifier = Modifier.weight(1f),
                                    containerAlpha = if (embedded) 0.5f else 0.78f,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    ModelCapabilityBadges(
                        config,
                        modifier = if (embedded) Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            .padding(3.dp)
                        else Modifier
                    )
                }

                if (isSelected) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                         color = MaterialTheme.colorScheme.primary.copy(alpha = if (embedded) 0.5f else 1f),
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            SharedIcon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.common_selected),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                trailingContent?.let {
                    Spacer(Modifier.width(6.dp))
                    it()
                }
            }

            // Off is the lowest slider level, so the slider stays visible whenever the model supports thinking.
            if (showReasoningEffort && isSelected && config.supportsThinking) {
                val activeLabel = effortOptions[previewReasoningValue.roundToInt().coerceIn(effortOptions.indices)].label

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SharedIcon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.reasoning_effort_title),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (!embedded && enhancedThinking) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = activeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                ReasoningEffortControl(
                    effort = conversationReasoningEffort,
                    onEffortChange = onSetReasoningEffort,
                    onPreviewValueChange = {
                        previewReasoningValue = it
                        onPreviewTierChange(reasoningEffortTier(it.roundToInt(), effortOptions))
                    },
                    options = effortOptions,
                    onColorBurst = { origin, selectedTier ->
                        if (selectedTier == 6) {
                            cardBurst.start(origin - cardOrigin, Color(0xFFDC2626), reasoningEffortBurstDuration(6))
                            screenBurst?.start(
                                origin = origin,
                                color = Color(0xFFDC2626),
                                durationMillis = 6500,
                                keepAfterCompletion = true,
                                starCount = 180
                            )
                        } else {
                            cardBurst.retract(origin - cardOrigin)
                            screenBurst?.retract(origin)
                        }
                    }
                )
            }
    }
    CompositionLocalProvider(LocalContentColor provides cardContentColor) {
        if (embedded) {
            Column(modifier = cardContentModifier, content = cardContent)
        } else {
            Surface(
                modifier = cardModifier,
                shape = cardShape,
                color = cardBase,
                contentColor = cardContentColor,
                border = if (isSelected) {
                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                } else {
                    prominentContainerBorder()
                }
            ) {
                Column(modifier = cardContentModifier, content = cardContent)
            }
        }
    }
}

@Composable
private fun ModelContextChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color,
    contentColor: Color,
    containerAlpha: Float = 0.78f
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = containerColor.copy(alpha = containerAlpha),
        contentColor = contentColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ModelCapabilityBadges(config: ModelConfiguration, modifier: Modifier = Modifier) {
    val badgeColors = AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ModelBadge(
            label = when (config.modelType) {
                ModelType.TEXT_GENERATION -> stringResource(R.string.model_type_chat)
                ModelType.IMAGE_GENERATION -> stringResource(R.string.common_image)
                ModelType.VIDEO_GENERATION -> stringResource(R.string.common_video)
                ModelType.TEXT_TO_SPEECH -> stringResource(R.string.model_type_tts)
                ModelType.SPEECH_TO_TEXT -> stringResource(R.string.model_type_stt)
                else -> config.modelType.displayName
            },
            icon = when (config.modelType) {
                ModelType.TEXT_GENERATION -> Icons.Outlined.ChatBubbleOutline
                ModelType.IMAGE_GENERATION -> Icons.Outlined.Image
                ModelType.VIDEO_GENERATION -> Icons.Outlined.Videocam
                ModelType.TEXT_TO_SPEECH, ModelType.SPEECH_TO_TEXT -> Icons.Outlined.Audiotrack
                ModelType.EMBEDDING, ModelType.RERANKING -> null
                ModelType.REALTIME -> Icons.Outlined.ChatBubbleOutline
            },
            colors = badgeColors
        )
        if (config.modelType != ModelType.TEXT_GENERATION) return@Row
        ModelBadge(
            label = compactContextWindow(config.contextWindowTokens),
            colors = badgeColors
        )
        if (config.supportsVision) {
            ModelBadge(
                label = stringResource(R.string.common_image),
                iconOnly = true,
                icon = Icons.Outlined.Image,
                colors = badgeColors
            )
        }
        if (config.supportsVideoInput) {
            ModelBadge(
                label = stringResource(R.string.common_video),
                iconOnly = true,
                icon = Icons.Outlined.Videocam,
                colors = badgeColors
            )
        }
        if (config.supportsAudioInput) {
            ModelBadge(
                label = stringResource(R.string.common_audio),
                iconOnly = true,
                icon = Icons.Outlined.Audiotrack,
                colors = badgeColors
            )
        }
        if (config.supportsThinking) {
            ModelBadge(
                label = stringResource(R.string.thinking),
                iconOnly = true,
                icon = Icons.Outlined.Psychology,
                colors = badgeColors
            )
        }
        if (config.supportsToolCalls) {
            ModelBadge(
                label = stringResource(R.string.common_tools),
                iconOnly = true,
                icon = Icons.Outlined.Build,
                colors = badgeColors
            )
        }
    }
}

private fun compactContextWindow(tokens: Int): String {
    val count = tokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
    val divisor = when {
        count >= 1_000_000 -> 1_000_000
        count >= 1_000 -> 1_000
        else -> return count.toString()
    }
    val value = java.math.BigDecimal(count).divide(java.math.BigDecimal(divisor), 1, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
    return value + if (divisor == 1_000_000) "M" else "k"
}

@Composable
private fun ModelBadge(
    label: String,
    icon: ImageVector? = null,
    iconOnly: Boolean = false,
    colors: ChipColors = AssistChipDefaults.assistChipColors()
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.containerColor,
        contentColor = colors.labelColor,
        modifier = Modifier.height(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            icon?.let { SharedIcon(it, contentDescription = if (iconOnly) label else null, modifier = Modifier.size(12.dp)) }
            if (!iconOnly) Text(label, fontSize = 10.sp)
        }
    }
}

@Composable
internal fun ProviderMark(
    provider: ModelProvider,
    compact: Boolean = false
) {
    val mark = providerInitials(provider.name)
    EntityIcon(provider.icon, Modifier.size(if (compact) 18.dp else 42.dp), text = mark, matchName = provider.name)
}
