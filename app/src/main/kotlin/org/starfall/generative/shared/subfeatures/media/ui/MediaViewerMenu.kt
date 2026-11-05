package org.starfall.generative.shared.subfeatures.media.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.ContextMenuAction
import org.starfall.generative.shared.ui.menus.ContextDropdownMenu

internal data class MediaViewerAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val tint: Color? = null
)

@Composable
internal fun MediaViewerMenu(
    actions: List<MediaViewerAction>,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.align(AbsoluteAlignment.TopRight),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ViewerOverlayButton {
                IconButton(onClick = onDismiss) {
                    SharedIcon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.media_close_viewer),
                        tint = Color.White
                    )
                }
            }
            Box {
                ViewerOverlayButton {
                    IconButton(onClick = { expanded = true }) {
                        SharedIcon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.common_more_options),
                            tint = Color.White
                        )
                    }
                }
                if (expanded) {
                    ContextDropdownMenu(
                        expanded = true,
                        actions = actions.map { action ->
                            ContextMenuAction(
                                label = action.label,
                                onClick = action.onClick,
                                icon = action.icon,
                                destructive = action.tint != null,
                                destructiveColor = action.tint
                            )
                        },
                        onDismissRequest = { expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerOverlayButton(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.48f),
        content = content
    )
}
