package org.starfall.generative.shared.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A list/grid layout only. Item identity, ordering, gestures and persistence belong to its caller. */
@Composable
fun EntityGrid(
    isGrid: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier.fillMaxSize(),
    state: LazyGridState = rememberLazyGridState(),
    spacing: Dp = 0.dp,
    content: LazyGridScope.() -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (isGrid) 2 else 1),
        state = state,
        contentPadding = contentPadding,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}
