package org.starfall.generative.shared.subfeatures.explorer.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.menus.DropdownMenuItem

/** One file or folder entry, rendered with the app's shared [ItemCard] list/grid card. */
@Composable
internal fun ExplorerItemCard(
    item: ExplorerItem,
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownload: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    val itemKey = "${item.location.sourceId}:${item.location.path}"
    // Source entries on the explorer home are navigation roots, not editable files.
    val manageable = item.location.path.isNotEmpty()

    ItemCard(
        isGrid = isGrid,
        onClick = onOpen,
        modifier = modifier.testTag("explorer-item:$itemKey"),
        onLongClick = if (manageable) ({ menuOpen = true }) else null,
        icon = { ExplorerItemIcon(item = item, isGrid = isGrid) },
        actions = {
            if (manageable) {
                ExplorerItemMenu(
                    itemName = item.name,
                    itemKey = itemKey,
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = it },
                    onRename = onRename,
                    onDelete = onDelete,
                    onDownload = onDownload
                )
            }
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = if (isGrid) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    explorerItemSubtitle(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

@Composable
private fun ExplorerItemIcon(item: ExplorerItem, isGrid: Boolean) {
    SharedIcon(
        explorerItemIcon(item),
        contentDescription = null,
        tint = if (item.directory) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(if (isGrid) 40.dp else 28.dp)
    )
}

@Composable
private fun explorerItemSubtitle(item: ExplorerItem): String {
    if (item.directory) return stringResource(R.string.explorer_folder)
    return Formatter.formatFileSize(LocalContext.current, item.size)
}

@Composable
private fun ExplorerItemMenu(
    itemName: String,
    itemKey: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownload: (() -> Unit)?
) {
    Box {
        IconButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.testTag("explorer-menu:$itemKey")
        ) {
            SharedIcon(
                Icons.Default.MoreVert,
                stringResource(R.string.explorer_more_actions, itemName)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            if (onDownload != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.explorer_download)) },
                    leadingIcon = { SharedIcon(Icons.Outlined.Download, contentDescription = null) },
                    onClick = {
                        onExpandedChange(false)
                        onDownload()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.explorer_rename)) },
                leadingIcon = { SharedIcon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    onExpandedChange(false)
                    onRename()
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                leadingIcon = {
                    SharedIcon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    onExpandedChange(false)
                    onDelete()
                }
            )
        }
    }
}

/** Icons follow the entry kind so the list stays scannable without opening files. */
private fun explorerItemIcon(item: ExplorerItem): ImageVector {
    if (item.directory) return Icons.Outlined.Folder
    return when (item.name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "svg" -> Icons.Outlined.Image
        "mp4", "mkv", "mov", "webm", "avi", "m4v" -> Icons.Outlined.Videocam
        "mp3", "wav", "m4a", "ogg", "flac", "opus", "aac" -> Icons.Outlined.Audiotrack
        "kt", "kts", "java", "py", "js", "ts", "tsx", "json", "xml", "html", "css",
        "sh", "yaml", "yml", "toml", "c", "cpp", "h", "rs", "go", "sql", "gradle" -> Icons.Outlined.Code
        "md", "markdown", "txt", "log", "csv" -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}
