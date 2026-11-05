package org.starfall.generative.features.mcp.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.generative.R
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.isWebSearchProvider
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.ItemContextMenu
import org.starfall.generative.shared.subfeatures.icons.ui.providerInitials

@Composable
fun McpUnifiedCard(server: McpInfo, isGrid: Boolean, modifier: Modifier = Modifier, toolCount: Int?, toolError: String?,
    loading: Boolean, isEnabled: Boolean, onToggleEnabled: () -> Unit,
    onToolErrorClick: (String) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    managedSettings: ToolSettings = ToolSettings()) {
    ItemCard(
        isGrid = isGrid,
        onClick = onEdit,
        modifier = modifier,
            icon = { EntityIcon(server.icon, Modifier.size(42.dp), text = providerInitials(server.name),
                fallback = Icons.Outlined.Extension, matchName = server.name) },
            actions = {
                ItemContextMenu(
                    onEdit = onEdit,
                    onDelete = onDelete,
                    deleteColor = MaterialTheme.colorScheme.error,
                    firstActionText = if (isWebSearchProvider(managedSettings, server.id)) null
                        else stringResource(if (isEnabled) R.string.mcp_enabled else R.string.mcp_enable),
                    firstActionIcon = (if (isEnabled) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked)
                        .takeUnless { isWebSearchProvider(managedSettings, server.id) },
                    onFirstAction = onToggleEnabled.takeUnless { isWebSearchProvider(managedSettings, server.id) }
                )
            },
            content = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(server.url ?: stringResource(R.string.mcp_no_endpoint), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when {
                        toolError != null -> Row(Modifier.clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)).clickable { onToolErrorClick(toolError) }
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
