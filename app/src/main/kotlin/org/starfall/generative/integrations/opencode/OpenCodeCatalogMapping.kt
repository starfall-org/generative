package org.starfall.generative.integrations.opencode

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.starfall.generative.shared.model.McpAuthMethod
import org.starfall.generative.shared.model.McpAuthorization
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.objectValue(key: String): JsonObject = (this[key] as? JsonObject) ?: JsonObject(emptyMap())
private fun JsonObject.strings(key: String): Map<String, String> =
    objectValue(key).mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }.toMap()

internal fun OpenCodeCatalogEntry.toProvider(fallback: ModelProvider): ModelProvider {
    val settings = value.objectValue("settings")
    val models = value.objectValue("models").mapValues { (_, element) ->
        val model = element.jsonObject
        val context = model.objectValue("limit")["context"]?.jsonPrimitive?.intOrNull
        val caps = model.objectValue("capabilities")
        val reasoning = caps.objectValue("reasoning")
        val inputs = caps["input"] as? JsonArray
        val modalities = inputs.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val defaults = ModelConfiguration()
        defaults.copy(
            displayName = model.text("name").orEmpty(),
            contextWindowTokens = context?.takeIf { it > 0 } ?: defaults.contextWindowTokens,
            modelType = if (model.text("type") == "image") ModelType.IMAGE_GENERATION else defaults.modelType,
            supportsToolCalls = caps["tools"]?.jsonPrimitive?.booleanOrNull ?: defaults.supportsToolCalls,
            supportsVision = if (inputs == null) defaults.supportsVision else "image" in modalities,
            supportsVideoInput = "video" in modalities,
            supportsAudioInput = "audio" in modalities,
            supportsThinking = reasoning["supported"]?.jsonPrimitive?.booleanOrNull ?: defaults.supportsThinking,
            reasoningEfforts = (reasoning["effort"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: defaults.reasoningEfforts
        )
    }
    return fallback.copy(
        name = value.text("name") ?: fallback.name,
        type = value.text("package")?.let(::openCodeProviderType) ?: fallback.type,
        baseUrl = settings.text("baseURL") ?: fallback.baseUrl,
        auth = Authorization(AuthMethod.PLATFORM_DEFAULT, value = settings.text("apiKey")),
        config = ProviderConfiguration(headers = value.strings("headers"), modelIds = models.keys.toList(), modelConfigs = models)
    )
}

internal fun OpenCodeCatalogEntry.withProvider(initial: ModelProvider, edited: ModelProvider): JsonObject {
    val updated = value.toMutableMap()
    if (edited.name != initial.name) {
        require(edited.name.isNotBlank())
        updated["name"] = JsonPrimitive(edited.name)
    }
    if (edited.type != initial.type) updated["package"] = JsonPrimitive(openCodeProviderPackage(edited.type))
    val settings = value.objectValue("settings").toMutableMap()
    if (edited.baseUrl != initial.baseUrl) {
        requireOpenCodeEndpoint(edited.baseUrl)
        settings["baseURL"] = JsonPrimitive(edited.baseUrl)
    }
    if (edited.auth.token != initial.auth.token || edited.auth.method != initial.auth.method) {
        require(edited.auth.method in setOf(AuthMethod.PLATFORM_DEFAULT, AuthMethod.NONE))
        if (edited.auth.token.isBlank()) settings.remove("apiKey") else settings["apiKey"] = JsonPrimitive(edited.auth.token)
    }
    if (settings != value.objectValue("settings")) updated["settings"] = JsonObject(settings)
    if (edited.config.headers != initial.config.headers) {
        updated["headers"] = JsonObject(edited.config.headers.mapValues { JsonPrimitive(it.value) })
    }
    val oldModels = value.objectValue("models")
    val modelIds = edited.config.modelIds.orEmpty()
    require(modelIds.none { it.isBlank() || it.any(Char::isWhitespace) })
    val models = modelIds.associateWith { id ->
        val config = edited.config.modelConfigs[id] ?: ModelConfiguration()
        val previous = initial.config.modelConfigs[id] ?: ModelConfiguration()
        val model = oldModels.objectValue(id).toMutableMap()
        if (config.displayName != previous.displayName) {
            if (config.displayName.isBlank()) model.remove("name") else model["name"] = JsonPrimitive(config.displayName)
        }
        if (config.contextWindowTokens != previous.contextWindowTokens) {
            val limit = model["limit"] as? JsonObject ?: JsonObject(emptyMap())
            model["limit"] = JsonObject(limit + ("context" to JsonPrimitive(config.contextWindowTokens)))
        }
        if (config.modelType != previous.modelType) {
            model["type"] = JsonPrimitive(when (config.modelType) {
                ModelType.TEXT_GENERATION -> "chat"
                ModelType.IMAGE_GENERATION -> "image"
                else -> error("Unsupported OpenCode model type")
            })
        }
        val oldCaps = (model["capabilities"] as? JsonObject) ?: JsonObject(emptyMap())
        val caps = oldCaps.toMutableMap()
        if (config.supportsToolCalls != previous.supportsToolCalls) caps["tools"] = JsonPrimitive(config.supportsToolCalls)
        if (config.supportsVision != previous.supportsVision || config.supportsVideoInput != previous.supportsVideoInput ||
            config.supportsAudioInput != previous.supportsAudioInput) {
            val inputs = (caps["input"] as? JsonArray).orEmpty().toMutableList()
            if (inputs.isEmpty()) inputs.add(JsonPrimitive("text"))
            for ((modality, enabled) in listOf("image" to config.supportsVision, "video" to config.supportsVideoInput,
                "audio" to config.supportsAudioInput)) {
                val value = JsonPrimitive(modality)
                if (enabled && value !in inputs) inputs.add(value)
                if (!enabled) inputs.remove(value)
            }
            caps["input"] = JsonArray(inputs)
        }
        if (config.supportsThinking != previous.supportsThinking || config.reasoningEfforts != previous.reasoningEfforts) {
            val reasoning = (caps["reasoning"] as? JsonObject).orEmpty().toMutableMap()
            if (!config.supportsThinking) reasoning.clear()
            reasoning["supported"] = JsonPrimitive(config.supportsThinking)
            if (config.supportsThinking && config.reasoningEfforts != previous.reasoningEfforts) {
                reasoning["effort"] = JsonArray(config.reasoningEfforts.map(::JsonPrimitive))
            }
            caps["reasoning"] = JsonObject(reasoning)
        }
        if (caps != oldCaps) model["capabilities"] = JsonObject(caps)
        JsonObject(model)
    }
    if (models != oldModels || modelIds != oldModels.keys.toList()) updated["models"] = JsonObject(models)
    return JsonObject(updated)
}

internal fun OpenCodeCatalogEntry.toMcp(): McpInfo {
    require(value.text("type") == "remote") { "MCP entry does not use a remote endpoint" }
    val name = path.last()
    val oauth = value["oauth"] as? JsonObject
    val auth = if (oauth == null) McpAuthorization() else McpAuthorization(
        method = McpAuthMethod.OAUTH2,
        oauthClientId = oauth.text("client_id"),
        oauthClientSecret = oauth.text("client_secret"),
        oauthRedirectUri = oauth.text("redirect_uri")
    )
    return McpInfo(name, name, url = requireNotNull(value.text("url")),
        headers = value.strings("headers").ifEmpty { null }, auth = auth)
}

internal fun OpenCodeCatalogEntry.withMcp(initial: McpInfo, edited: McpInfo): JsonObject {
    val result = value.toMutableMap()
    if (edited.url != initial.url || edited.headers != initial.headers ||
        edited.auth.method in setOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM, McpAuthMethod.CUSTOM_HEADER)) {
        if (edited.url != initial.url) requireOpenCodeEndpoint(requireNotNull(edited.url))
        val editedTransport = if (edited.auth.method == McpAuthMethod.OAUTH2)
            edited.copy(auth = McpAuthorization()) else edited
        val transport = if (editedTransport.auth.method != McpAuthMethod.NONE)
            openCodeMcpImportConfig(editedTransport) else null
        if (edited.url != initial.url || edited.auth.method == McpAuthMethod.QUERY_PARAM) {
            result["url"] = transport?.get("url") ?: JsonPrimitive(requireNotNull(edited.url))
        }
        if (edited.headers != initial.headers || edited.auth.method in setOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.CUSTOM_HEADER)) {
            val headers = transport?.get("headers") ?: edited.headers?.takeIf { it.isNotEmpty() }
                ?.let { JsonObject(it.mapValues { (_, value) -> JsonPrimitive(value) }) }
            if (headers == null) result.remove("headers") else result["headers"] = headers
        }
    }
    val before = initial.auth
    val after = edited.auth
    if (before.method != after.method || before.oauthClientId.orEmpty() != after.oauthClientId.orEmpty() ||
        before.oauthClientSecret.orEmpty() != after.oauthClientSecret.orEmpty() ||
        before.oauthRedirectUri.orEmpty() != after.oauthRedirectUri.orEmpty()) {
        if (after.method == McpAuthMethod.OAUTH2) {
            val oauth = value.objectValue("oauth").toMutableMap()
            listOf("client_id" to after.oauthClientId, "client_secret" to after.oauthClientSecret,
                "redirect_uri" to after.oauthRedirectUri).forEach { (key, field) ->
                if (field.isNullOrBlank()) oauth.remove(key) else oauth[key] = JsonPrimitive(field)
            }
            result["oauth"] = JsonObject(oauth)
        } else result["oauth"] = JsonPrimitive(false)
    }
    return JsonObject(result)
}

private fun requireOpenCodeEndpoint(endpoint: String) {
    val url = endpoint.toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
}
