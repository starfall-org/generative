package org.starfall.generative.features.home.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.ui.LocalToolControls
import org.starfall.generative.shared.ui.icons.EntityIcon
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.sheets.BottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureToolsSheet(onDismiss: () -> Unit) {
    BottomSheet(onDismissRequest = onDismiss) {
        FeatureToolsSheetContent()
    }
}

@Composable
internal fun FeatureToolsSheetContent(
    onBack: (() -> Unit)? = null,
    onOpenSettings: () -> Unit = {}
) {
    val controls = LocalToolControls.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QuickActionsSubpageHeader(
            title = stringResource(R.string.common_feature_tools),
            onBack = onBack,
            actions = {
                QuickActionsSettingsButton(
                    title = stringResource(R.string.common_feature_tools),
                    onClick = onOpenSettings
                )
            }
        )
            val webSearchProvider = controls.servers.firstOrNull { it.id == controls.settings.webSearchProviderId }
            SettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SharedIcon(
                        Icons.Outlined.Language,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp).padding(10.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.model_tool_web_search),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                        Text(
                            webSearchProvider?.name ?: stringResource(R.string.web_search_service_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = webSearchProvider != null && controls.settings.webSearchEnabled,
                        enabled = webSearchProvider != null,
                        onCheckedChange = controls.setWebSearch
                    )
                }
            }
            listOf("generate_image", "generate_video", "generate_music").forEach { name ->
                val config = controls.settings.system[name] ?: SystemToolConfig()
                val provider = if (name == "generate_music") null else controls.providers.firstOrNull { it.id == config.providerId }
                val model = provider?.config?.modelConfigs?.get(config.modelId)
                val modelName = if (name == "generate_music") config.musicRequestProfiles[config.activeMusicRequestProfileId]?.name else model?.displayName?.ifBlank { config.modelId }?.takeIf(String::isNotBlank)
                val icon = if (name == "generate_music") Icons.Outlined.Audiotrack else if (name == "generate_image") Icons.Outlined.Image else Icons.Outlined.Videocam
                SettingsCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (model != null && modelName != null) {
                            EntityIcon(
                                image = model.icon,
                                modifier = Modifier.size(44.dp),
                                text = modelName.take(2).uppercase(),
                                fallback = icon,
                                matchName = modelName,
                                fallbackMatchName = config.modelId,
                                model = true
                            )
                        } else {
                            SharedIcon(icon, contentDescription = null, modifier = Modifier.size(44.dp))
                        }
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    if (name == "generate_music") R.string.tool_music_generation_title
                                    else if (name == "generate_image") R.string.tool_image_generation_title
                                    else R.string.tool_video_generation_title
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1
                            )
                            modelName?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { controls.setSystem(name, config.copy(enabled = it)) }
                        )
                    }
                }
            }
            SettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SharedIcon(
                        Icons.Outlined.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp).padding(10.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.tool_send_file_title),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                        Text(
                            stringResource(R.string.tool_send_file_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = controls.settings.sendFileEnabled,
                        onCheckedChange = controls.setSendFile
                    )
                }
            }
            SettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SharedIcon(Icons.Outlined.Search, contentDescription = null,
                        modifier = Modifier.size(44.dp).padding(10.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tool_agent_skills_search_title),
                            style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.tool_agent_skills_search_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = controls.settings.agentSkillsSearchEnabled,
                        onCheckedChange = controls.setAgentSkillsSearch)
                }
            }
            SettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SharedIcon(Icons.Outlined.Search, contentDescription = null,
                        modifier = Modifier.size(44.dp).padding(10.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tool_search_tools_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.tool_search_tools_description), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = controls.settings.searchToolsEnabled,
                        onCheckedChange = controls.setSearchTools)
                }
            }
    }
}
