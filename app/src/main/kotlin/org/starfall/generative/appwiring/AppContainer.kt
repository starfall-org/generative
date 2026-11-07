package org.starfall.generative.appwiring


import android.content.Context
import org.starfall.generative.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.starfall.generative.integrations.mcp.McpOAuthService
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.features.appdata.logic.AppDataTransferService
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.features.settings.logic.SettingsViewModel
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.integrations.files.AgentSkillsStore
import org.starfall.generative.shared.subfeatures.icons.logic.IconStore
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLog
import org.starfall.generative.integrations.speech.SpeechAudioPlayer
import org.starfall.generative.integrations.speech.SpeechSynthesisService
import org.starfall.generative.integrations.speech.TtsHelper
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.DEFAULT_AGENT_SKILL_ID
import org.starfall.generative.shared.model.normalizeAgentSkillName
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.repositories.McpRepository
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.repositories.ProfileRepository
import org.starfall.generative.database.repositories.SpeechRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.database.preferences.ToolSettingsStore
import org.starfall.generative.database.preferences.NotificationPermissionStore
import org.starfall.generative.R
import org.starfall.generative.features.home.chat.logic.tool.ToolChatLabels
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.home.chat.logic.markdown.PersistentMarkdownAstCache

/** Application-scoped dependencies. ViewModels receive only their required dependencies. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val aiService = AiService(appContext)
    val toolFiles = ToolFiles(appContext)
    private val toolHttp = ToolHttp(toolFiles)
    private val mcpOAuth = McpOAuthService(appContext, toolHttp, contentApiMcpUrl = BuildConfig.CONTENT_API_MCP_URL)
    private val mcpService = McpService(toolHttp, mcpOAuth, contentApiMcpUrl = BuildConfig.CONTENT_API_MCP_URL)
    private val conversations = ConversationRepository(database, File(appContext.noBackupFilesDir, "pending-conversation-saves"), toolFiles)
    private val profiles = ProfileRepository(database)
    private val icons = IconStore(appContext)
    private val providers = ModelRepository(database, aiService, icons)
    private val mcp = McpRepository(database, mcpService, icons, mcpOAuth, BuildConfig.CONTENT_API_MCP_URL)
    private val speech = SpeechRepository(database)
    private val preferences = AppPreferencesRepository(appContext)
    private val toolSettings = ToolSettingsStore(appContext)
    val notificationPermissionStore = NotificationPermissionStore(appContext)
    val remoteMachineService = RemoteMachineService(appContext)
    val appDataTransfer = AppDataTransferService(
        appContext, providers, mcp, speech, profiles, conversations, preferences, toolSettings,
        icons, toolFiles, remoteMachineService
    )
    val defaultDataInitializer = DefaultDataInitializer(providers, mcp, speech, preferences, icons)

    init {
        PersistentMarkdownAstCache.get(appContext)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { conversations.recoverPendingSaves() }
        // Keep the debug log's on/off state and scopes in step with App settings, from process start.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            preferences.appPreferencesFlow.collect { DebugLog.configure(it.debugMode, it.debugLogCategories, it.debugLogLevel) }
        }
        aiService.providerLoader = providers::resolveProvider
        mcpService.serverLoader = mcp::resolveServer
    }

    private val skillStore = AgentSkillsStore(appContext)

    val viewModelFactory = viewModelFactory {
        initializer {
            ChatViewModel(
                conversations,
                providers,
                mcp,
                preferences,
                ToolChatViewModel(toolHttp, mcpService, aiService, remoteMachineService, toolChatLabels(appContext),
                    saveSkill = { name, description, content ->
                        val cleanName = normalizeAgentSkillName(name)
                        require(cleanName != DEFAULT_AGENT_SKILL_ID) {
                            "The bundled create-skill skill cannot be overwritten"
                        }
                        val cleanDescription = description.trim().take(1024)
                        val cleanContent = content.trim().take(32000)
                        require(cleanName.isNotBlank() && cleanDescription.isNotBlank() && cleanContent.isNotBlank()) {
                            "Skill name, description and content are required"
                        }
                        val skill = AgentSkill(
                            cleanName, cleanName, cleanContent, cleanDescription
                        )
                        val current = preferences.appPreferencesFlow.first().agentSkills.skills
                        require(current.none { it.name == cleanName }) {
                            "A skill named $cleanName already exists"
                        }
                        preferences.setAgentSkills(current + skill)
                        skill
                    },
                    // Re-discover from files for each agent turn (including external SKILL.md edits).
                    availableSkills = { listOf(skillStore.readBundledCreateSkill(appContext)) + skillStore.readBlocking() },
                    skillResources = { name -> skillStore.listResources(appContext, name) },
                    readSkillResource = { name, path -> skillStore.readResource(appContext, name, path) }),
                toolSettings,
                speech,
                TtsHelper(appContext),
                SpeechSynthesisService(providers::resolveProvider),
                SpeechAudioPlayer(appContext),
                toolFiles,
                serversideService = remoteMachineService,
                speechNotification = org.starfall.generative.integrations.speech.SpeechPlaybackNotification(appContext)
            )
        }
        initializer { ConfigurationViewModel(profiles, providers, mcp, speech, preferences) }
        initializer { SettingsViewModel(preferences) }
        initializer { RemoteMachinesViewModel(remoteMachineService) }
    }
}

/** Localized tool status labels; English sources live in strings.xml. */
private fun toolChatLabels(context: Context) = ToolChatLabels(
    toolsReady = context.getString(R.string.tool_status_ready),
    gettingMcpServers = context.getString(R.string.tool_status_getting_mcp_servers),
    connectingMcpServers = context.getString(R.string.tool_status_connecting_mcp_servers),
    stopped = context.getString(R.string.tool_status_stopped),
    fileSent = context.getString(R.string.tool_summary_file_sent),
    mediaDisplayed = { count -> context.getString(R.string.tool_summary_media_displayed, count) },
    roundLimit = { rounds -> context.getString(R.string.tool_round_limit_reached, rounds) },
)
