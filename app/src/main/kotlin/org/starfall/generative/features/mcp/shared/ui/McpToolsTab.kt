package org.starfall.generative.features.mcp.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.starfall.generative.R
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.McpToolParameterConfig
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.ui.LinearWavyProgressIndicator
import org.starfall.generative.shared.ui.lists.ItemSurface
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

@Composable
internal fun McpToolsTab(
    serverId: String, tools: List<ToolDefinition>?, loading: Boolean, error: String?, ready: Boolean,
    toolSettings: ToolSettings, onSetToolEnabled: (String, String, Boolean) -> Unit,
    onSetToolParameter: (String, String, String, McpToolParameterConfig) -> Unit,
    onRefresh: () -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.available_tools), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (tools == null) stringResource(R.string.no_cached_tool_list) else stringResource(R.string.cached_tools_count, tools.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRefresh, enabled = ready && !loading) {
                Text(stringResource(if (loading) R.string.refreshing else R.string.refresh))
            }
        }
        if (loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        if (!ready) {
            ToolListPlaceholder(R.string.mcp_enter_server_first)
            return@Column
        }
        if (error != null) Text(error.lineSequence().firstOrNull().orEmpty(),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        when {
            tools == null -> ToolListPlaceholder(R.string.no_cached_tools_yet)
            tools.isEmpty() -> ToolListPlaceholder(R.string.server_reported_no_tools)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(tools, key = { it.name }) { tool ->
                    McpToolItem(
                        serverId = serverId,
                        tool = tool,
                        enabled = toolSettings.mcpTools[serverId]?.get(tool.originalName) != false,
                        parameterSettings = toolSettings.mcpToolParameters[serverId]?.get(tool.originalName).orEmpty(),
                        onEnabledChange = { onSetToolEnabled(serverId, tool.originalName, it) },
                        onParameterChange = { name, config -> onSetToolParameter(serverId, tool.originalName, name, config) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolListPlaceholder(resource: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(resource), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun McpToolItem(
    serverId: String,
    tool: ToolDefinition,
    enabled: Boolean,
    parameterSettings: Map<String, McpToolParameterConfig>,
    onEnabledChange: (Boolean) -> Unit,
    onParameterChange: (String, McpToolParameterConfig) -> Unit
) {
    var expanded by remember(tool.name) { mutableStateOf(false) }
    val properties = tool.schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (tool.schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
    ItemSurface(
        isGrid = false,
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                content = { Text(tool.originalName, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    if (tool.description.isNotBlank()) Text(tool.description, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { EntityIcon(null, Modifier.size(42.dp), fallback = Icons.Outlined.Extension) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(enabled, onCheckedChange = onEnabledChange)
                        Spacer(Modifier.width(6.dp))
                        SharedIcon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                            stringResource(if (expanded) R.string.collapse_tool_details else R.string.expand_tool_details))
                    }
                })
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (tool.description.isNotBlank()) {
                        Text(stringResource(R.string.common_description), style = MaterialTheme.typography.labelLarge)
                        Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.common_parameters), style = MaterialTheme.typography.labelLarge)
                    if (properties.isEmpty()) Text(stringResource(R.string.no_parameters), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else properties.forEach { (name, raw) ->
                        McpToolParameter(
                            name = name,
                            raw = raw,
                            required = name in required,
                            config = parameterSettings[name] ?: McpToolParameterConfig(),
                            onChange = { onParameterChange(name, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToolParameter(
    name: String,
    raw: JsonElement,
    required: Boolean,
    config: McpToolParameterConfig,
    onChange: (McpToolParameterConfig) -> Unit
) {
    val schema = raw as? JsonObject ?: JsonObject(emptyMap())
    val type = schema["type"]?.jsonPrimitive?.contentOrNull ?: if (schema["enum"] is JsonArray) "enum" else "any"
    val description = schema["description"]?.jsonPrimitive?.contentOrNull
    val enumValues = (schema["enum"] as? JsonArray)?.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text(type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    if (required) {
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mcp_schema_required), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!enumValues.isNullOrBlank()) Text(stringResource(R.string.mcp_schema_allowed_values, enumValues), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { onChange(config.copy(enabled = it)) },
            )
        }
        OutlinedTextField(
            value = config.defaultValue,
            onValueChange = { onChange(config.copy(defaultValue = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.common_default)) },
            placeholder = { Text(type) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password
            ),
            singleLine = true,
            supportingText = if (required && !config.enabled && config.defaultValue.isBlank()) {
                { Text(stringResource(R.string.mcp_required_parameter_default_needed), color = MaterialTheme.colorScheme.error) }
            } else null
        )
    }
}
