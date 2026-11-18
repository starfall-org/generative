package org.starfall.generative.shared.ui.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.ItemContextMenu
import org.starfall.generative.shared.subfeatures.icons.ui.providerInitials

@Composable
fun McpUnifiedCard(
    name: String, detail: String, isGrid: Boolean, modifier: Modifier = Modifier, icon: String? = null,
    toolCount: Int? = null, toolError: String? = null, loading: Boolean = false,
    onToolErrorClick: ((String) -> Unit)? = null,
    onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    firstActionText: String? = null, firstActionIcon: Int? = null,
    firstActionImageVector: ImageVector? = null, onFirstAction: (() -> Unit)? = null,
    actionsEnabled: Boolean = true
) {
    ItemCard(
        isGrid = isGrid,
        onClick = onEdit.takeIf { actionsEnabled },
        modifier = modifier,
            icon = { EntityIcon(icon, Modifier.size(42.dp), text = providerInitials(name),
                fallback = Icons.Outlined.Mcp, matchName = name) },
            actions = {
                if (onEdit != null || onDelete != null || onFirstAction != null) ItemContextMenu(
                    onEdit = onEdit, onDelete = onDelete, deleteColor = MaterialTheme.colorScheme.error,
                    firstActionText = firstActionText, firstActionIcon = firstActionIcon,
                    firstActionImageVector = firstActionImageVector, onFirstAction = onFirstAction,
                    enabled = actionsEnabled
                )
            },
            content = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when {
                        toolError != null -> Row(Modifier.clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                            .then(if (onToolErrorClick != null && actionsEnabled) Modifier.clickable { onToolErrorClick(toolError) } else Modifier)
                            .padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            SharedIcon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.mcp_error_details), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.error)
                        }
                        loading -> Text(stringResource(R.string.loading_tools), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurface)
                        toolCount != null -> Text(stringResource(R.string.cached_tools_count, toolCount),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.outline)
                    }
                }
            })
}

@Composable
internal fun McpEmptyState(hasSearchQuery: Boolean) {
    Box(
        Modifier.fillMaxSize().then(if (hasSearchQuery) Modifier else Modifier.padding(horizontal = 16.dp, vertical = 8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (hasSearchQuery) {
            Text(stringResource(R.string.content_list_no_results))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SharedIcon(Icons.Outlined.Mcp, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.no_mcp_servers), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
