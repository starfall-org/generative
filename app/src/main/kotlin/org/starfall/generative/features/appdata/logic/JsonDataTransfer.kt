package org.starfall.generative.features.appdata.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.starfall.generative.features.appdata.model.FullAppDataExport
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.BuildConfig
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.isContentApiPreset
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup

private val exportJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun buildJsonExport(
    providers: List<ModelProvider>,
    mcpServers: List<McpInfo>,
    speechServices: List<SpeechService>,
    conversations: List<Conversation>,
    providerGroups: List<ProviderGroup> = emptyList()
): String = exportJson.encodeToString(buildJsonObject {
    put("formatVersion", 1)
    put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
    put("type", "full_backup")
    put("providers", exportJson.parseToJsonElement(exportJson.encodeToString(providers)))
    put("providerGroups", exportJson.parseToJsonElement(exportJson.encodeToString(providerGroups)))
    put("mcpServers", exportJson.parseToJsonElement(exportJson.encodeToString(
        mcpServers.map { it.withoutBundledEndpoint() }
    )))
    put("speechServices", exportJson.parseToJsonElement(exportJson.encodeToString(speechServices)))
    put("conversations", exportJson.parseToJsonElement(exportJson.encodeToString(conversations)))
})

/** Reads current full backups and every legacy per-category JSON backup format. */
fun parseFullAppDataExport(raw: String, fileNameHint: String? = null): FullAppDataExport {
    val rootElement = exportJson.parseToJsonElement(raw.trim())
    if (rootElement is JsonArray) return inferLegacyArray(rootElement, fileNameHint)

    val root = rootElement.jsonObject
    val version = root["formatVersion"]?.jsonPrimitive?.int
    require(version == null || version == 1) { "Unsupported JSON backup version." }
    val type = root["type"]?.jsonPrimitive?.content
        ?: root["exportType"]?.jsonPrimitive?.content
        ?: root["kind"]?.jsonPrimitive?.content

    // Current consolidated backups and older partial object backups with named collections.
    if (type == null || normalizeType(type) in FULL_BACKUP_TYPES) {
        val providers = root["providers"]
        val groups = root["providerGroups"] ?: root["groups"]
        val mcp = root["mcpServers"] ?: root["mcp"]
        val speech = root["speechServices"] ?: root["speech_services"]
        val conversations = root["conversations"] ?: root["chats"]
        require(listOf(providers, groups, mcp, speech, conversations).any { it != null }) {
            "The JSON file does not contain recognizable Generative backup data."
        }
        return FullAppDataExport(
            providers = providers?.decode(),
            providerGroups = groups?.decode(),
            mcpServers = mcp?.decode(),
            speechServices = speech?.decode(),
            conversations = conversations?.decode()
        )
    }

    require(version == 1) { "Unsupported JSON export version." }
    // Legacy exports used { formatVersion, type, items }, while some intermediate builds
    // used `data` or a category-named property instead of `items`.
    val normalizedType = normalizeType(type)
    val categoryItems = root["items"] ?: root["data"] ?: root[categoryKey(normalizedType)]
        ?: error("The JSON export has no items.")
    return parseLegacyCategory(root, normalizedType, categoryItems)
}

private val FULL_BACKUP_TYPES = setOf("full_backup", "fullbackup", "all_data", "backup")

private fun normalizeType(value: String): String = value.trim()
    .lowercase(Locale.ROOT)
    .replace('-', '_')
    .replace(' ', '_')

private fun categoryKey(type: String): String? = when (type) {
    "provider", "providers" -> "providers"
    "group", "groups", "provider_group", "provider_groups" -> "providerGroups"
    "mcp", "mcp_server", "mcp_servers" -> "mcpServers"
    "speech", "speech_service", "speech_services" -> "speechServices"
    "chat", "chats", "conversation", "conversations" -> "conversations"
    else -> null
}

private inline fun <reified T> JsonElement.decode(): List<T> = exportJson.decodeFromString(toString())

private fun parseLegacyCategory(
    root: JsonObject,
    type: String,
    items: JsonElement
): FullAppDataExport = when (type) {
    "provider", "providers" -> FullAppDataExport(
        providers = items.decode<ModelProvider>(),
        providerGroups = (root["groups"] ?: root["providerGroups"])?.decode(),
        speechServices = root["speechServices"]?.decode()
    )
    "group", "groups", "provider_group", "provider_groups" ->
        FullAppDataExport(providerGroups = items.decode<ProviderGroup>())
    "mcp", "mcp_server", "mcp_servers" -> FullAppDataExport(mcpServers = items.decode<McpInfo>())
    "speech", "speech_service", "speech_services" -> FullAppDataExport(speechServices = items.decode<SpeechService>())
    "chat", "chats", "conversation", "conversations" -> FullAppDataExport(conversations = items.decode<Conversation>())
    else -> error("This is not a supported Generative backup or legacy export.")
}

private fun inferLegacyArray(items: JsonArray, fileNameHint: String?): FullAppDataExport {
    if (items.isEmpty()) {
        val name = fileNameHint.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT)
        return when {
            "provider" in name -> FullAppDataExport(providers = emptyList())
            "mcp" in name -> FullAppDataExport(mcpServers = emptyList())
            "speech" in name -> FullAppDataExport(speechServices = emptyList())
            "chat" in name || "conversation" in name -> FullAppDataExport(conversations = emptyList())
            "group" in name -> FullAppDataExport(providerGroups = emptyList())
            else -> error("An empty legacy JSON array has no identifiable data category.")
        }
    }
    val first = items.first().jsonObject
    return when {
        "config" in first && "type" in first -> FullAppDataExport(providers = items.decode())
        "command" in first || "transport" in first || "url" in first && "name" in first && "headers" in first ->
            FullAppDataExport(mcpServers = items.decode())
        "messages" in first || "modelId" in first || "updatedAt" in first && "createdAt" in first ->
            FullAppDataExport(conversations = items.decode())
        "voice" in first || "speechRate" in first || "apiKey" in first && "provider" in first ->
            FullAppDataExport(speechServices = items.decode())
        "sortOrder" in first && "name" in first && "id" in first ->
            FullAppDataExport(providerGroups = items.decode())
        else -> error("Cannot identify the data category in this legacy JSON array.")
    }
}


/** Do not serialize the build-provided Content API endpoint in portable exports. */
private fun McpInfo.withoutBundledEndpoint(): McpInfo =
    if (isContentApiPreset() && BuildConfig.CONTENT_API_MCP_URL.isNotBlank() &&
        url?.trim() == BuildConfig.CONTENT_API_MCP_URL.trim()) copy(url = null) else this
