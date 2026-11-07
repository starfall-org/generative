package org.starfall.generative.navigation

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.util.UUID
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.features.chatoptions.ui.ChatOptionsScreen
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.features.settings.logic.SettingsViewModel
import org.starfall.generative.features.tools.ui.MediaRequestChainSettings
import org.starfall.generative.features.tools.ui.MediaRequestProfileEditor
import org.starfall.generative.features.tools.ui.ToolsEditor
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.media.MediaKind
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.media.defaultMediaProfile
import org.starfall.generative.shared.model.media.modelIndependent
import org.starfall.generative.shared.model.media.withDefaultMediaProfile
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup

/** Read root state at use time so retained screens and save callbacks receive the latest values. */
internal fun RetainedNavGraphBuilder.featureToolRoutes(
    navController: NavHostController,
    viewModel: ChatViewModel,
    configurationViewModel: ConfigurationViewModel,
    settingsViewModel: SettingsViewModel,
    appPrefs: () -> AppPreferences,
    providers: () -> List<ModelProvider>,
    providerGroups: () -> List<ProviderGroup>,
    speechServices: () -> List<SpeechService>,
    mcpServers: () -> List<McpInfo>,
    toolSettings: () -> ToolSettings
) {
    fun persistMusicProfiles(profiles: Map<String, MediaRequestProfile>, preferredId: String) {
        val latest = toolSettings().system["generate_music"] ?: SystemToolConfig()
        viewModel.setSystemTool("generate_music", latest.withMusicProfiles(profiles, preferredId))
    }

    fun persistMediaProfiles(video: Boolean, updatedProfiles: Map<String, MediaRequestProfile>, preferredId: String) {
        val toolName = if (video) "generate_video" else "generate_image"
        // Read the latest config on every write; an editor must never overwrite model or toggle changes.
        val config = toolSettings().system[toolName] ?: SystemToolConfig()
        val profiles = updatedProfiles.withDefaultMediaProfile(video)
        val requestedId = preferredId.takeIf { it in profiles } ?: defaultMediaProfile(video).id
        val selectedId = requestedId.takeIf {
            (config.providerId.isNotBlank() && config.modelId.isNotBlank()) ||
                profiles[it]?.modelIndependent == true
        } ?: defaultMediaProfile(video).id
        val updated = config.withMediaProfiles(video, profiles)
        val next = if (video) updated.copy(
            activeVideoRequestProfileId = selectedId,
            videoGenerationMode = updated.mediaProfile(true, selectedId).mode
        ) else updated.copy(
            activeImageRequestProfileId = selectedId,
            imageGenerationMode = updated.mediaProfile(false, selectedId).mode
        )
        viewModel.setSystemTool(toolName,
            if (profiles[selectedId]?.modelIndependent == true) next.copy(providerId = "", modelId = "") else next)
        // Reset references for deleted profiles and profiles converted to model-independent.
        val kind = if (video) ModelType.VIDEO_GENERATION else ModelType.IMAGE_GENERATION
        providers().forEach { source ->
            source.config.modelConfigs.forEach { (id, model) ->
                if (model.modelType == kind && model.mediaRequestProfileId.isNotBlank() &&
                    (model.mediaRequestProfileId !in profiles ||
                        profiles[model.mediaRequestProfileId]?.modelIndependent == true)) {
                    val fallback = next.mediaProfile(video, "")
                    configurationViewModel.saveModelConfiguration(source.id, id, model.copy(
                        mediaRequestProfileId = fallback.id,
                        mediaApiType = fallback.mode
                    ))
                }
            }
        }
    }

    composable(
        route = "${AppDestination.CHAT_OPTIONS.route}?tab={tab}&instructionId={instructionId}",
        arguments = listOf(
            navArgument("tab") { type = NavType.StringType; defaultValue = "helpers" },
            navArgument("instructionId") { type = NavType.StringType; defaultValue = "" }
        )
    ) { chatOptionsEntry ->
        ChatOptionsScreen(
            initialTab = chatOptionsEntry.arguments?.getString("tab") ?: "helpers",
            initialInstructionId = chatOptionsEntry.arguments?.getString("instructionId").orEmpty(),
            instructionLibrary = appPrefs().instructionLibrary,
            onInstructionLibraryChange = viewModel::setInstructionLibrary,
            agentSkills = appPrefs().agentSkills.skills,
            onAgentSkillsChange = viewModel::setAgentSkills,
            providers = providers(),
            settings = toolSettings(),
            mcpServers = mcpServers(),
            onSetWebSearchProvider = viewModel::setWebSearchProvider,
            onSetWebSearchEnabled = viewModel::setWebSearchEnabled,
            speechServices = speechServices(),
            selectedSpeechServiceId = appPrefs().selectedSpeechServiceId,
            onSetTtsProvider = { viewModel.selectSpeechService(it) },
            appPreferences = appPrefs(),
            onAutoSummaryEnabledChange = settingsViewModel::setAutoSummaryEnabled,
            onSummarySuggestionEnabledChange = settingsViewModel::setSummarySuggestionEnabled,
            onAutoSummaryPercentChange = settingsViewModel::setAutoSummaryPercent,
            providerGroups = providerGroups(),
            collapsedGroupIds = appPrefs().modelPickerCollapsedGroups,
            collapsedProviderIds = appPrefs().modelPickerCollapsedProviders,
            onCollapsedGroupIdsChange = settingsViewModel::setModelPickerCollapsedGroups,
            onCollapsedProviderIdsChange = settingsViewModel::setModelPickerCollapsedProviders,
            onSave = viewModel::setSystemTool,
            onEditProvider = { providerId ->
                navController.navigate("providers/edit/${Uri.encode(providerId)}") {
                    launchSingleTop = true
                }
            },
            onEditModel = { providerId, modelId ->
                navController.navigate(
                    "providers/edit/${Uri.encode(providerId)}?modelId=${Uri.encode(modelId)}"
                ) { launchSingleTop = true }
            },
            onBack = { navController.popBackStack() }
        )
    }
    composable(AppDestination.TOOLS_EDITOR.route) {
        ToolsEditor(
            providers = providers(),
            settings = toolSettings(),
            onSave = viewModel::setSystemTool,
            onSetSendFile = viewModel::setSendFileEnabled,
            onSetSearchTools = viewModel::setSearchToolsEnabled,
            onSetAgentSkillsSearch = viewModel::setAgentSkillsSearchEnabled,
            providerGroups = providerGroups(),
            collapsedGroupIds = appPrefs().modelPickerCollapsedGroups,
            collapsedProviderIds = appPrefs().modelPickerCollapsedProviders,
            onCollapsedGroupIdsChange = settingsViewModel::setModelPickerCollapsedGroups,
            onCollapsedProviderIdsChange = settingsViewModel::setModelPickerCollapsedProviders,
            onSaveModelConfiguration = configurationViewModel::saveModelConfiguration,
            onEditProvider = { providerId ->
                navController.navigate("providers/edit/${Uri.encode(providerId)}") {
                    launchSingleTop = true
                }
            },
            onEditModel = { providerId, modelId ->
                navController.navigate(
                    "providers/edit/${Uri.encode(providerId)}?modelId=${Uri.encode(modelId)}"
                ) { launchSingleTop = true }
            },
            onOpenMusicProfiles = { navController.navigate(MediaProfileRoutes.library(MediaKind.MUSIC)) },
            onOpenMediaProfiles = { video ->
                navController.navigate(MediaProfileRoutes.library(video)) { launchSingleTop = true }
            },
            onBack = { navController.popBackStack() }
        )
    }
    composable(
        route = MediaProfileRoutes.LIBRARY,
        arguments = listOf(navArgument("kind") { type = NavType.StringType })
    ) { entry ->
        val kind = MediaKind.entries.firstOrNull { it.name.equals(entry.arguments?.getString("kind"), true) } ?: MediaKind.IMAGE
        val video = kind == MediaKind.VIDEO
        val music = kind == MediaKind.MUSIC
        val toolName = kind.toolName
        val config = toolSettings().system[toolName] ?: SystemToolConfig()
        MediaRequestChainSettings(
            provider = if (music) null else providers().firstOrNull { it.id == config.providerId },
            config = config,
            onAutoSave = { profiles, activeId -> if (music) persistMusicProfiles(profiles, activeId) else persistMediaProfiles(video, profiles, activeId) },
            onOpenProfile = { id -> navController.navigate(MediaProfileRoutes.editor(kind, id)) },
            onAddProfile = { navController.navigate(MediaProfileRoutes.editor(kind, "new")) },
            onBack = { navController.popBackStack() },
            video = video, kind = kind
        )
    }
    composable(
        route = MediaProfileRoutes.EDITOR,
        arguments = listOf(
            navArgument("kind") { type = NavType.StringType },
            navArgument("profileId") { type = NavType.StringType }
        )
    ) { entry ->
        val kind = MediaKind.entries.firstOrNull { it.name.equals(entry.arguments?.getString("kind"), true) } ?: MediaKind.IMAGE
        val video = kind == MediaKind.VIDEO
        val music = kind == MediaKind.MUSIC
        val profileId = entry.arguments?.getString("profileId").orEmpty()
        val toolName = kind.toolName
        val config = toolSettings().system[toolName] ?: SystemToolConfig()
        val profile = if (profileId == "new") {
            val id = rememberSaveable { UUID.randomUUID().toString() }
            // Empty profiles are not inserted until a valid name can be auto-saved.
            remember(id) { MediaRequestProfile(id = id, name = "", mode = if (music) MediaGenerationMode.CUSTOM else MediaGenerationMode.DEFAULT) }
        } else {
            remember(profileId) { config.profiles(kind)[profileId] }
        }
        if (profile == null) {
            LaunchedEffect(profileId) { navController.popBackStack() }
        } else {
            key(profile.id) {
                MediaRequestProfileEditor(
                    provider = if (music) null else providers().firstOrNull { it.id == config.providerId },
                    config = config,
                    profile = profile,
                    video = video, kind = kind,
                    onAutoSave = { updated ->
                        val latest = toolSettings().system[toolName] ?: SystemToolConfig()
                        val current = latest.profiles(kind)
                        val selected = if (music) latest.activeMusicRequestProfileId else if (video) latest.activeVideoRequestProfileId
                            else latest.activeImageRequestProfileId
                        if (music) persistMusicProfiles(current + (updated.id to updated), selected)
                        else persistMediaProfiles(video, current + (updated.id to updated), selected)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
