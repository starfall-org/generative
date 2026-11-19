package org.starfall.generative.integrations.opencode

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.starfall.generative.shared.model.api.ModelApiType

/** A server-reported config path. It is never resolved against the Android filesystem. */
data class OpenCodeProviderConfigFile(val path: String, val exists: Boolean) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    val parent: String get() = path.dropLast(name.length).trimEnd('/', '\\').ifEmpty { "/" }
}

private val providerPackages = mapOf(
    ModelApiType.OPENAI to "@opencode/ai/providers/openai-compatible",
    ModelApiType.OPENAI_RESPONSES to "@opencode/ai/providers/openai-compatible/responses",
    ModelApiType.ANTHROPIC to "@opencode/ai/providers/anthropic-compatible",
    ModelApiType.GOOGLE to "@opencode/ai/providers/google"
)

internal val openCodeProviderTypes = providerPackages.keys.toList()

internal fun validOpenCodeProviderId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))

internal fun openCodeProviderConfig(name: String, type: ModelApiType, baseUrl: String, apiKey: String,
    modelIds: List<String>): JsonObject? {
    val pkg = providerPackages[type] ?: return null
    val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
    if (name.isBlank() || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
        modelIds.isEmpty() || modelIds.any { it.isBlank() || it.any(Char::isWhitespace) }) return null
    return buildJsonObject {
        put("name", name.trim())
        put("package", pkg)
        put("settings", buildJsonObject {
            put("baseURL", baseUrl.trim())
            if (apiKey.isNotBlank()) put("apiKey", apiKey.trim())
        })
        put("models", buildJsonObject { modelIds.distinct().forEach { put(it, buildJsonObject {}) } })
    }
}

/** Select the file in the requested project, or the first user config source for Server default. */
internal fun selectOpenCodeProviderConfig(entries: List<JsonObject>, directory: String?): OpenCodeProviderConfigFile {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    fun norm(path: String) = path.replace('\\', '/').trimEnd('/')
    fun isConfig(path: String) = OpenCodeProviderConfigFile(path, true).name in setOf("opencode.json", "opencode.jsonc")
    fun isManaged(path: String) = norm(path).let {
        it.startsWith("/etc/opencode/") || it.startsWith("/Library/Application Support/opencode/") ||
            it.contains("/ProgramData/opencode/", ignoreCase = true)
    }
    val docs = entries.mapNotNull { entry ->
        val path = entry.text("path") ?: return@mapNotNull null
        if (entry.text("type") != "document" || !isConfig(path) || isManaged(path)) null else entry to path
    }
    val scoped = if (directory == null) {
        // Global discovery sources precede project and system sources. Prefer a document
        // within that first directory instead of modifying a more specific project.
        val first = entries.firstOrNull { it.text("type") == "directory" }?.text("path")
        if (first == null) docs.take(1)
        else docs.filter { norm(OpenCodeProviderConfigFile(it.second, true).parent) == norm(first) }
    } else docs.filter {
        val parent = norm(OpenCodeProviderConfigFile(it.second, true).parent)
        parent == norm(directory) || parent == norm(directory) + "/.opencode"
    }
    val selected = scoped.lastOrNull { (it.first["info"] as? JsonObject)?.containsKey("providers") == true }
        ?: scoped.lastOrNull()
    if (selected != null) return OpenCodeProviderConfigFile(selected.second, true)
    val root = directory ?: entries.firstOrNull { it.text("type") == "directory" }?.text("path")
        ?: throw OpenCodeProtocolException("No writable configuration source")
    val path = root.trimEnd('/', '\\') + if ('\\' in root && '/' !in root) "\\opencode.json" else "/opencode.json"
    if (isManaged(path)) throw OpenCodeProtocolException("Managed configuration is read-only")
    return OpenCodeProviderConfigFile(path, false)
}

@OptIn(ExperimentalSerializationApi::class)
private val configJson = Json { allowComments = true; allowTrailingComma = true }

// Tokenize only structural tokens, strings and comments. The original JSONC is retained,
// including variables, formatting, comments and every unrelated provider.
private val configTokens = Regex(""""(?:\\.|[^"\\])*"|//[^\r\n]*|/\*[\s\S]*?\*/|[{}\[\]:,]""")

internal fun insertOpenCodeProvider(raw: String, id: String, config: JsonObject): String {
    require(validOpenCodeProviderId(id))
    val root = configJson.parseToJsonElement(raw).jsonObject
    val existing = root["providers"]?.jsonObject
    require(existing?.containsKey(id) != true)
    val value = JsonPrimitive(id).toString() + ": " + config.toString()
    val tokens = configTokens.findAll(raw).filterNot { it.value.startsWith("//") || it.value.startsWith("/*") }.toList()
    val index: Int
    val addition: String
    if (existing == null) {
        index = tokens.first().range.last + 1
        addition = "\n  \"providers\": { $value }" + if (root.isEmpty()) "\n" else ","
    } else {
        var depth = 0
        var opening = -1
        for ((i, token) in tokens.withIndex()) {
            if (depth == 1 && token.value.startsWith('"') &&
                configJson.parseToJsonElement(token.value).jsonPrimitive.content == "providers" &&
                tokens.getOrNull(i + 1)?.value == ":" && tokens.getOrNull(i + 2)?.value == "{") {
                opening = tokens[i + 2].range.last + 1
                break
            }
            when (token.value) {
                "{", "[" -> depth++
                "}", "]" -> depth--
            }
        }
        require(opening >= 0)
        index = opening
        addition = "\n    $value" + if (existing.isEmpty()) "\n  " else ","
    }
    val result = raw.substring(0, index) + addition + raw.substring(index)
    val expected = JsonObject(root + ("providers" to JsonObject(existing.orEmpty() + (id to config))))
    require(configJson.parseToJsonElement(result) == expected)
    return result
}
