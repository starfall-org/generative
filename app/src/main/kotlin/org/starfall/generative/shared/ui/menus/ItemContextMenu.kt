package org.starfall.generative.shared.ui.menus
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.sheets.ContextMenuAction
import org.starfall.generative.shared.ui.sheets.ContextMenuSheet

@Composable
fun ItemContextMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    deleteColor: Color,
    firstActionText: String? = null,
    firstActionIcon: Int? = null,
    firstActionImageVector: ImageVector? = null,
    onFirstAction: (() -> Unit)? = null,
    enabled: Boolean = true,
    showEdit: Boolean = true,
    showDelete: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(enabled = enabled, onClick = { expanded = true }, modifier = modifier) {
            SharedIcon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more_options))
        }
        if (expanded) {
            ContextMenuSheet(
                actions = buildList {
                    if (firstActionText != null && onFirstAction != null &&
                        (firstActionIcon != null || firstActionImageVector != null)
                    ) {
                        add(
                            ContextMenuAction(
                                label = firstActionText,
                                onClick = onFirstAction,
                                icon = firstActionImageVector,
                                iconRes = firstActionIcon
                            )
                        )
                    }
                    if (showEdit) add(
                        ContextMenuAction(
                            label = stringResource(R.string.common_edit),
                            onClick = onEdit,
                            icon = Icons.Outlined.Edit
                        )
                    )
                    if (showDelete) add(
                        ContextMenuAction(
                            label = stringResource(R.string.common_delete),
                            onClick = onDelete,
                            icon = Icons.Outlined.Delete,
                            destructive = true,
                            destructiveColor = deleteColor
                        )
                    )
                },
                onDismissRequest = { expanded = false }
            )
        }
    }
}
