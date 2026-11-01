package org.starfall.generative.features.home.chat.logic.tool

import org.starfall.generative.shared.model.resolveMediaProfile
import org.starfall.generative.shared.model.media.ContentApiMediaResult
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.media.modelIndependent
import org.starfall.generative.features.home.shared.logic.prepareToolResult
import org.starfall.generative.features.home.shared.logic.ToolSummary

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID
import org.starfall.generative.shared.helpers.media.mediaInputImageArgument
import org.starfall.generative.shared.helpers.media.mediaInputImageUrlArgument
import org.starfall.generative.shared.helpers.media.mediaInputImagesArgument
import org.starfall.generative.shared.helpers.media.mediaInputVideoArgument
import org.starfall.generative.shared.helpers.media.mediaInputVideoUrlArgument
import org.starfall.generative.shared.helpers.media.mediaToolSchema
import org.starfall.generative.shared.helpers.media.configuredMediaOptions
import org.starfall.generative.shared.helpers.media.effectiveMediaDisabledParams
import org.starfall.generative.shared.helpers.media.imagePreviewAspectRatio
import org.starfall.generative.shared.helpers.media.toolAttachmentReferences
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.mcp.McpSession
import org.starfall.generative.integrations.mcp.mcpToolWireName
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.REMOTE_TOOL_DISCONNECT
import org.starfall.generative.shared.model.REMOTE_TOOL_CLOSE_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_START_MACHINE
import org.starfall.generative.shared.model.REMOTE_TOOL_UPDATE_IP
import org.starfall.generative.shared.model.REMOTE_TOOL_LIST_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_READ_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_OPEN_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_EXECUTE
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineType
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.DEFAULT_AGENT_SKILL_ID
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.generation.hasToolCalls
import org.starfall.generative.shared.model.globalMcpToolEnabled
import org.starfall.generative.shared.model.isWebSearchProvider
import org.starfall.generative.shared.model.mcpServerEnabled
import org.starfall.generative.shared.model.configuredMcpTool
import org.starfall.generative.shared.model.configuredMcpArguments
import org.starfall.generative.shared.model.isContentApiPreset
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.language.sendsThinkingBack
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.streamEnabledFor
import org.starfall.generative.shared.model.remoteMachineToolEnabled
import org.starfall.generative.shared.model.withUniqueWireNames
import org.starfall.generative.shared.networking.ToolHttp
import org.starfall.generative.shared.networking.obj
import org.starfall.generative.shared.networking.requireObject
import org.starfall.generative.shared.networking.str
import org.starfall.generative.shared.networking.text
import org.starfall.generative.shared.networking.toolArguments
import org.starfall.generative.shared.storage.preferences.McpAttachmentSettings
import org.starfall.generative.features.home.chat.logic.SEND_FILE_TOOL_NAME
import org.starfall.generative.features.home.chat.logic.SendFileTool
import org.starfall.generative.features.home.chat.logic.fileDeliveryResult
import org.starfall.generative.features.home.chat.logic.sendFileDefinition
import org.starfall.generative.features.home.shared.logic.ToolExecutionService
import org.starfall.generative.features.home.shared.logic.ToolMediaGenerationService
import org.starfall.generative.features.home.shared.logic.ToolRequestService

private fun sentFileNames(result: JsonObject): List<String> {
    val listed = (result["files"] as? JsonArray).orEmpty().mapNotNull { value ->
        when (value) {
            is JsonObject -> value.text("uri")
            is JsonPrimitive -> value.content
            else -> null
        }.orEmpty().removePrefix("tool-file:").takeIf { it.isNotBlank() }
    }
    val legacy = result.text("uri").removePrefix("tool-file:").takeIf { it.isNotBlank() }
    return (listed + legacy.orEmpty()).distinct()
}

/** Resolve the preview shape from the same saved defaults and per-call overrides as the request.
 * Store it on the running activity so a subsequent settings change cannot resize the tile. */
internal fun imageToolPreviewRatio(
    rawArguments: String,
    toolSettings: ToolSettings,
    providers: List<ModelProvider>
): Float? {
    val args = runCatching { Json.parseToJsonElement(rawArguments) as? JsonObject }.getOrNull() ?: obj()
    val config = toolSettings.system["generate_image"] ?: return imagePreviewAspectRatio(args)
    val provider = providers.firstOrNull { it.id == config.providerId }
    val profile = toolSettings.resolveMediaProfile(false, provider?.config?.modelConfigs?.get(config.modelId))
    if (provider == null && !profile.modelIndependent) return imagePreviewAspectRatio(args)
    val defaults = if (profile.id == org.starfall.generative.shared.model.media.DEFAULT_IMAGE_GEN_PROFILE_ID &&
        profile.options.isEmpty()) config.imageOptions else profile.options
    val effective = if (profile.mode == MediaGenerationMode.DEFAULT) {
        configuredMediaOptions("generate_image", requireNotNull(provider), config.modelId, defaults, args,
            effectiveMediaDisabledParams(profile, "generate_image", config.disabledParams))
    } else defaults
    return imagePreviewAspectRatio(effective) ?: if (profile.mode == MediaGenerationMode.CUSTOM) {
        profile.chain.steps.firstNotNullOfOrNull { step ->
            runCatching { Json.parseToJsonElement(step.body) as? JsonObject }.getOrNull()
                ?.let(::imagePreviewAspectRatio)
        }
    } else null
}

/**
 * User-visible status labels for tool activity. English defaults keep plain text for callers
 * without a [android.content.Context]; the app wiring passes localized values.
 */
data class ToolChatLabels(
    val toolsReady: String = "Tools ready",
    val gettingMcpServers: String = "Getting MCP Servers",
    val connectingMcpServers: String = "Connecting to MCP Servers",
    val stopped: String = "Stopped",
    val fileSent: String = "File sent to chat.",
    val mediaDisplayed: (Int) -> String = { "Displayed $it media item(s) in chat." },
    val roundLimit: (Int) -> String = { "Stopped after $it tool rounds. Send another message to continue." },
)

/** Native structured tool calls, bounded rounds, no executable text extracted from answers. */
class ToolChatViewModel internal constructor(
    private val http: ToolHttp,
    private val mcp: McpService,
    private val aiService: AiService,
    private val remoteMachines: RemoteMachineService? = null,
    private val labels: ToolChatLabels = ToolChatLabels(),
    private val saveSkill: suspend (String, String, String) -> AgentSkill = { _, _, _ -> error("Skill storage unavailable") },
    private val availableSkills: suspend () -> List<AgentSkill> = { emptyList() },
    private val skillResources: suspend (String) -> List<String> = { emptyList() },
    private val readSkillResource: suspend (String, String) -> String = { _, _ -> error("Skill resource unavailable") }
) {
    private val toolRequest = ToolRequestService(http, aiService)
    private val mediaGeneration = ToolMediaGenerationService(http, aiService)
    private val toolExecution = ToolExecutionService(mcp, remoteMachines)
    /** Closes persistent Remote Machine SSH connections; called when the user switches chat. */
    fun disconnectRemoteMachines() {
        toolExecution.disconnectRemoteMachines()
    }

    fun generateMedia(
        provider: ModelProvider,
        model: String,
        kind: ModelType,
        prompt: String,
        imageOptions: JsonObject = obj(),
        attachments: List<String> = emptyList(),
        videoOptions: JsonObject = obj(),
        generationMode: MediaGenerationMode = MediaGenerationMode.DEFAULT,
        requestChain: org.starfall.generative.shared.model.media.MediaRequestChain? = null
    ): Flow<GenerationEvent> = flow {
        emitAll(mediaGeneration.generateMedia(
            aiService.resolveProvider(provider), model, kind, prompt, imageOptions, attachments,
            videoOptions, generationMode, requestChain
        ))
    }

    suspend fun completeText(
        provider: ModelProvider,
        model: String,
        messages: List<StoredMessage>,
        prompt: String,
        maxOutputTokens: Int? = null
    ): String = toolRequest.completeText(provider, model, messages, prompt, maxOutputTokens)

    fun generate(
        provider: ModelProvider,
        model: String,
        messages: List<StoredMessage>,
        prompt: String,
        servers: List<McpInfo>,
        providers: List<ModelProvider>,
        settings: () -> ToolSettings,
        maxToolRounds: () -> Int? = { null },
        takePendingMessage: () -> StoredMessage? = { null },
        mcpAttachmentSettings: () -> McpAttachmentSettings = { McpAttachmentSettings() },
        createSkillRequested: Boolean = false
    ): Flow<GenerationEvent> = channelFlow {
        val selectedProvider = aiService.resolveProvider(provider)
        val allSkills = availableSkills().filter {
            it.name.isNotBlank() && it.description.isNotBlank() && it.name != DEFAULT_AGENT_SKILL_ID
        }.distinctBy { it.name }
        // Only an explicit user invocation can bypass the search gate. No fuzzy
        // automatic matching: discovery is opt-in through agent_skills_search.
        val skills = skillsForCurrentTurn(allSkills, messages, createSkillRequested).toMutableList()
        val searchEnabled = settings().agentSkillsSearchEnabled && allSkills.isNotEmpty() && !createSkillRequested
        val catalog = skillCatalogPrompt(skills)
        // The bundled create-skill instructions are only loaded for explicit picker
        // selection, instead of being pasted into the user's message text.
        val createSkillInstructions = if (createSkillRequested) {
            availableSkills().firstOrNull { it.name == DEFAULT_AGENT_SKILL_ID }?.content.orEmpty()
        } else ""
        val effectivePrompt = listOf(prompt, catalog, createSkillInstructions)
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val activatedSkills = mutableSetOf<String>()
        // Prior successful activation remains visible in tool history on later user turns.
        // Keep its permissions for reading referenced files while that history is in context.
        messages.filter { it.role == ChatRole.MODEL }.forEach { message ->
            message.activeVersion.toolActivity.filter { it.wireName == ACTIVATE_SKILL_TOOL_NAME &&
                it.status == "success" }.forEach { activity ->
                runCatching { toolArguments(JsonPrimitive(activity.arguments)).text("name") }
                    .getOrNull()?.takeIf { name -> skills.any { it.name == name } }
                    ?.let(activatedSkills::add)
            }
        }
        val loadedThisGeneration = mutableSetOf<String>()
        var readableSkillResources = false
        val sessionKey = messages.firstOrNull { it.role == ChatRole.USER }?.id
        val sessions = mutableMapOf<String, McpSession>()
        val tools = mutableListOf<ToolDefinition>()
        val mcpEntries = mutableListOf<McpAttachments.Entry>()
        val searchable = mutableListOf<SearchableTool>()
        // Keep one catalog for a generation. The service still validates the selected machine at
        // execution time, so deleting a machine cannot redirect a call to another machine.
        val configuredMachines = toolExecution.configuredMachines()
        try {
            servers
                .filter { mcpServerEnabled(settings(), it.id) }
                .forEach { server ->
                    val activity = ToolActivity(UUID.randomUUID().toString(), "${server.name}: connect")
                    send(GenerationEvent.Tool(activity))
                    try {
                        val session = toolExecution.connect(server)
                        sessions[server.id] = session
                        val allDiscovered = toolExecution.discoverTools(session).map { tool ->
                            // Only the model-facing name changes. MCP calls still use originalName.
                            if (isWebSearchProvider(settings(), server.id)) {
                                tool.copy(name = mcpToolWireName("WebSearch", tool.originalName))
                            } else tool
                        }
                        val discovered = allDiscovered.filter {
                            globalMcpToolEnabled(settings(), server.id, it.originalName)
                        }
                        searchable += allDiscovered.map {
                            SearchableTool(it.name, globalMcpToolEnabled(settings(), server.id, it.originalName))
                        }
                        tools += discovered
                        if (discovered.isNotEmpty()) mcpEntries += McpAttachments.Entry(
                            serverId = server.id,
                            name = server.name,
                            tools = discovered,
                            alwaysAttached = isWebSearchProvider(settings(), server.id)
                        )
                        send(GenerationEvent.Tool(activity.copy(status = "success", summary = labels.toolsReady)))
                    } catch (e: CancellationException) {
                        send(GenerationEvent.Tool(activity.copy(status = "cancelled")))
                        throw e
                    } catch (e: Exception) {
                        send(GenerationEvent.Tool(activity.copy(status = "error", summary = e.message.orEmpty().take(500))))
                }
            }

            configuredMachines.forEach { machine ->
                    machine.supportedToolNames()
                        .filter { toolName -> remoteMachineToolEnabled(settings(), machine, toolName) }
                        .forEach { toolName ->
                        tools += ToolDefinition(
                            name = remoteToolName(machine, toolName),
                            description = when (toolName) {
                                REMOTE_TOOL_START_MACHINE ->
                                    "Start the Google Cloud Shell environment of the remote machine '${machine.name}' before using it."
                                REMOTE_TOOL_DISCONNECT ->
                                    "Close every terminal and the SSH connection to the remote machine '${machine.name}'."
                                REMOTE_TOOL_OPEN_TERMINAL ->
                                    "Open a new interactive terminal on '${machine.name}' and return its terminal_id."
                                REMOTE_TOOL_CLOSE_TERMINAL ->
                                    "Close one terminal on '${machine.name}'. If terminal_id is omitted, close the newest terminal."
                                REMOTE_TOOL_LIST_TERMINAL ->
                                    "List interactive terminals currently open on '${machine.name}'."
                                REMOTE_TOOL_READ_TERMINAL ->
                                    "Read the latest lines of output from an existing interactive terminal on '${machine.name}'. " +
                                        "Specify lines (1-1000); optionally supply terminal_id, otherwise use the newest terminal. " +
                                        "This does not execute a command or consume terminal output."
                                REMOTE_TOOL_UPDATE_IP ->
                                    "Refresh the SSH endpoint of the Google Cloud Shell remote machine '${machine.name}'. Use only after connecting to this machine reports an error."
                                else ->
                                    "Execute a command on an already open terminal on '${machine.name}' (${machine.type.label()}). " +
                                        "If terminal_id is omitted, use the newest terminal. Return concise commands and inspect the result."
                            },
                            schema = remoteToolSchema(toolName),
                            originalName = toolName,
                            remoteMachineId = machine.id
                        )
                    }
                }

            searchable += listOf(
                SearchableTool("generate_image", settings().system["generate_image"]?.enabled == true),
                SearchableTool("generate_video", settings().system["generate_video"]?.enabled == true),
                SearchableTool("generate_music", settings().system["generate_music"]?.let { it.enabled && it.activeMusicRequestProfile != null } == true),
                SearchableTool(SEND_FILE_TOOL_NAME, settings().sendFileEnabled),
                SearchableTool(SEARCH_TOOLS_TOOL_NAME, settings().searchToolsEnabled)
            )
            if (searchEnabled) searchable += SearchableTool(AGENT_SKILLS_SEARCH_TOOL_NAME, true)
            if (createSkillRequested) {
                tools += createSkillDefinition
                searchable += SearchableTool(CREATE_SKILL_TOOL_NAME, true)
            }
            if (searchEnabled) tools += agentSkillsSearchDefinition
            if (skills.isNotEmpty() || searchEnabled) {
                // Reserve names before disambiguating MCP tools; activation is hidden
                // until the user chooses a skill or an agent_skills_search succeeds.
                tools += activateSkillDefinition(skills.map { it.name })
                tools += readSkillResourceDefinition
                for (name in activatedSkills) {
                    if (skillResources(name).isNotEmpty()) {
                        readableSkillResources = true
                        break
                    }
                }
            }
            if (settings().searchToolsEnabled) tools += searchToolsDefinition
            settings().system["generate_music"]?.takeIf { it.enabled && it.activeMusicRequestProfile != null }?.let {
                tools += ToolDefinition("generate_music", "Generate music from a detailed prompt. The app saves and plays the resulting audio.",
                    mediaToolSchema("generate_music"))
            }
            listOf("generate_image", "generate_video").forEach { name ->
                val config = settings().system[name]
                if (config?.enabled == true) {
                    val mediaProvider = providers.find { it.id == config.providerId }
                    val mediaModel = mediaProvider?.config?.modelConfigs?.get(config.modelId)
                    val profile = settings().resolveMediaProfile(name == "generate_video", mediaModel)
                    val generationMode = profile.mode
                    val disabledParams = org.starfall.generative.shared.helpers.media.effectiveMediaDisabledParams(
                        profile, name, config.disabledParams
                    )
                    tools += ToolDefinition(
                        name = name,
                        description = if (name == "generate_image") {
                            "Generate or edit an image from a detailed prompt and optional attached/reference images. The app displays the saved image."
                        } else {
                            "Generate a video from a detailed prompt and an optional attached/reference image or video. The app displays the saved video."
                        },
                        schema = mediaToolSchema(
                            name,
                            if (generationMode == MediaGenerationMode.DEFAULT) disabledParams
                            else disabledParams + setOf("input_image_url", "input_video_url"),
                            if (generationMode == MediaGenerationMode.DEFAULT) mediaProvider else null,
                            if (generationMode == MediaGenerationMode.DEFAULT) config.modelId else ""
                        )
                    )
                }
            }

            if (tools.isEmpty()) {
                toolRequest.streamEvents(selectedProvider, model, messages, effectivePrompt).collect { send(it) }
                return@channelFlow
            }

            // Reserve native skill/tool names before disambiguating user-controlled MCP names.
            // Otherwise a remote tool called activate_skill could shadow the local loader.
            val nativeNames = setOf(CREATE_SKILL_TOOL_NAME, ACTIVATE_SKILL_TOOL_NAME,
                READ_SKILL_RESOURCE_TOOL_NAME, SEARCH_TOOLS_TOOL_NAME, AGENT_SKILLS_SEARCH_TOOL_NAME)
            // Keep the WebSearch_ names stable if an ordinary MCP exposes the same wire name.
            val pinnedSearchServerId = settings().run { if (webSearchEnabled) webSearchProviderId else null }
            val uniquelyNamedTools = tools.sortedBy { tool ->
                when {
                    tool.serverId == null && tool.remoteMachineId == null && tool.name in nativeNames -> 0
                    tool.serverId != null && tool.serverId == pinnedSearchServerId -> 1
                    else -> 2
                }
            }.withUniqueWireNames()
            tools.clear()
            tools += uniquelyNamedTools
            // Classic attaches every MCP tool; Dynamic lets the model load MCPs on demand with the internal tools.
            val mcpAttachments = McpAttachments(mcpEntries, mcpAttachmentSettings().normalized())
            if (mcpAttachments.active) tools += mcpAttachments.definitions()

            http.requireFiles()
            val history = mutableListOf<JsonObject>()
            if (selectedProvider.type != ModelApiType.GOOGLE && effectivePrompt.isNotBlank()) {
                history += obj("role" to str("system"), "content" to str(effectivePrompt))
            }
            val passbackConfig = selectedProvider.config.modelConfigs[model] ?: ModelConfiguration()
            // Auto passes earlier reasoning back only when this conversation already used tools. It also
            // switches on as soon as the current turn makes a tool call (see below).
            var sendThinkingContent = passbackConfig.sendsThinkingBack(messages.hasToolCalls())
            val importedAttachments = mutableMapOf<String, String>()
            // Rebuild which MCP tools are attached: connect keeps an MCP available for a few user messages, and using it extends that.
            val serverByWireName = tools.mapNotNull { tool -> tool.serverId?.let { tool.name to it } }.toMap()
            messages.forEach { message ->
                if (message.role == ChatRole.USER) mcpAttachments.startUserTurn()
                else if (mcpAttachments.active) message.activeVersion.toolActivity.forEach { activity ->
                    if (activity.wireName == CONNECT_MCPS_TOOL_NAME && activity.status == "success") {
                        runCatching { toolArguments(JsonPrimitive(activity.arguments)) }.getOrNull()?.let(mcpAttachments::replayLoad)
                    } else serverByWireName[activity.wireName]?.let(mcpAttachments::markUsed)
                }
                val activities = if (message.role == ChatRole.MODEL) {
                    // Connection handshakes are transport state, not conversation.
                    // With Send File off, earlier send_file calls are dropped too so the model never sees the tool.
                    message.activeVersion.toolActivity.filterNot {
                        it.name.endsWith(": connect") ||
                            (!settings().sendFileEnabled && it.wireName == SEND_FILE_TOOL_NAME) ||
                            // On-demand MCP tools are per generation, so earlier list/connect calls must not be replayed.
                            it.wireName in mcpDynamicToolNames ||
                            // Search discovery is per-turn; never replay searches into another task.
                            it.wireName == AGENT_SKILLS_SEARCH_TOOL_NAME ||
                            // A prior skill call is not permission to offer or replay skill
                            // tools for an unrelated new user request.
                            ((it.wireName == ACTIVATE_SKILL_TOOL_NAME ||
                                it.wireName == READ_SKILL_RESOURCE_TOOL_NAME) &&
                                skills.none { skill ->
                                    skill.name == runCatching {
                                        toolArguments(JsonPrimitive(it.arguments)).text("name")
                                    }.getOrNull()
                                }) ||
                            (it.wireName == CREATE_SKILL_TOOL_NAME && !createSkillRequested)
                    }
                } else emptyList()
                val replay = replayToolActivities(message.content, activities, http.requireFiles())
                val attachments = if (message.files.isEmpty()) JsonArray(emptyList()) else
                    aiService.toolAttachments(message, http.requireFiles(), importedAttachments)
                val tail = replay.trailingContent + toolAttachmentReferences(attachments)
                val wireMessages = replay.messages.toMutableList()
                // A stopped reply with no answer text and no tool calls would be an empty assistant message, which providers reject.
                val emptyModelReply = message.role == ChatRole.MODEL && replay.messages.isEmpty() && tail.isBlank()
                if ((replay.messages.isEmpty() && !emptyModelReply) || tail.isNotBlank()) {
                    val baseMessage = obj(
                        "role" to str(if (message.role == ChatRole.MODEL) "assistant" else "user"),
                        "content" to str(tail)
                    )
                    wireMessages += if (message.files.isEmpty()) baseMessage else
                        JsonObject(baseMessage + ("_attachments" to attachments))
                }
                val reasoning = message.reasoningContent?.takeIf {
                    sendThinkingContent && message.role == ChatRole.MODEL && it.isNotBlank()
                }
                val lastAssistant = wireMessages.indexOfLast { it.text("role") == "assistant" }
                if (reasoning != null && lastAssistant >= 0) {
                    var wireMessage = JsonObject(wireMessages[lastAssistant] + ("reasoning_content" to str(reasoning)))
                    message.reasoningSignature?.takeIf { it.isNotBlank() }?.let { signature ->
                        wireMessage = JsonObject(wireMessage + ("reasoning_signature" to str(signature)))
                    }
                    wireMessages[lastAssistant] = wireMessage
                }
                history += wireMessages
            }

            suspend fun appendUserMessage(message: StoredMessage) {
                val attachments = if (message.files.isEmpty()) JsonArray(emptyList()) else
                    aiService.toolAttachments(message, http.requireFiles(), importedAttachments)
                history += obj(
                    "role" to str("user"),
                    "content" to str(message.content + toolAttachmentReferences(attachments))
                ).let { base ->
                    if (message.files.isEmpty()) base else JsonObject(base + ("_attachments" to attachments))
                }
            }

            val budget = ToolBudget()
            val fileSender = SendFileTool(http)
            var fileDeliveryAvailable = false
            var round = 0
            // A null limit means unlimited rounds; the loop then ends only when the model stops calling tools.
            while (maxToolRounds().let { limit -> limit == null || round < limit }) {
                round++
                currentCoroutineContext().ensureActive()
                val allowed = tools.filter { tool ->
                    if (tool.remoteMachineId != null) {
                        configuredMachines.firstOrNull { it.id == tool.remoteMachineId }?.let { machine ->
                            remoteMachineToolEnabled(settings(), machine, tool.originalName)
                        } == true
                    } else if (tool.serverId == null) {
                        if (tool.name == CREATE_SKILL_TOOL_NAME) createSkillRequested
                        else if (tool.name == AGENT_SKILLS_SEARCH_TOOL_NAME) searchEnabled && settings().agentSkillsSearchEnabled
                        else if (tool.name == ACTIVATE_SKILL_TOOL_NAME) skills.isNotEmpty()
                        else if (tool.name == READ_SKILL_RESOURCE_TOOL_NAME) readableSkillResources
                        else if (tool.name == SEARCH_TOOLS_TOOL_NAME) settings().searchToolsEnabled
                        else if (tool.name == SEND_FILE_TOOL_NAME) fileDeliveryAvailable && settings().sendFileEnabled
                        else if (tool.name in mcpDynamicToolNames) mcpAttachments.active
                        else settings().system[tool.name]?.enabled == true
                    } else {
                        mcpServerEnabled(settings(), tool.serverId) &&
                            globalMcpToolEnabled(settings(), tool.serverId, tool.originalName) &&
                            mcpAttachments.includes(tool.serverId)
                    }
                }

                val streaming = selectedProvider.streamEnabledFor(model)
                var turnUsage: org.starfall.generative.shared.model.generation.TokenUsage? = null
                val turn = toolRequest.request(
                    selectedProvider,
                    model,
                    history,
                    allowed.map { mcpAttachments.present(configuredMcpTool(it, settings())) },
                    effectivePrompt,
                    sendThinking = sendThinkingContent,
                    onText = { send(GenerationEvent.Text(it)) },
                    onReasoning = { send(GenerationEvent.Reasoning(it)) },
                    onUsage = { turnUsage = it },
                    sessionKey = sessionKey
                )
                val reasoningSignature = turn.text("reasoning_signature").takeIf { it.isNotBlank() }
                if (!streaming) {
                    turn.text("reasoning_content").takeIf { it.isNotBlank() }?.let {
                        send(GenerationEvent.Reasoning(it, reasoningSignature))
                    }
                    turn.text("content").takeIf { it.isNotBlank() }?.let {
                        send(GenerationEvent.Text(it))
                    }
                } else if (reasoningSignature != null) {
                    send(GenerationEvent.Reasoning("", reasoningSignature))
                }
                turnUsage?.let { send(GenerationEvent.Usage(it)) }

                val calls = turn["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
                if (calls.isEmpty()) return@channelFlow
                check(calls.size <= 16) { "Too many tool calls in a single response" }
                // A turn that calls tools must keep its reasoning chain on the following requests
                // (Auto and On); Off never sends it.
                if (passbackConfig.sendsThinkingBack(hasToolCalls = true)) sendThinkingContent = true
                history += turn

                calls.forEach { value ->
                    val call = value.jsonObject
                    val function = call.requireObject("function")
                    val name = function.text("name")
                    val tool = allowed.find { it.name == name }
                    val rawArguments = when (val raw = function["arguments"]) {
                        null, JsonNull -> ""
                        is JsonPrimitive -> raw.content
                        else -> raw.toString()
                    }
                    val mediaTool = tool != null && tool.serverId == null && tool.remoteMachineId == null &&
                        name in setOf("generate_image", "generate_video", "generate_music")
                    val activity = ToolActivity(
                        id = UUID.randomUUID().toString(),
                        name = when (name) {
                            LIST_MCPS_TOOL_NAME -> labels.gettingMcpServers
                            CONNECT_MCPS_TOOL_NAME -> labels.connectingMcpServers
                            else -> tool?.let { definition ->
                                servers.find { it.id == definition.serverId }
                                    ?.let { "${it.name} / ${definition.originalName}" }
                                    ?: configuredMachines.find { it.id == definition.remoteMachineId }
                                        ?.let { "${it.name} / ${definition.originalName}" }
                                    ?: definition.originalName
                            } ?: name
                        },
                        wireName = name,
                        arguments = rawArguments,
                        previewAspectRatio = if (mediaTool && name == "generate_image") {
                            imageToolPreviewRatio(rawArguments, settings(), providers)
                        } else null
                    )
                    val contentApi = tool?.serverId?.let { serverId ->
                        servers.find { it.id == serverId }?.isContentApiPreset()
                    } == true
                    val mcpAttachmentTool = tool != null && tool.serverId == null && tool.remoteMachineId == null &&
                        name in mcpDynamicToolNames
                    send(GenerationEvent.Tool(activity))

                    var result: JsonObject
                    try {
                        require(tool != null) { "Tool is not enabled" }
                        // Listing and loading MCPs is bookkeeping and does not count against the per-message tool limit.
                        if (!mcpAttachmentTool) budget.consume(if (tool.serverId == null) name else null)
                        val args = toolArguments(function["arguments"])
                        result = if (tool.remoteMachineId != null) {
                            val machine = configuredMachines.firstOrNull { it.id == tool.remoteMachineId }
                                ?: error("Remote Machine is no longer configured")
                            check(remoteMachineToolEnabled(settings(), machine, tool.originalName)) {
                                "Remote Machine or tool disabled"
                            }
                            toolExecution.executeRemoteTool(machine, tool.originalName, args)
                        } else if (tool.serverId != null) {
                            check(
                                mcpServerEnabled(settings(), tool.serverId) &&
                                    globalMcpToolEnabled(settings(), tool.serverId, tool.originalName)
                            ) { "MCP tool disabled" }
                            mcpAttachments.markUsed(tool.serverId)
                            toolExecution.callMcpTool(
                                sessions[tool.serverId] ?: error("MCP session unavailable"),
                                tool.originalName, configuredMcpArguments(tool, args, settings())
                            )
                        } else if (name == SEARCH_TOOLS_TOOL_NAME) {
                            check(settings().searchToolsEnabled) { "Search Tools disabled" }
                            obj("tools" to searchToolCatalog(args.text("query"), searchable))
                        } else if (name == AGENT_SKILLS_SEARCH_TOOL_NAME) {
                            check(searchEnabled && settings().agentSkillsSearchEnabled) {
                                "Agent Skills Search is disabled"
                            }
                            val found = searchAgentSkills(args.text("query"), allSkills)
                            found.forEach { match ->
                                if (skills.none { it.name == match.name }) skills.add(match)
                            }
                            // Provider tool schemas must reflect exactly the discovered names.
                            val activationIndex = tools.indexOfFirst { it.name == ACTIVATE_SKILL_TOOL_NAME }
                            if (activationIndex >= 0) {
                                tools[activationIndex] = activateSkillDefinition(skills.map { it.name })
                            }
                            obj("skills" to JsonArray(found.map { match ->
                                obj("name" to str(match.name), "description" to str(match.description))
                            }))
                        } else if (name == ACTIVATE_SKILL_TOOL_NAME) {
                            val skillName = args.text("name")
                            val skill = skills.firstOrNull { it.name == skillName }
                                ?: error("Skill not found: $skillName")
                            if (skillName in loadedThisGeneration) {
                                obj("status" to str("already_loaded"), "name" to str(skillName))
                            } else {
                                val resourceNames = skillResources(skillName)
                                loadedThisGeneration.add(skillName)
                                activatedSkills.add(skillName)
                                if (resourceNames.isNotEmpty()) readableSkillResources = true
                                obj(
                                    "status" to str("success"),
                                    "name" to str(skillName),
                                    "instructions" to str(skill.content),
                                    "resources" to JsonArray(resourceNames.map(::str))
                                )
                            }
                        } else if (name == READ_SKILL_RESOURCE_TOOL_NAME) {
                            val skillName = args.text("name")
                            check(skillName in activatedSkills) { "Activate the skill before reading its resources" }
                            val path = args.text("path")
                            obj("status" to str("success"), "name" to str(skillName),
                                "path" to str(path), "content" to str(readSkillResource(skillName, path)))
                        } else if (name == CREATE_SKILL_TOOL_NAME) {
                            check(createSkillRequested) { "Create Skill not attached" }
                            val saved = saveSkill(
                                args.text("name"),
                                args.text("description"),
                                args.text("content")
                            )
                            obj("status" to str("success"), "name" to str(saved.name))
                        } else if (mcpAttachmentTool) {
                            mcpAttachments.execute(name, args)
                        } else if (name == SEND_FILE_TOOL_NAME) {
                            check(settings().sendFileEnabled) { "Send File tool disabled" }
                            fileSender.execute(args)
                        } else if (name == "generate_music") {
                            val cfg = settings().system[name] ?: error("Music tool is not configured")
                            check(cfg.enabled) { "Music tool disabled" }
                            val profile = cfg.activeMusicRequestProfile ?: error("Select a valid Custom music profile")
                            mediaGeneration.executeMusicTool(profile, args.text("prompt"))
                        } else {
                            val cfg = settings().system[name] ?: error("System tool is not configured")
                            check(cfg.enabled) { "System tool disabled" }
                            val configuredProvider = providers.find { it.id == cfg.providerId }
                            val mediaModel = configuredProvider?.config?.modelConfigs?.get(cfg.modelId)
                            val profile = settings().resolveMediaProfile(name == "generate_video", mediaModel)
                            val independent = profile.modelIndependent
                            val mediaProvider = if (independent) null else aiService.resolveProvider(
                                configuredProvider ?: error("Select a provider for this system tool")
                            )
                            val kind = if (name == "generate_image") ModelType.IMAGE_GENERATION
                                else ModelType.VIDEO_GENERATION
                            if (!independent) {
                                val selected = requireNotNull(mediaProvider)
                                check(selected.config.modelConfigs[cfg.modelId]?.modelType == kind &&
                                    selected.config.modelIds?.contains(cfg.modelId) != false
                                ) { "Select an available media model in Feature Tools" }
                            }
                            val generationMode = profile.mode
                             val builtIn = profile.id == org.starfall.generative.shared.model.media.defaultMediaProfile(name == "generate_video").id
                             val effectiveDisabled = org.starfall.generative.shared.helpers.media.effectiveMediaDisabledParams(
                                 profile, name, cfg.disabledParams
                             )
                             val effectiveOptions = if (builtIn && profile.options.isEmpty()) {
                                 if (name == "generate_image") cfg.imageOptions else cfg.videoOptions
                             } else profile.options
                            mediaGeneration.executeConfiguredTool(
                                name = name,
                                provider = mediaProvider,
                                model = if (independent) "" else cfg.modelId,
                                prompt = args.text("prompt"),
                                imageOptions = if (independent) effectiveOptions else configuredMediaOptions(
                                    name, requireNotNull(mediaProvider), cfg.modelId,
                                    if (name == "generate_image") effectiveOptions else cfg.imageOptions, args, effectiveDisabled
                                ),
                                inputImage = if ("input_image" in effectiveDisabled) null else mediaInputImageArgument(args),
                                inputImages = if ("input_images" in effectiveDisabled) emptyList() else mediaInputImagesArgument(args),
                                videoOptions = if (independent) effectiveOptions else configuredMediaOptions(
                                    name, requireNotNull(mediaProvider), cfg.modelId,
                                    if (name == "generate_video") effectiveOptions else cfg.videoOptions, args, effectiveDisabled
                                ),
                                inputImageUrl = if ("input_image_url" in effectiveDisabled) null else mediaInputImageUrlArgument(args),
                                inputVideo = if ("input_video" in effectiveDisabled) null else mediaInputVideoArgument(args),
                                inputVideoUrl = if ("input_video_url" in effectiveDisabled) null else mediaInputVideoUrlArgument(args),
                                 generationMode = generationMode,
                                 requestChain = if (generationMode == MediaGenerationMode.CUSTOM) profile.chain else null
                            )
                        }

                        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
                        val contentApiMedia = if (contentApi) resolveContentApiMedia(result, http) else null
                        if (contentApiMedia != null) result = contentApiMedia.content
                        val summary = if (contentApi || mediaTool) {
                            prepareToolResult(result, http.requireFiles())
                        } else {
                            prepareGenericToolResult(result)
                        }
                        // A tool's output is independent media, even when its chat is later deleted
                        // or its visual MediaBlock is removed. send_file only reuses existing files.
                        if (!isError && name != SEND_FILE_TOOL_NAME) {
                            http.requireFiles().markGeneratedMedia(
                                summary.files + contentApiMedia?.files.orEmpty()
                            )
                        }
                        // Keep the complete processed result as tool content; the preview below is UI metadata only.
                        val completeResponse = result.toString()
                        result = summary.content
                        if (mediaTool) {
                            // The model only learns that the media exists; file names and locations stay in the app.
                            val noun = if (name == "generate_music") "audio track" else if (name == "generate_image") "image" else "video"
                            result = obj(
                                "status" to str("success"),
                                "message" to str("Generated ${summary.files.size} $noun(s) and displayed them to the user in chat.")
                            )
                        }
                        if (tool.serverId != null && !contentApi) {
                            val availableFiles = (summary.files + contentApiMedia?.files.orEmpty()).distinct()
                            result = fileDeliveryResult(result, availableFiles, settings().sendFileEnabled)
                        }
                        send(
                            GenerationEvent.Tool(
                                activity.copy(
                                    status = if (isError) "error" else "success",
                                    summary = when {
                                        mcpAttachmentTool -> mcpAttachments.lastPreview
                                        name == SEND_FILE_TOOL_NAME && tool.serverId == null -> labels.fileSent
                                        contentApiMedia?.files?.isNotEmpty() == true ->
                                            labels.mediaDisplayed(contentApiMedia.files.size)
                                        else -> summary.preview
                                    },
                                    files = when {
                                        contentApiMedia?.files?.isNotEmpty() == true -> contentApiMedia.files
                                         tool.serverId != null -> summary.files.distinct()
                                        name == SEND_FILE_TOOL_NAME -> sentFileNames(result)
                                        else -> summary.files
                                    },
                                    inlineMedia = (mediaTool && name == "generate_music") || contentApiMedia?.files?.isNotEmpty() == true ||
                                        tool.serverId == null && name == SEND_FILE_TOOL_NAME,
                                    responsePreviewLimit = if (contentApi || mediaTool) 0 else 6_000,
                                    response = completeResponse
                                )
                            )
                        )
                    } catch (e: CancellationException) {
                        send(GenerationEvent.Tool(activity.copy(status = "cancelled", summary = labels.stopped)))
                        throw e
                    } catch (e: Exception) {
                        result = obj(
                            "isError" to JsonPrimitive(true),
                            "status" to str("error"),
                            "error" to str(e.message.orEmpty())
                        )
                        send(
                            GenerationEvent.Tool(
                                activity.copy(
                                    status = "error",
                                    summary = e.message.orEmpty(),
                                    response = result.toString()
                                )
                            )
                        )
                    }

                    val contentApiTool = tool?.serverId?.let { serverId ->
                        servers.find { it.id == serverId }?.isContentApiPreset()
                    } == true
                    if (tool?.serverId != null && !contentApiTool) {
                        if (!fileDeliveryAvailable) tools += sendFileDefinition
                        fileDeliveryAvailable = true
                    }
                    history += obj(
                        "role" to str("tool"),
                        "tool_call_id" to str(call.text("id")),
                        "name" to str(name),
                        "content" to str(result.toString())
                    )
                }

                // A pending user message belongs after the complete tool response(s),
                // before the next model request. This lets the model react to the new
                // instruction without waiting for the current tool loop to finish.
                val canStartAnotherRound = maxToolRounds().let { limit ->
                    limit == null || round < limit
                }
                if (canStartAnotherRound) {
                    takePendingMessage()?.let { pending ->
                        appendUserMessage(pending)
                        send(GenerationEvent.UserMessage(pending.copy(isQueued = false)))
                    }
                }
            }
            error(labels.roundLimit(round))
        } finally {
            sessions.values.forEach { it.close() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Tool names are visible to the model, so they use the machine name the user sees in the app
     * rather than its internal id, which stays in [ToolDefinition.remoteMachineId] for dispatch only.
     * Same-named machines are told apart later by withUniqueWireNames (name_2, name_3...).
     */
    private fun remoteToolName(machine: RemoteMachine, tool: String): String {
        val readable = java.text.Normalizer.normalize(machine.name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('đ', 'd').replace('Đ', 'D')
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .ifBlank { "unnamed" }
        val prefix = "machine_"
        val suffix = "_$tool"
        return prefix + readable.take((64 - prefix.length - suffix.length).coerceAtLeast(1)) + suffix
    }

    private fun RemoteMachineType.label(): String = when (this) {
        RemoteMachineType.SSH -> "SSH"
        RemoteMachineType.GCLOUD_SHELL -> "Google Cloud Shell"
    }

    private fun remoteToolSchema(tool: String): JsonObject = when (tool) {
        REMOTE_TOOL_START_MACHINE, REMOTE_TOOL_UPDATE_IP, REMOTE_TOOL_OPEN_TERMINAL, REMOTE_TOOL_LIST_TERMINAL, REMOTE_TOOL_DISCONNECT -> buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
        REMOTE_TOOL_CLOSE_TERMINAL -> buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("terminal_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional terminal ID. Without it, close the newest terminal.")
                })
            })
        }
        REMOTE_TOOL_READ_TERMINAL -> buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("lines", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of latest output lines to read, between 1 and 1000")
                    put("minimum", 1)
                    put("maximum", 1000)
                })
                put("terminal_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional open terminal ID. Without it, use the newest terminal.")
                })
            })
            put("required", JsonArray(listOf(JsonPrimitive("lines"))))
        }
        REMOTE_TOOL_EXECUTE -> buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "The shell command to run on the remote machine")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional timeout in seconds, from 1 to 600")
                })
                put("terminal_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional open terminal ID. Without it, use the newest terminal.")
                })
            })
            put("required", JsonArray(listOf(JsonPrimitive("command"))))
        }
        else -> error("Unknown Remote Machine tool: $tool")
    }


}
