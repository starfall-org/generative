package org.starfall.generative.features.providers.list.ui

import org.starfall.generative.shared.ui.menus.ContextMenuAction
import org.starfall.generative.shared.ui.menus.ContextDropdownMenu
import org.starfall.generative.features.providers.list.logic.*

import org.starfall.generative.features.providers.list.logic.ProviderRootItem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.generative.R
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.subfeatures.icons.ui.providerInitials

private val ProviderListCardHeight = 88.dp
private val ProviderGridCardHeight = 152.dp

/** One root tile: a provider card or a folder card. Shared by the grid cell and the drag ghost. */
@Composable
internal fun ProviderRootCard(
    item: ProviderRootItem,
    members: List<ModelProvider>,
    isGrid: Boolean,
    isDropTarget: Boolean,
    testTag: Boolean = true,
    onEditProvider: (ModelProvider) -> Unit,
    onMoveProvider: (ModelProvider) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSetProviderHidden: (String, Boolean) -> Unit = { _, _ -> },
    onOpenGroup: (String) -> Unit
) {
    when (item) {
        is ProviderRootItem.ProviderItem -> ProviderUnifiedCard(
            provider = item.provider,
            isGrid = isGrid,
            modifier = Modifier.then(if (testTag) Modifier.testTag("provider_${item.provider.id}") else Modifier),
            onEdit = { onEditProvider(item.provider) },
            onMoveToGroup = { onMoveProvider(item.provider) },
            onDelete = { onDeleteProvider(item.provider.id) },
            onToggleHidden = { onSetProviderHidden(item.provider.id, !item.provider.isHidden) }
        )
        is ProviderRootItem.GroupItem -> ProviderGroupCollapsedCard(
            group = item.group,
            isGrid = isGrid,
            providers = members,
            isDropTarget = isDropTarget,
            modifier = Modifier.then(if (testTag) Modifier.testTag("provider_group_${item.group.id}") else Modifier),
            onOpen = { onOpenGroup(item.group.id) }
        )
    }
}

@Composable
private fun ProviderGroupCollapsedCard(
    group: ProviderGroup,
    isGrid: Boolean,
    providers: List<ModelProvider>,
    isDropTarget: Boolean = false,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    val height = if (isGrid) ProviderGridCardHeight else ProviderListCardHeight
    ItemCard(
        isGrid = isGrid,
        onClick = onOpen,
        modifier = modifier.height(height),
        color = if (isDropTarget) MaterialTheme.colorScheme.primaryContainer else prominentContainerColor(),
        border = if (isDropTarget) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else prominentContainerBorder(1.5.dp),
        icon = {
            ProviderFolderPreview(group, providers,
                Modifier.size(if (isGrid) 52.dp else 46.dp).testTag("provider_group_icon_${group.id}"))
        },
        actions = {},
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isDropTarget) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.provider_count, providers.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    )
}

/** A provider inside an open folder: the very same card as outside, only with its own test tag. */
@Composable
internal fun ProviderFolderProviderTile(
    provider: ModelProvider,
    isGrid: Boolean,
    onEdit: () -> Unit,
    onMoveToGroup: () -> Unit,
    onDelete: () -> Unit,
    onToggleHidden: () -> Unit,
    testTag: Boolean = true,
    modifier: Modifier = Modifier
) {
    ProviderUnifiedCard(
        provider = provider,
        isGrid = isGrid,
        modifier = modifier.then(
            if (testTag) Modifier.testTag("provider_folder_provider_${provider.id}") else Modifier
        ),
        onEdit = onEdit,
        onMoveToGroup = onMoveToGroup,
        onDelete = onDelete,
        onToggleHidden = onToggleHidden
    )
}

@Composable
fun ProviderUnifiedCard(
    provider: ModelProvider,
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onMoveToGroup: () -> Unit,
    onDelete: () -> Unit,
    onToggleHidden: () -> Unit = {}
) {
    ItemCard(
        isGrid = isGrid,
        onClick = onEdit,
        modifier = modifier
            .heightIn(min = if (isGrid) ProviderGridCardHeight else ProviderListCardHeight),
            icon = {
                EntityIcon(provider.icon, Modifier.size(42.dp), text = providerInitials(provider.name),
                    matchName = provider.name, grayscale = provider.isHidden)
            },
            actions = {
                ProviderContextMenu(provider.isHidden, onEdit, onMoveToGroup, onDelete, onToggleHidden)
            },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (provider.isHidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                        )
                            Text(
                                text = provider.type.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (provider.isHidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                    if (!provider.type.isAccountProvider) Text(
                        text = provider.baseUrl,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = if (provider.isHidden) 0.7f else 1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )
}

@Composable
private fun ProviderContextMenu(
    isHidden: Boolean,
    onEdit: () -> Unit,
    onMoveToGroup: () -> Unit,
    onDelete: () -> Unit,
    onToggleHidden: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            SharedIcon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more_options))
        }
        if (expanded) ContextDropdownMenu(
            expanded = true,
            actions = listOf(
                ContextMenuAction(
                    stringResource(if (isHidden) R.string.provider_show else R.string.provider_hide),
                    onToggleHidden,
                    if (isHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff
                ),
                ContextMenuAction(stringResource(R.string.common_edit), onEdit, Icons.Outlined.Edit),
                ContextMenuAction(stringResource(R.string.move_to_group), onMoveToGroup, Icons.Outlined.Folder),
                ContextMenuAction(stringResource(R.string.common_delete), onDelete, Icons.Outlined.Delete, destructive = true)
            ),
            onDismissRequest = { expanded = false }
        )
    }
}
