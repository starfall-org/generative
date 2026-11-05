package org.starfall.generative.features.chatoptions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.resolvedUrl
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.subfeatures.icons.ui.providerInitials
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.subfeatures.modelpicker.ui.DefaultSelectionItem
import org.starfall.generative.shared.ui.sheets.ItemListSheet
import org.starfall.generative.shared.ui.sheets.ItemListSheetItem

/** Selects and enables the MCP server that supplies web-search tools. */
@Composable
fun WebSearchProviderSettings(
    servers: List<McpInfo>,
    settings: ToolSettings,
    onProviderChange: (String?) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedProvider = servers.firstOrNull { it.id == settings.webSearchProviderId }
    var expanded by remember { mutableStateOf(false) }

    SettingsCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntityIcon(null, Modifier.size(42.dp), fallback = Icons.Outlined.Language)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.web_search_service),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    stringResource(R.string.web_search_service_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selectedProvider != null) {
                Switch(
                    checked = settings.webSearchEnabled,
                    onCheckedChange = onEnabledChange
                )
            }
        }

        Box(Modifier.fillMaxWidth()) {
            DefaultSelectionItem(
                title = selectedProvider?.name ?: stringResource(R.string.web_search_service_none),
                icon = {
                    EntityIcon(
                        image = selectedProvider?.icon,
                        modifier = Modifier.size(48.dp),
                        text = providerInitials(selectedProvider?.name.orEmpty()),
                        fallback = Icons.Outlined.Language,
                        matchName = selectedProvider?.name
                    )
                },
                onClick = { expanded = true }
            )
            if (expanded) {
                val options = buildList<ItemListSheetItem<McpInfo?>> {
                    add(chatOptionSelectionItem<McpInfo?>(
                        key = "none", value = null,
                        title = stringResource(R.string.web_search_service_none),
                        selected = selectedProvider == null,
                        icon = {
                            EntityIcon(image = null, modifier = Modifier.size(42.dp),
                                fallback = Icons.Outlined.Language)
                        },
                        onClick = { onProviderChange(null); expanded = false },
                        modifier = Modifier.testTag("web-search-provider-option-none")
                    ))
                    servers.forEach { server ->
                        add(chatOptionSelectionItem(
                            key = server.id, value = server,
                            title = server.name,
                            subtitle = server.resolvedUrl() ?: stringResource(R.string.mcp_no_endpoint),
                            detail = server.cachedTools?.let {
                                stringResource(R.string.cached_tools_count, it.size)
                            },
                            selected = server.id == settings.webSearchProviderId,
                            icon = {
                                EntityIcon(image = server.icon, modifier = Modifier.size(42.dp),
                                    text = providerInitials(server.name), fallback = Icons.Outlined.Extension,
                                    matchName = server.name)
                            },
                            onClick = { onProviderChange(server.id); expanded = false },
                            modifier = Modifier.testTag("web-search-provider-option-${server.id}")
                        ))
                    }
                }
                ItemListSheet(
                    title = stringResource(R.string.web_search_service),
                    items = options,
                    onDismissRequest = { expanded = false }
                )
            }

        }
        if (servers.isEmpty()) {
            Text(
                stringResource(R.string.web_search_service_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
