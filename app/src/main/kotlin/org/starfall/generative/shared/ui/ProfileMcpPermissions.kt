package org.starfall.generative.shared.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.generative.R
import androidx.compose.ui.res.stringResource
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.model.McpAccess
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.ui.sheets.BottomSheet
import org.starfall.generative.shared.ui.icons.EntityIcon
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.text.OutlinedTextField
import org.starfall.generative.shared.ui.ToolSwitch

@Composable
fun ProfileMcpPermissions(
    servers: List<McpInfo>,
    access: Map<String, McpAccess>,
    toolsCache: Map<String, List<ToolDefinition>>,
    settings: ToolSettings,
    onChange: (Map<String, McpAccess>) -> Unit
) {
    Text(stringResource(R.string.mcp_permissions), style = MaterialTheme.typography.titleMedium)
    if (servers.isEmpty()) {
        Text(stringResource(R.string.mcp_add_servers_hint))
        return
    }

    servers.forEach { server ->
        val policy = access[server.id] ?: McpAccess(enabled = true)
        HorizontalDivider()
        ToolSwitch(server.name, policy.enabled) { enabled ->
            onChange(access + (server.id to policy.copy(enabled = enabled)))
        }

        if (policy.enabled) {
            val cached = toolsCache[server.id] ?: server.cachedTools
            when {
                cached == null -> {
                    Text(
                        stringResource(R.string.mcp_tool_list_not_cached_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    val globallyEnabled = cached.filter { tool ->
                        settings.mcpTools[server.id]?.get(tool.originalName) != false
                    }
                    if (globallyEnabled.isEmpty()) {
                        Text(
                            stringResource(R.string.mcp_no_globally_enabled_tools_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        globallyEnabled.forEach { tool ->
                            ToolSwitch(
                                label = tool.originalName,
                                checked = policy.tools[tool.originalName] != false
                            ) { enabled ->
                                onChange(
                                    access + (
                                        server.id to policy.copy(
                                            tools = policy.tools + (tool.originalName to enabled)
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
