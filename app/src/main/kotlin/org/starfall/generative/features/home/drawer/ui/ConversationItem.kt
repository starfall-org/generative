@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.starfall.generative.features.home.drawer.ui

import org.starfall.generative.shared.ui.menus.ContextMenuAction
import org.starfall.generative.shared.ui.menus.ContextDropdownMenu
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.home.drawer.logic.ConversationModelIcon
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.ui.theme.LocalAmoledMode
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.subfeatures.modelpicker.logic.modelInitial

@Composable
internal fun ConversationItem(
    chat: ConversationListItem, currentId: String?, generatingIds: Set<String>, selecting: Boolean, checked: Boolean, pinned: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onSelect: () -> Unit,
    onPin: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onMove: () -> Unit,
    showContextMenu: Boolean, onContextMenuDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    modelIcon: ConversationModelIcon? = null
) {
    val menu = showContextMenu
    val active = chat.id == currentId
    val isGenerating = chat.id in generatingIds
    // AMOLED: the open chat is outlined with a faint border instead of a tinted fill.
    val outlineActive = LocalAmoledMode.current && active && !checked
    val itemShape = RoundedCornerShape(12.dp)
    Surface(shape = itemShape,
        color = if (!outlineActive && (checked || active)) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        border = if (outlineActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)) else null,
        modifier = modifier
            .fillMaxWidth()
            .clip(itemShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ConversationLeadingIcon(
                chat = chat,
                active = active,
                isGenerating = isGenerating,
                modelIcon = modelIcon,
                onLongClick = if (selecting) onClick else onLongClick
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(chat.title.ifBlank { stringResource(R.string.conversation_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(formatTimestamp(chat.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (pinned) SharedIcon(Icons.Default.PushPin, stringResource(R.string.conversation_pinned), Modifier.padding(end = 10.dp).size(14.dp))
            if (selecting) {
                Checkbox(
                    checked,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(36.dp).testTag("conversation_select_checkbox_${chat.id}")
                )
            } else {
                if (menu) ContextDropdownMenu(
                    expanded = true,
                    actions = listOf(
                        ContextMenuAction(stringResource(R.string.sidebar_select), onSelect, Icons.Outlined.CheckCircle),
                        ContextMenuAction(stringResource(if (pinned) R.string.sidebar_unpin else R.string.sidebar_pin), onPin, Icons.Outlined.PushPin),
                        ContextMenuAction(stringResource(R.string.sidebar_move_to_folder), onMove, Icons.Outlined.Folder),
                        ContextMenuAction(stringResource(R.string.sidebar_rename), onRename, Icons.Outlined.Edit),
                        ContextMenuAction(stringResource(R.string.common_delete), onDelete, Icons.Outlined.Delete, destructive = true)
                    ),
                    onDismissRequest = onContextMenuDismiss
                )
            }
        }
    }
}

@Composable
private fun ConversationLeadingIcon(
    chat: ConversationListItem,
    active: Boolean,
    isGenerating: Boolean,
    modelIcon: ConversationModelIcon?,
    onLongClick: () -> Unit
) {
    val iconInset by animateDpAsState(
        targetValue = if (isGenerating) 2.75.dp else 0.dp,
        label = "conversationGeneratingIconInset"
    )
    val ringRotation = if (isGenerating) {
        val transition = rememberInfiniteTransition(label = "conversationGeneratingRing")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 950, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "conversationGeneratingAngle"
        )
        angle
    } else {
        0f
    }
    val ringColor = MaterialTheme.colorScheme.primary
    val ringTrackColor = ringColor.copy(alpha = 0.22f)

    Box(
        modifier = Modifier
            .size(36.dp)
            .testTag(
                if (isGenerating) "conversation_generating_icon_${chat.id}"
                else "conversation_icon_${chat.id}"
            )
            .clip(CircleShape)
            .combinedClickable(onClick = onLongClick, onLongClick = onLongClick)
            .drawWithContent {
                drawContent()
                if (isGenerating) {
                    val strokePx = 2.dp.toPx()
                    val halfStroke = strokePx / 2f
                    val arcSize = Size(size.width - strokePx, size.height - strokePx)
                    val topLeft = Offset(halfStroke, halfStroke)
                    drawArc(
                        color = ringTrackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx)
                    )
                    drawArc(
                        color = ringColor,
                        startAngle = ringRotation - 90f,
                        sweepAngle = 105f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            }
            .padding(iconInset),
        contentAlignment = Alignment.Center
    ) {
        if (modelIcon != null) {
            EntityIcon(
                image = modelIcon.image,
                modifier = Modifier.fillMaxSize(),
                text = modelInitial(modelIcon.name),
                matchName = modelIcon.name,
                fallbackMatchName = modelIcon.id,
                model = true,
                backgroundColor = when {
                    active -> MaterialTheme.colorScheme.primaryContainer
                    isGenerating -> MaterialTheme.colorScheme.surfaceContainerHighest
                    else -> null
                },
                shape = CircleShape
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    .then(
                        if (!active && !isGenerating && LocalAmoledMode.current) {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chat.title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#",
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
@Composable
private fun formatTimestamp(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp) / 60000).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        minutes < 60 -> stringResource(R.string.time_minutes_ago, minutes)
        minutes < 1440 -> stringResource(R.string.time_hours_ago, minutes / 60)
        minutes < 10080 -> stringResource(R.string.time_days_ago, minutes / 1440)
        else -> stringResource(R.string.time_weeks_ago, minutes / 10080)
    }
}
