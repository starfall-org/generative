package org.starfall.generative.shared.subfeatures.modelpicker.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.generative.R
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.theme.LocalAmoledMode
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

/** Muted divider between the folder sections in the shared picker list. */
@Composable
internal fun ModelPickerSection(label: String, tag: String) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModelPickerDashedLine(Modifier.weight(1f), lineColor)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ModelPickerDashedLine(Modifier.weight(1f), lineColor)
    }
}

@Composable
private fun ModelPickerDashedLine(modifier: Modifier, color: Color) {
    Canvas(modifier.height(1.dp)) {
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ModelPickerProviderRow(
    provider: ModelProvider,
    collapsed: Boolean,
    hasSelectedModel: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit
) {
    val rowShape = RoundedCornerShape(12.dp)
    val amoledSelected = LocalAmoledMode.current && hasSelectedModel
    Surface(
        shape = rowShape,
        color = if (hasSelectedModel && !amoledSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            Color.Transparent
        },
        contentColor = if (hasSelectedModel) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (amoledSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            )
            .testTag("model-picker-provider_${provider.id}")
            .clip(rowShape)
            .combinedClickable(onClick = onToggle, onLongClick = onEdit)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SharedIcon(
                if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (collapsed) R.string.expand_group else R.string.collapse_group, provider.name),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            ProviderMark(
                provider = provider,
                compact = true
            )
            Spacer(Modifier.width(6.dp))
            Text(
                provider.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                 color = if (hasSelectedModel) MaterialTheme.colorScheme.onPrimaryContainer
                     else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                 fontWeight = FontWeight.Medium
             )
             if (hasSelectedModel) {
                 SharedIcon(
                     Icons.Filled.Check,
                     contentDescription = null,
                     tint = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.size(16.dp)
                 )
             }
        }
    }
}
