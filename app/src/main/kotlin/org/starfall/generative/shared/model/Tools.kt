package org.starfall.generative.shared.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.media.ImageGenProfile
import org.starfall.generative.shared.model.media.VideoGenProfile
import org.starfall.generative.shared.model.media.withDefaultMediaProfile
import org.starfall.generative.shared.model.media.validMusicProfile
import org.starfall.generative.shared.model.media.modelIndependent
import org.starfall.generative.shared.model.media.hasUnsafeMusicHeaders

@Serializable
data class McpAccess(val enabled: Boolean = false, val tools: Map<String, Boolean> = emptyMap())

@Serializable
data class SystemToolConfig(
    val enabled: Boolean = false,
    val providerId: String = "",
    val modelId: String = "",
    val prompt: String = "",
    /** Minimum user+assistant message count before title generation is attempted. */
    val titleGenerationMinMessages: Int = DEFAULT_TITLE_GENERATION_MIN_MESSAGES,
    /** Minimum words in the first user message; zero disables this early trigger. */
    val titleGenerationMinWords: Int = DEFAULT_TITLE_GENERATION_MIN_WORDS,
    val imageOptionsByModel: Map<String, JsonObject> = emptyMap(),
    val videoOptionsByModel: Map<String, JsonObject> = emptyMap(),
    val imageGenerationMode: MediaGenerationMode = MediaGenerationMode.DEFAULT,
    val videoGenerationMode: MediaGenerationMode = MediaGenerationMode.DEFAULT,
    val imageRequestProfiles: Map<String, MediaRequestProfile> = emptyMap(),
    val videoRequestProfiles: Map<String, MediaRequestProfile> = emptyMap(),
    val activeImageRequestProfileId: String = "",
    val activeVideoRequestProfileId: String = "",
    val musicRequestProfiles: Map<String, MediaRequestProfile> = emptyMap(),
    val activeMusicRequestProfileId: String = "",
    /** Optional media-tool parameters hidden from the model and ignored if it passes them anyway. */
    val disabledParams: Set<String> = emptySet()
) {
    val activeMusicRequestProfile: MediaRequestProfile?
        get() = musicRequestProfiles[activeMusicRequestProfileId]?.takeIf { it.validMusicProfile() }

    fun profiles(kind: org.starfall.generative.shared.model.media.MediaKind): Map<String, MediaRequestProfile> =
        if (kind == org.starfall.generative.shared.model.media.MediaKind.MUSIC) musicRequestProfiles.filterValues { it.mode == MediaGenerationMode.CUSTOM }.mapValues { (id, profile) -> profile.copy(id = id) }
        else mediaProfiles(kind == org.starfall.generative.shared.model.media.MediaKind.VIDEO)

    fun withMusicProfiles(profiles: Map<String, MediaRequestProfile>, activeId: String): SystemToolConfig {
        val custom = profiles.filterValues { it.mode == MediaGenerationMode.CUSTOM }.mapValues { (id, profile) -> profile.copy(id = id) }
        val selected = activeId.takeIf { it in custom }.orEmpty()
        return copy(musicRequestProfiles = custom, activeMusicRequestProfileId = selected,
            enabled = enabled && selected.isNotBlank())
    }

    val imageOptions: JsonObject
        get() = imageOptionsByModel["$providerId/$modelId"] ?: JsonObject(emptyMap())

    fun withImageOptions(options: JsonObject) =
        copy(imageOptionsByModel = imageOptionsByModel + ("$providerId/$modelId" to options))

    val videoOptions: JsonObject
        get() = videoOptionsByModel["$providerId/$modelId"] ?: JsonObject(emptyMap())

    fun withVideoOptions(options: JsonObject) =
        copy(videoOptionsByModel = videoOptionsByModel + ("$providerId/$modelId" to options))

    val activeImageRequestProfile: MediaRequestProfile?
        get() = imageRequestProfiles[activeImageRequestProfileId]

    fun withImageRequestProfiles(profiles: Map<String, MediaRequestProfile>, activeId: String) =
        copy(imageRequestProfiles = profiles, activeImageRequestProfileId = activeId, imageGenerationMode = MediaGenerationMode.CUSTOM)

    val activeVideoRequestProfile: MediaRequestProfile?
        get() = videoRequestProfiles[activeVideoRequestProfileId]

    fun withVideoRequestProfiles(profiles: Map<String, MediaRequestProfile>, activeId: String) =
        copy(videoRequestProfiles = profiles, activeVideoRequestProfileId = activeId, videoGenerationMode = MediaGenerationMode.CUSTOM)

    /** Domain-specific views retain the old JSON keys for saved configuration compatibility. */
    val imageGenProfiles: Map<String, ImageGenProfile> get() = mediaProfiles(false)
    val videoGenProfiles: Map<String, VideoGenProfile> get() = mediaProfiles(true)

    /** Every model resolves its own profile; an unset/missing id uses the first Default. */
    fun mediaProfiles(video: Boolean): Map<String, MediaRequestProfile> =
        (if (video) videoRequestProfiles else imageRequestProfiles).withDefaultMediaProfile(video)

    fun mediaProfile(video: Boolean, profileId: String): MediaRequestProfile =
        mediaProfiles(video).let { library ->
            library[profileId] ?: library.values.first { it.mode == MediaGenerationMode.DEFAULT }
        }

    fun selectedMediaProfile(video: Boolean, model: ModelConfiguration?): MediaRequestProfile {
        val library = mediaProfiles(video)
        val activeId = if (video) activeVideoRequestProfileId else activeImageRequestProfileId
        if (model == null) {
            // Tool-level selection is valid without a model only for independent Custom chains.
            return library[activeId]?.takeIf { it.modelIndependent } ?: mediaProfile(video, "")
        }
        library[model.mediaRequestProfileId]?.takeIf { !it.modelIndependent }?.let { return it }
        // Legacy model-bound Custom selections on the tool, before per-model metadata existed.
        if (model.mediaRequestProfileId.isBlank() && model.mediaApiType == MediaGenerationMode.CUSTOM) {
            library[activeId]?.takeIf { !it.modelIndependent }?.let { return it }
        }
        return mediaProfile(video, "")
    }

    /** Select a profile at the tool level, clearing a model for standalone chains. */
    fun selectMediaProfile(video: Boolean, profile: MediaRequestProfile): SystemToolConfig {
        require(mediaProfiles(video)[profile.id] == profile) { "Unknown media profile" }
        require(profile.modelIndependent || (providerId.isNotBlank() && modelId.isNotBlank())) {
            "Select a model before choosing this profile"
        }
        val updated = if (video) copy(
            activeVideoRequestProfileId = profile.id, videoGenerationMode = profile.mode
        ) else copy(activeImageRequestProfileId = profile.id, imageGenerationMode = profile.mode)
        return if (profile.modelIndependent) updated.copy(providerId = "", modelId = "") else updated
    }

    /** Switching back to a model must clear an incompatible standalone selection. */
    fun selectMediaModel(video: Boolean, provider: String, model: String): SystemToolConfig {
        val current = selectedMediaProfile(video, null)
        val next = copy(providerId = provider, modelId = model)
        if (!current.modelIndependent) return next
        val default = mediaProfile(video, "")
        return if (video) next.copy(activeVideoRequestProfileId = default.id, videoGenerationMode = default.mode)
            else next.copy(activeImageRequestProfileId = default.id, imageGenerationMode = default.mode)
    }

    fun withMediaProfiles(video: Boolean, profiles: Map<String, MediaRequestProfile>): SystemToolConfig {
        val normalized = profiles.withDefaultMediaProfile(video)
        val currentId = if (video) activeVideoRequestProfileId else activeImageRequestProfileId
        val updated = if (video) copy(videoRequestProfiles = normalized)
            else copy(imageRequestProfiles = normalized)
        val current = normalized[currentId]
        if (current?.modelIndependent == true) return updated.copy(providerId = "", modelId = "")
        if ((providerId.isBlank() || modelId.isBlank()) && currentId.isNotBlank() &&
            currentId != org.starfall.generative.shared.model.media.defaultMediaProfile(video).id) {
            val defaultId = org.starfall.generative.shared.model.media.defaultMediaProfile(video).id
            return if (video) updated.copy(activeVideoRequestProfileId = defaultId, videoGenerationMode = MediaGenerationMode.DEFAULT)
                else updated.copy(activeImageRequestProfileId = defaultId, imageGenerationMode = MediaGenerationMode.DEFAULT)
        }
        return updated
    }

    fun withImageGenerationMode(mode: MediaGenerationMode) = copy(imageGenerationMode = mode)

    fun withVideoGenerationMode(mode: MediaGenerationMode) = copy(videoGenerationMode = mode)
}

@Serializable
data class ToolSettings(
    val system: Map<String, SystemToolConfig> = emptyMap(),
    val quickMcp: Map<String, Boolean> = emptyMap(),
    /** The MCP selected to provide web-search tools, or null when no provider is pinned. */
    val webSearchProviderId: String? = null,
    /** Whether the pinned web-search MCP is available to the language model. */
    val webSearchEnabled: Boolean = false,
    val mcpTools: Map<String, Map<String, Boolean>> = emptyMap(),
    /** Per-server, per-tool parameter visibility and optional default values. */
    val mcpToolParameters: Map<String, Map<String, Map<String, McpToolParameterConfig>>> = emptyMap(),
    /** Master switch for Remote Machine tools in the chat tool picker. */
    val machinesEnabled: Boolean = false,
    /** Per-machine switches shown in Remote Machines. Entries override the legacy master switch. */
    val enabledMachines: Map<String, Boolean> = emptyMap(),
    /** Legacy per-machine tool switches retained for decoding older settings. */
    val machineTools: Map<String, Map<String, Boolean>> = emptyMap(),
    /** Lets the AI display files in chat through send_file. On unless the user turns it off. */
    val sendFileEnabled: Boolean = true,
    /** Optional built-in tool allowing the model to find tool names and enablement. */
    val searchToolsEnabled: Boolean = false,
    /** Opt-in discovery; Agent Skills stay detached until a search or explicit user selection. */
    val agentSkillsSearchEnabled: Boolean = false
)

/** Resolves a media model's profile from the shared library, including legacy selections. */
fun ToolSettings.resolveMediaProfile(video: Boolean, model: ModelConfiguration?): MediaRequestProfile {
    val toolName = if (video) "generate_video" else "generate_image"
    val configured = system[toolName] ?: SystemToolConfig()
    val id = model?.mediaRequestProfileId.orEmpty()
    if (id.isNotEmpty()) {
        system.values.forEach { config ->
            val match = (if (video) config.videoRequestProfiles else config.imageRequestProfiles)[id]
            if (match != null && !match.modelIndependent) return match
        }
        return configured.mediaProfile(video, "")
    }
    return configured.selectedMediaProfile(video, model)
}

@Serializable
data class ToolActivity(
    val id: String,
    val name: String,
    /** Function name the model used for this call; [name] is the display name. */
    val wireName: String = "",
    val status: String = "running",
    val summary: String = "",
    val files: List<String> = emptyList(),
    val arguments: String = "",
    val response: String = "",
    val contentOffset: Int = 0,
    val reasoningOffset: Int? = null,
    val inlineMedia: Boolean = false,
    /** Non-zero marks a generic MCP tool response (not media / Content-API). It does not limit [response]. */
    val responsePreviewLimit: Int = 0,
    /** Keeps distinct Actions groups when editing moves them to one Content offset. */
    val actionGroup: Int? = null,
    /** In-flight preview shape from effective image options. Room does not persist pending UI. */
    @kotlinx.serialization.Transient val previewAspectRatio: Float? = null,
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val serverId: String? = null,
    val originalName: String = name,
    /** A tool owned by a Remote Machine, mutually exclusive with [serverId]. */
    val remoteMachineId: String? = null
)


@Serializable
data class McpToolParameterConfig(
    val enabled: Boolean = true,
    /** Stored as text in the editor and converted to the parameter's schema type when called. */
    val defaultValue: String = ""
)

fun configuredMcpTool(tool: ToolDefinition, settings: ToolSettings): ToolDefinition {
    val serverId = tool.serverId ?: return tool
    val overrides = settings.mcpToolParameters[serverId]?.get(tool.originalName).orEmpty()
    if (overrides.isEmpty()) return tool
    val properties = tool.schema["properties"] as? JsonObject ?: return tool
    val required = (tool.schema["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    val visibleProperties = buildJsonObject {
        properties.forEach { (name, schema) ->
            val config = overrides[name]
            if (config?.enabled != false) put(name, schema)
        }
    }
    val visibleRequired = required.filter { name -> overrides[name]?.enabled != false }
    val schema = buildJsonObject {
        tool.schema.forEach { (key, value) ->
            when (key) {
                "properties" -> put(key, visibleProperties)
                "required" -> if (visibleRequired.isNotEmpty()) put(key, JsonArray(visibleRequired.map(::JsonPrimitive)))
                else -> put(key, value)
            }
        }
        if ("properties" !in tool.schema) put("properties", visibleProperties)
    }
    return tool.copy(schema = schema)
}

fun configuredMcpArguments(tool: ToolDefinition, arguments: JsonObject, settings: ToolSettings): JsonObject {
    val serverId = tool.serverId ?: return arguments
    val overrides = settings.mcpToolParameters[serverId]?.get(tool.originalName).orEmpty()
    if (overrides.isEmpty()) return arguments
    val properties = tool.schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    return buildJsonObject {
        arguments.forEach { (name, value) ->
            val config = overrides[name]
            if (config?.enabled != false) put(name, value)
        }
        overrides.forEach { (name, config) ->
            if (config.defaultValue.isBlank() || arguments.containsKey(name)) return@forEach
            val type = (properties[name] as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
            val value = runCatching {
                when (type) {
                    "integer" -> JsonPrimitive(config.defaultValue.trim().toLong())
                    "number" -> JsonPrimitive(config.defaultValue.trim().toDouble())
                    "boolean" -> JsonPrimitive(config.defaultValue.trim().toBooleanStrict())
                    "object", "array" -> Json.parseToJsonElement(config.defaultValue)
                    else -> JsonPrimitive(config.defaultValue)
                }
            }.getOrElse { JsonPrimitive(config.defaultValue) }
            put(name, value)
        }
    }
}

/**
 * Server and machine names are user-controlled, so two entries can still produce the same readable
 * wire name after sanitization. Disambiguate only those collisions with a plain ordinal
 * (name, name_2, name_3...); the normal name stays untouched. Internal ids are never used here
 * because wire names are shown to the model.
 */
internal fun List<ToolDefinition>.withUniqueWireNames(): List<ToolDefinition> {
    val used = mutableSetOf<String>()
    return map { definition ->
        var name = definition.name
        var ordinal = 1
        while (!used.add(name)) {
            ordinal++
            val suffix = "_$ordinal"
            name = definition.name.take(64 - suffix.length) + suffix
        }
        if (name == definition.name) definition else definition.copy(name = name)
    }
}

fun systemMediaToolAvailable(
    name: String,
    config: SystemToolConfig,
    providers: List<ModelProvider>,
    mode: MediaGenerationMode = if (name == "generate_image") config.imageGenerationMode else config.videoGenerationMode
): Boolean {
    if (name == "generate_music") return config.activeMusicRequestProfile != null
    if (config.providerId.isBlank() || config.modelId.isBlank()) return false
    val requiredType = when (name) {
        "generate_image" -> ModelType.IMAGE_GENERATION
        "generate_video" -> ModelType.VIDEO_GENERATION
        else -> return false
    }
    val provider = providers.find { it.id == config.providerId } ?: return false
    if (mode == MediaGenerationMode.DEFAULT && !provider.type.isOpenAi && provider.type != ModelApiType.GOOGLE) return false
    val model = provider.config.modelConfigs[config.modelId] ?: return false
    return model.modelType == requiredType && provider.config.modelIds?.contains(config.modelId) != false
}

fun globalMcpToolEnabled(settings: ToolSettings, serverId: String, name: String): Boolean =
    settings.mcpTools[serverId]?.get(name) != false

fun isWebSearchProvider(settings: ToolSettings, serverId: String): Boolean =
    settings.webSearchProviderId == serverId

fun mcpServerEnabled(settings: ToolSettings, serverId: String): Boolean =
    if (isWebSearchProvider(settings, serverId)) settings.webSearchEnabled else settings.quickMcp[serverId] != false

fun remoteMachineEnabled(settings: ToolSettings, machine: org.starfall.generative.shared.model.RemoteMachine): Boolean =
    if (settings.enabledMachines.isNotEmpty()) {
        settings.enabledMachines[machine.id] == true
    } else {
        settings.machinesEnabled && machine.aiToolsEnabled
    }

/**
 * Atomically replace the active Remote Machine for chat tools. Explicit false entries ensure that
 * deselecting also disables the older machinesEnabled fallback for every existing machine.
 */
fun ToolSettings.withSelectedRemoteMachine(machineId: String?, knownMachineIds: List<String>): ToolSettings {
    require(machineId == null || machineId in knownMachineIds)
    val ids = enabledMachines.keys + knownMachineIds
    return copy(
        enabledMachines = ids.associateWith { it == machineId },
        machinesEnabled = machineId != null
    )
}

/** One machine is presented as selected in the drawer. Legacy multi-enabled settings show the first. */
fun selectedRemoteMachine(settings: ToolSettings, machines: List<RemoteMachine>): RemoteMachine? =
    machines.firstOrNull { remoteMachineEnabled(settings, it) }

fun remoteMachineToolEnabled(
    settings: ToolSettings,
    machine: org.starfall.generative.shared.model.RemoteMachine,
    toolName: String
): Boolean = remoteMachineEnabled(settings, machine) &&
    machine.isToolEnabled(toolName)

fun toolAllowed(
    access: McpAccess?,
    quick: Boolean?,
    globalEnabled: Boolean,
    name: String
): Boolean =
    access?.enabled == true && quick != false && globalEnabled && (access.tools[name] != false)

const val DEFAULT_TITLE_GENERATION_PROMPT = "Generate a concise title for this conversation. Return only the title, without quotation marks or extra commentary. Keep it under 8 words."
const val DEFAULT_CHAT_SUMMARY_PROMPT = "Summarize the conversation faithfully for use as future context. Preserve user goals, decisions, constraints, important facts, code or technical details, unresolved issues, and commitments. Remove repetition and incidental chatter. Do not invent information."
const val DEFAULT_TITLE_GENERATION_MIN_MESSAGES = 5
const val MIN_TITLE_GENERATION_MIN_MESSAGES = 1
const val MAX_TITLE_GENERATION_MIN_MESSAGES = 50
const val DEFAULT_TITLE_GENERATION_MIN_WORDS = 10
const val MIN_TITLE_GENERATION_MIN_WORDS = 0
const val MAX_TITLE_GENERATION_MIN_WORDS = 1000

/** Reject unsafe music imports before any plaintext credentials reach persisted settings. */
fun ToolSettings.validateMusicSecrets(): ToolSettings = also {
    system["generate_music"]?.musicRequestProfiles?.values?.forEach { profile ->
        require(profile.mode == MediaGenerationMode.CUSTOM) { "Music imports must contain Custom profiles only." }
        require(!profile.hasUnsafeMusicHeaders()) { "Music authentication headers must reference the protected profile credential." }
        require(profile.encryptedCredential.isBlank() || profile.encryptedCredential.startsWith("keystore:v1:")) {
            "Music credentials must use a protected credential envelope. Enter the API key in the profile editor."
        }
    }
}
