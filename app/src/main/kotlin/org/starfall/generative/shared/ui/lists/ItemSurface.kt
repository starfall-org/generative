package org.starfall.generative.shared.ui.lists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

/** Shared card surface for entity items, including expandable items with custom content. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemSurface(
    isGrid: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    color: Color = prominentContainerColor(),
    border: BorderStroke? = prominentContainerBorder(1.5.dp),
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(if (isGrid) 20.dp else 16.dp)
    if (onClick == null && onLongClick == null) {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border, content = content)
    } else if (onLongClick == null && onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape,
            color = color, border = border, content = content)
    } else {
        Surface(modifier = modifier.fillMaxWidth().clip(shape).combinedClickable(
            onClick = onClick ?: {}, onLongClick = onLongClick
        ), shape = shape, color = color, border = border, content = content)
    }
}
