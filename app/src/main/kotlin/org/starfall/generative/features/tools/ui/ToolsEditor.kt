package org.starfall.generative.features.tools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.media.modelIndependent
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.inputs.SelectionDropdown
import org.starfall.generative.shared.ui.layout.AppSwipeTabs
import org.starfall.generative.shared.ui.model.DefaultModelSelector
import org.starfall.generative.shared.ui.model.ModelPickerSheet
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsEditor(
    providers: List<ModelProvider>,
    settings: ToolSettings,
    onSave: (String, SystemToolConfig) -> Unit,
    onSetSendFile: (Boolean) -> Unit = {},
    onSetSearchTools: (Boolean) -> Unit = {},
    onSetAgentSkillsSearch: (Boolean) -> Unit = {},
    providerGroups: List<ProviderGroup> = emptyList(),
    collapsedGroupIds: Set<String> = emptySet(),
    collapsedProviderIds: Set<String> = emptySet(),
    onCollapsedGroupIdsChange: (Set<String>) -> Unit = {},
    onCollapsedProviderIdsChange: (Set<String>) -> Unit = {},
    onSaveModelConfiguration: (String, String, ModelConfiguration) -> Unit = { _, _, _ -> },
    onEditProvider: (providerId: String) -> Unit = {},
    onEditModel: ((providerId: String, modelId: String) -> Unit)? = null,
    onOpenMediaProfiles: (Boolean) -> Unit = {},
    onOpenMusicProfiles: () -> Unit = {},
    onBack: () -> Unit
) {
    var choosing by remember { mutableStateOf<String?>(null) }
    val pager = rememberPagerState(pageCount = { 2 })
    fun openMediaSettings(name: String) { onOpenMediaProfiles(name == "generate_video") }

    EditorScreenTemplate(
        title = stringResource(R.string.common_feature_tools),
        onBack = onBack
    ) { scaffoldPadding ->
        val padding = scaffoldPadding

        AppSwipeTabs(
            tabs = listOf(stringResource(R.string.tool_settings_tab_media), stringResource(R.string.tool_settings_tab_others)),
            pagerState = pager,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            tabRowModifier = Modifier.padding(horizontal = 16.dp),
            tabTag = { if (it == 0) "built-in-tools-media-tab" else "built-in-tools-others-tab" }
        ) { page ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (page == 0) {
                    items(listOf("generate_image", "generate_video")) { name ->
                        val config = settings.system[name] ?: SystemToolConfig()
                        val provider = providers.find { it.id == config.providerId }
                        val model = provider?.config?.modelConfigs?.get(config.modelId)
                        val image = name == "generate_image"
                        val selectedProfile = config.selectedMediaProfile(!image, model)
                        val profiles = config.mediaProfiles(!image)
                        val hasModel = provider != null && config.modelId.isNotBlank() && model != null
                        val eligibleProfiles = profiles.values.filter { hasModel || it.modelIndependent }
                        val canPickProfile = eligibleProfiles.isNotEmpty()
                        val currentProfileValid = hasModel || selectedProfile.modelIndependent

                        SettingsCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(
                                            if (image) R.string.tool_image_generation_title
                                            else R.string.tool_video_generation_title
                                        ),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Switch(
                                    checked = config.enabled,
                                    onCheckedChange = { onSave(name, config.copy(enabled = it)) }
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            DefaultModelSelector(
                                provider = provider,
                                modelId = config.modelId,
                                model = model,
                                onClick = { choosing = name }
                            )
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                key(name, config.providerId, config.modelId) {
                                    SelectionDropdown(
                                        label = stringResource(R.string.media_gen_profile),
                                        options = eligibleProfiles.map { profile ->
                                            profile to (profile.name + " · " + stringResource(
                                                if (profile.mode == MediaGenerationMode.DEFAULT)
                                                    R.string.common_default else R.string.media_chain_custom
                                            ))
                                        },
                                        selected = selectedProfile,
                                        displayValue = if (currentProfileValid) selectedProfile.name
                                            else stringResource(R.string.media_chain_select_profile),
                                        enabled = canPickProfile,
                                        onSelect = { profile ->
                                            if (!profile.modelIndependent && hasModel) {
                                                onSaveModelConfiguration(
                                                    config.providerId, config.modelId,
                                                    requireNotNull(model).copy(
                                                        mediaApiType = profile.mode,
                                                        mediaRequestProfileId = profile.id
                                                    )
                                                )
                                            }
                                            onSave(name, config.selectMediaProfile(!image, profile))
                                        },
                                        modifier = Modifier.weight(1f),
                                        fieldModifier = Modifier.testTag("media_profile_picker_$name")
                                    )
                                }
                                IconButton(
                                    onClick = { openMediaSettings(name) },
                                    modifier = Modifier.padding(start = 6.dp).testTag("media_profile_library_$name")
                                ) {
                                    SharedIcon(Icons.Outlined.FormatListBulleted,
                                        stringResource(if (image) R.string.media_image_gen_profiles else R.string.media_video_gen_profiles))
                                }
                            }
                        }
                    }
                    item {
                        MusicToolSettings(settings.system["generate_music"] ?: SystemToolConfig(),
                            onChange = { onSave("generate_music", it) }, onOpenProfiles = onOpenMusicProfiles)
                    }
                } else {
                    item {
                        SettingsCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.tool_search_tools_title), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.tool_search_tools_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = settings.searchToolsEnabled, onCheckedChange = onSetSearchTools)
                            }
                        }
                    }
                    item {
                        SettingsCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.tool_agent_skills_search_title),
                                        style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.tool_agent_skills_search_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = settings.agentSkillsSearchEnabled,
                                    onCheckedChange = onSetAgentSkillsSearch)
                            }
                        }
                    }
                    item {
                        SettingsCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.tool_send_file_title),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        stringResource(R.string.tool_send_file_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = settings.sendFileEnabled,
                                    onCheckedChange = onSetSendFile
                                )
                            }
                        }
                    }
                }
            }
        }

    }

    choosing?.let { name ->
        val type = when (name) {
            "generate_image" -> ModelType.IMAGE_GENERATION
            "generate_video" -> ModelType.VIDEO_GENERATION
            else -> ModelType.TEXT_GENERATION
        }
        val current = settings.system[name] ?: SystemToolConfig()
        ModelPickerSheet(
            providers = providers,
            providerGroups = providerGroups,
            collapsedGroupIdsState = collapsedGroupIds,
            collapsedProviderIdsState = collapsedProviderIds,
            onCollapsedGroupIdsChange = onCollapsedGroupIdsChange,
            onCollapsedProviderIdsChange = onCollapsedProviderIdsChange,
            selectedProviderId = current.providerId,
            selectedModelId = current.modelId,
            conversationReasoningEffort = null,
            onSetReasoningEffort = {},
            showReasoningEffort = false,
            modelFilter = { provider, id, config ->
                config.modelType == type &&
                    provider.config.modelConfigs.containsKey(id) &&
                    provider.config.modelIds?.contains(id) != false
            },
            onSelectModel = { providerId, modelId ->
                onSave(name, current.selectMediaModel(name == "generate_video", providerId, modelId))
            },
            onEditProvider = onEditProvider,
            onEditModel = onEditModel,
            onDismiss = { choosing = null }
        )
    }
}
