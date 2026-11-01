package org.starfall.generative.features.home.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import org.starfall.generative.R
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.SummaryRole
import org.starfall.generative.shared.model.generation.hasToolCalls
import org.starfall.generative.shared.model.language.DEFAULT_CONTEXT_WINDOW_TOKENS
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.sendsThinkingBack
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.TemporaryChatIcon
import org.starfall.generative.shared.ui.FeaturesLogo
import org.starfall.generative.shared.ui.LinearWavyProgressIndicator
import org.starfall.generative.features.home.chat.model.ContextWindowStatus
/** Themed scrim over scrolling content: 60% at the status bar, fully clear at the bottom. */
internal fun chatAppBarOverlayAlpha(fractionFromTop: Float): Float =
    0.6f * (1f - fractionFromTop.coerceIn(0f, 1f))

@Composable
fun ChatAppBar(
    modelName: String,
    providerName: String,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    featuresLogoImage: String? = null,
    isTemporaryChat: Boolean = false,
    onOpenTemporaryChat: () -> Unit = onNewChat,
    onOpenModelPicker: () -> Unit = {}
) {
    val statusBarHeight = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    val displayedModelName = modelName.substringAfterLast('/').ifBlank { modelName }

    Box(
        modifier = modifier
            // The scrim must be above the scrolling content, but below these app-bar controls.
            .zIndex(1f)
            .testTag("chat-app-bar")
            .fillMaxWidth()
            .height(72.dp + statusBarHeight)
            // Use the actual themed chat background: light in light theme, dark in dark theme.
            // Fade only this overlay, never individual messages, text, or media blocks.
            .background(Brush.verticalGradient(
                0f to MaterialTheme.colorScheme.background.copy(alpha = chatAppBarOverlayAlpha(0f)),
                1f to MaterialTheme.colorScheme.background.copy(alpha = chatAppBarOverlayAlpha(1f))
            ))
            .padding(top = statusBarHeight)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Icon-only actions: no extra background or raised surface around the glyphs.
        // IconButton retains an accessible tap target while the visual size stays compact.
        IconButton(
            onClick = onOpenDrawer,
            modifier = Modifier.align(Alignment.CenterStart).size(44.dp)
        ) {
            SharedIcon(
                imageVector = Icons.Default.Menu,
                contentDescription = stringResource(R.string.chat_open_side_navigation),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(start = 64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenModelPicker)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = displayedModelName.ifBlank { stringResource(R.string.select_model) },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (providerName.isNotBlank()) {
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = if (isTemporaryChat) onOpenTemporaryChat else onNewChat,
                    modifier = Modifier.size(44.dp)
                ) {
                    if (isTemporaryChat) {
                        TemporaryChatIcon(
                            contentDescription = stringResource(R.string.chat_open_temporary_chat),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        SharedIcon(
                            imageVector = Icons.Outlined.Compose,
                            contentDescription = stringResource(R.string.sidebar_new_chat),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                val appMenuLabel = stringResource(R.string.chat_open_app_menu)
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(44.dp)
                    .semantics { contentDescription = appMenuLabel }) {
                    FeaturesLogo(featuresLogoImage, Modifier.size(32.dp).clip(CircleShape))
                }
            }
        }
    }
}

/** Severity color shared by the model button ring and the context bar in the model sheet. */
@Composable
private fun contextWindowColor(status: ContextWindowStatus): Color = when {
    status.percentage >= 90 -> MaterialTheme.colorScheme.error
    status.percentage >= 75 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

/** Thin progress ring drawn around the model icon; the exact usage is shown in the model sheet. */
@Composable
internal fun ContextWindowRing(status: ContextWindowStatus, modifier: Modifier = Modifier) {
    val progress = (status.usedTokens.toFloat() / status.limit.coerceAtLeast(1)).coerceIn(0f, 1f)
    val color = contextWindowColor(status)
    CircularProgressIndicator(
        progress = { progress },
        modifier = modifier,
        color = color,
        trackColor = color.copy(alpha = 0.18f),
        strokeWidth = 3.dp
    )
}

/**
 * Horizontal context window indicator shown above the model card in the model sheet.
 * [onDarkBackground] is set while the sheet wears the dark maximum-effort treatment.
 */
@Composable
internal fun ContextWindowBar(
    status: ContextWindowStatus,
    onDarkBackground: Boolean = false,
    modifier: Modifier = Modifier
) {
    val color = if (onDarkBackground) Color.White else contextWindowColor(status)
    val titleColor = if (onDarkBackground) Color.White.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant
    val detailColor = if (onDarkBackground) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.fillMaxWidth().testTag("context-window-bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.chat_context_window),
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(
                    R.string.chat_context_window_tokens,
                    formatContextTokens(status.usedTokens),
                    formatContextTokens(status.limit.toLong())
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = detailColor
            )
        }
        LinearWavyProgressIndicator(
            progress = { (status.usedTokens.toFloat() / status.limit.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = color,
            trackColor = color.copy(alpha = 0.18f)
        )
        Text(
            text = stringResource(when {
                status.apiUsage == null -> R.string.context_window_source_estimated
                status.estimatedAdditionalTokens > 0 -> R.string.context_window_source_mixed
                else -> R.string.context_window_source_api
            }),
            style = MaterialTheme.typography.labelSmall,
            color = detailColor
        )
        status.apiUsage?.let { usage ->
            Text(
                text = if (usage.outputTokens != null) stringResource(R.string.context_window_api_breakdown,
                    formatContextTokens(usage.inputTokens), formatContextTokens(usage.outputTokens))
                else stringResource(R.string.context_window_api_input_only, formatContextTokens(usage.inputTokens)),
                style = MaterialTheme.typography.labelSmall,
                color = detailColor
            )
        }
    }
}

internal fun formatContextTokens(tokens: Long): String =
    String.format(Locale.US, "%,d", tokens.coerceAtLeast(0L))
