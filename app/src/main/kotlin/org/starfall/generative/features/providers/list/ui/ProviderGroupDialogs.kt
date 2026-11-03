package org.starfall.generative.features.providers.list.ui

import org.starfall.generative.shared.ui.sheets.ContextMenuAction
import org.starfall.generative.shared.ui.sheets.ContextMenuSheet
import org.starfall.generative.features.providers.list.logic.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.EntityIcon
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.IconPickerRow
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.text.OutlinedTextField
import org.starfall.generative.shared.ui.layout.windowHeightIn
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

private const val UNGROUPED_SECTION = "__ungrouped__"

@Composable
internal fun ProviderGroupContextMenu(onEdit: () -> Unit, onDelete: () -> Unit, compact: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = if (compact) Modifier.size(36.dp) else Modifier
        ) {
            SharedIcon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.common_more_options),
                modifier = if (compact) Modifier.size(20.dp) else Modifier
            )
        }
        if (expanded) ContextMenuSheet(
            actions = listOf(
                ContextMenuAction(stringResource(R.string.common_edit), onEdit, Icons.Outlined.Edit),
                ContextMenuAction(stringResource(R.string.delete_group), onDelete, Icons.Outlined.Delete, destructive = true)
            ),
            onDismissRequest = { expanded = false }
        )
    }
}

@Composable
internal fun ProviderGroupNameDialog(
    title: String,
    initialName: String,
    initialIcon: String?,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
    onSaveIcon: (String, String?) -> Unit = { _, _ -> }
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var icon by remember(initialIcon) { mutableStateOf(initialIcon) }
    var iconImporting by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconPickerRow(
                    image = icon,
                    onChange = { icon = it; onSaveIcon(trimmed, it) },
                    fallback = Icons.Outlined.Folder,
                    onBusyChange = { iconImporting = it },
                    matchName = trimmed.ifBlank { name }
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.provider_group_name)) },
                    supportingText = {
                        if (name.isNotEmpty() && trimmed.isEmpty()) {
                            Text(stringResource(R.string.provider_group_name_required))
                        }
                    },
                    isError = name.isNotEmpty() && trimmed.isEmpty(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmAtRight = true,
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !iconImporting,
                onClick = { onSave(trimmed, icon) }
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

@Composable
internal fun ProviderGroupPickerDialog(
    provider: ModelProvider,
    groups: List<ProviderGroup>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_group)) },
        scrollableContent = false,
        text = {
            LazyColumn(Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.6f)) {
                item(key = UNGROUPED_SECTION) {
                    ProviderGroupChoiceCard(
                        title = stringResource(R.string.ungrouped),
                        icon = null,
                        selected = provider.groupId == null,
                        onClick = { onSelect(null) }
                    )
                }
                items(groups, key = { it.id }) { group ->
                    ProviderGroupChoiceCard(
                        title = group.name,
                        icon = group.icon,
                        selected = provider.groupId == group.id,
                        onClick = { onSelect(group.id) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

@Composable
private fun ProviderGroupChoiceCard(
    title: String,
    icon: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
            else prominentContainerColor(),
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            prominentContainerBorder()
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntityIcon(
                image = icon,
                modifier = Modifier.size(44.dp),
                text = title.take(2).uppercase(),
                fallback = Icons.Outlined.Folder,
                matchName = title
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                SharedIcon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Confirmation shown before a folder is deleted; its providers move back to the root. */
@Composable
internal fun ProviderDeleteGroupDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_provider_group_title)) },
        text = { Text(stringResource(R.string.delete_provider_group_message)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

/** Confirmation shown before a provider is deleted. */
@Composable
internal fun ProviderDeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_provider_title)) },
        text = { Text(stringResource(R.string.delete_provider_message)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}
