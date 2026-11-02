package org.starfall.generative.features.settings.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.starfall.generative.extensions.launcher.Launcher
import org.starfall.generative.shared.ui.lists.LauncherCollection

/** Demo adapter. All folder geometry, animation and scroll behavior lives in Launcher. */
@Composable
internal fun LauncherDragTestCollection(
    items: List<String>,
    state: Launcher.State<String>,
    options: LauncherDragTestOptions,
    modifier: Modifier,
    padding: Dp,
    enabled: Boolean,
    item: @Composable (String, Modifier, Rect?) -> Unit
) {
    LauncherCollection(
        items = items,
        key = { it },
        state = state,
        columns = if (options.list) null else options.columns,
        modifier = modifier,
        contentPadding = PaddingValues(padding),
        horizontalSpacing = 10.dp,
        verticalSpacing = 10.dp,
        placementDurationMillis = Launcher.defaults.PlacementDurationMillis,
        enabled = enabled,
        content = item
    )
}
