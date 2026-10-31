package org.starfall.generative.shared.ui.lists

import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

@Composable
fun ListViewToggle(isGrid: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier) {
        SharedIcon(
            if (isGrid) Icons.Default.List else Icons.Default.GridView,
            stringResource(if (isGrid) R.string.switch_to_list_view else R.string.switch_to_grid_view)
        )
    }
}
