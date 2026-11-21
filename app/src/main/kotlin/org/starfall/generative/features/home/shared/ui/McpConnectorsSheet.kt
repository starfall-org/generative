package org.starfall.generative.features.home.shared.ui

import org.starfall.generative.shared.ui.layout.fadingVerticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.resolvedUrl
import org.starfall.generative.shared.model.isWebSearchProvider
import org.starfall.generative.shared.model.mcpServerEnabled
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.sheets.ContentSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpConnectorsSheet(onDismiss: () -> Unit) {
    ContentSheet(onDismissRequest = onDismiss) {
        McpConnectorsSheetContent()
    }
}

@Composable
internal fun McpConnectorsSheetContent(
    onBack: (() -> Unit)? = null,
    onOpenSettings: () -> Unit = {}
) {
    val controls = LocalToolControls.current
    Column(
        Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QuickActionsSubpageHeader(
            title = stringResource(R.string.common_mcp_connectors),
            onBack = onBack,
            actions = {
                QuickActionsSettingsButton(
                    title = stringResource(R.string.common_mcp_connectors),
                    onClick = onOpenSettings
                )
            }
        )
        if (controls.servers.isEmpty()) {
            Text(
                stringResource(R.string.no_mcp_servers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        controls.servers.forEach { server ->
            val serverAddress = server.resolvedUrl().orEmpty()
            SettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    EntityIcon(
                        image = server.icon,
                        modifier = Modifier.size(44.dp),
                        text = server.name.take(2).uppercase(),
                        fallback = Icons.Outlined.Mcp,
                        matchName = server.name
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            server.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (serverAddress.isBlank()) stringResource(R.string.mcp_servers) else serverAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.cached_tools_count, server.cachedTools?.size ?: 0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (!isWebSearchProvider(controls.settings, server.id)) {
                        Switch(
                            checked = mcpServerEnabled(controls.settings, server.id),
                            onCheckedChange = { controls.setMcp(server.id, it) }
                        )
                    }
                }
            }
        }
    }
}

internal data class RuntimeMcpConnector(
    val name: String,
    val address: String?,
    val statusLabel: Int?,
    val connected: Boolean,
    val onSetConnected: ((Boolean) -> Unit)?,
)

@Composable
internal fun RuntimeMcpConnectorsSheetContent(
    connectors: List<RuntimeMcpConnector>,
    message: Int?,
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    switchesEnabled: Boolean,
) {
    val title = stringResource(R.string.common_mcp_connectors)
    Column(
        Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QuickActionsSubpageHeader(title = title, onBack = onBack) {
            if (onRetry != null) {
                IconButton(onClick = onRetry) {
                    SharedIcon(Icons.Outlined.Refresh, stringResource(R.string.common_retry))
                }
            }
            if (onOpenSettings != null) QuickActionsSettingsButton(title, onOpenSettings)
        }
        if (message != null) Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
        connectors.forEach { connector ->
            ItemCard(
                isGrid = false,
                onClick = null,
                icon = { SharedIcon(Icons.Outlined.Mcp, contentDescription = null, modifier = Modifier.size(24.dp)) },
                content = {
                    Column {
                        Text(connector.name, style = MaterialTheme.typography.titleSmall)
                        connector.address?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        connector.statusLabel?.let {
                            Text(stringResource(it), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    connector.onSetConnected?.let { setConnected ->
                        Switch(
                            checked = connector.connected,
                            enabled = switchesEnabled,
                            onCheckedChange = setConnected,
                            modifier = Modifier.semantics { contentDescription = connector.name },
                        )
                    }
                },
            )
        }
    }
}
