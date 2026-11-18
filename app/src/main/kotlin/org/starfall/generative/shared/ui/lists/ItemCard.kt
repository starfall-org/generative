package org.starfall.generative.shared.ui.lists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.ui.layout.AdaptiveCardLayout
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

/** The common list/grid card used by the app's entity collections. */
@Composable
fun ItemCard(
    isGrid: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    color: Color = prominentContainerColor(),
    border: BorderStroke? = prominentContainerBorder(1.5.dp),
    icon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    ItemSurface(isGrid = isGrid, onClick = onClick, modifier = modifier,
        onLongClick = onLongClick, color = color, border = border) {
        AdaptiveCardLayout(
            isGrid = isGrid,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            icon = { Box { icon() } },
            actions = { Box { actions() } },
            content = { Box { content() } }
        )
    }
}
