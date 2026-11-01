package org.starfall.generative.features.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.storage.preferences.DEFAULT_MCP_DYNAMIC_MIN_MCPS
import org.starfall.generative.shared.storage.preferences.DEFAULT_MCP_DYNAMIC_MIN_TOOLS
import org.starfall.generative.shared.storage.preferences.DEFAULT_MCP_LIST_LIMIT
import org.starfall.generative.shared.storage.preferences.MAX_MCP_DYNAMIC_MIN
import org.starfall.generative.shared.storage.preferences.MAX_MCP_LIST_LIMIT
import org.starfall.generative.shared.storage.preferences.MAX_MCP_LOAD_LIMIT
import org.starfall.generative.shared.storage.preferences.McpAttachmentMode
import org.starfall.generative.shared.storage.preferences.McpAttachmentSettings
import org.starfall.generative.shared.storage.preferences.McpDynamicStrategy
import org.starfall.generative.shared.ui.dialogs.Dialog

/** Direct switches, immediate choice dialogs and a single Save/Cancel editor for all MCP limits. */
@Composable
fun McpAttachmentsPreferences(
    settings: McpAttachmentSettings,
    onChange: (McpAttachmentSettings) -> Unit
) {
    Text(stringResource(R.string.mcp_strategy), style = MaterialTheme.typography.titleMedium)

    SettingsChoicePreference(
        title = stringResource(R.string.mcp_tool_mode),
        value = settings.mode,
        choices = listOf(
            SettingsChoice(McpAttachmentMode.DYNAMIC, stringResource(R.string.mcp_strategy_load_on_demand)),
            SettingsChoice(McpAttachmentMode.CLASSIC, stringResource(R.string.mcp_strategy_attach_all_enabled_tools))
        ),
        onSelected = { onChange(settings.copy(mode = it)) },
        modifier = Modifier
    )

    Row(
        Modifier.fillMaxWidth().toggleable(
            value = settings.includeToolDescription,
            role = Role.Switch,
            onValueChange = { onChange(settings.copy(includeToolDescription = it)) }
        ).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.mcp_strategy_include_tool_descriptions), style = MaterialTheme.typography.titleSmall)
        Switch(checked = settings.includeToolDescription, onCheckedChange = null)
    }

    if (settings.mode == McpAttachmentMode.CLASSIC) return

    SettingsChoicePreference(
        title = stringResource(R.string.mcp_strategy_on_demand),
        value = settings.strategy,
        choices = listOf(
            SettingsChoice(McpDynamicStrategy.OVERFLOW, stringResource(R.string.mcp_strategy_initial_on_demand)),
            SettingsChoice(McpDynamicStrategy.ALL, stringResource(R.string.mcp_strategy_all_servers_on_demand))
        ),
        onSelected = { onChange(settings.copy(strategy = it)) }
    )

    var limitsOpen by rememberSaveable { mutableStateOf(false) }
    SettingsActionPreference(stringResource(R.string.mcp_strategy_on_demand_limits)) { limitsOpen = true }
    if (limitsOpen) {
        McpLimitsDialog(settings, onDismiss = { limitsOpen = false }) { updated ->
            onChange(updated)
            limitsOpen = false
        }
    }
}

@Composable
private fun McpLimitsDialog(
    settings: McpAttachmentSettings,
    onDismiss: () -> Unit,
    onSave: (McpAttachmentSettings) -> Unit
) {
    var minMcps by remember { mutableStateOf(settings.minMcps.toString()) }
    var minTools by remember { mutableStateOf(settings.minTools.toString()) }
    var listLimit by remember { mutableStateOf(settings.listLimit.toString()) }
    var loadLimit by remember { mutableStateOf(settings.loadLimit.toString()) }

    fun parse(text: String, defaultValue: Int, range: IntRange): Int? =
        (if (text.isBlank()) defaultValue else text.toIntOrNull())?.takeIf { it in range }

    val a = parse(minMcps, DEFAULT_MCP_DYNAMIC_MIN_MCPS, 0..MAX_MCP_DYNAMIC_MIN)
    val b = parse(minTools, DEFAULT_MCP_DYNAMIC_MIN_TOOLS, 0..MAX_MCP_DYNAMIC_MIN)
    val c = parse(listLimit, DEFAULT_MCP_LIST_LIMIT, 1..MAX_MCP_LIST_LIMIT)
    val d = parse(loadLimit, settings.defaultLoadLimit, 1..MAX_MCP_LOAD_LIMIT)

    Dialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_strategy_on_demand_limits)) },
        scrollableContent = true,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsNumberField(
                    stringResource(R.string.mcp_strategy_min_servers), minMcps,
                    DEFAULT_MCP_DYNAMIC_MIN_MCPS, 0..MAX_MCP_DYNAMIC_MIN,
                    "mcp-attachment-min-mcps", onInputChange = { minMcps = it }
                )
                SettingsNumberField(
                    stringResource(R.string.mcp_strategy_min_tools), minTools,
                    DEFAULT_MCP_DYNAMIC_MIN_TOOLS, 0..MAX_MCP_DYNAMIC_MIN,
                    "mcp-attachment-min-tools", onInputChange = { minTools = it }
                )
                SettingsNumberField(
                    stringResource(R.string.mcp_strategy_list_limit), listLimit,
                    DEFAULT_MCP_LIST_LIMIT, 1..MAX_MCP_LIST_LIMIT,
                    "mcp-attachment-list-limit", onInputChange = { listLimit = it }
                )
                SettingsNumberField(
                    stringResource(R.string.mcp_strategy_connection_limit), loadLimit,
                    settings.defaultLoadLimit, 1..MAX_MCP_LOAD_LIMIT,
                    "mcp-attachment-load-limit", onInputChange = { loadLimit = it }
                )
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            TextButton(enabled = a != null && b != null && c != null && d != null, onClick = {
                if (a != null && b != null && c != null && d != null) {
                    onSave(settings.copy(minMcps = a, minTools = b, listLimit = c).withLoadLimit(d))
                }
            }) { Text(stringResource(R.string.common_save)) }
        }
    )
}
