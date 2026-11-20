package org.starfall.generative.integrations.generativeserver

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.requestHeaders
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider

/** Only adapters supported by Generative Server's mobile_ephemeral runtime are selectable. */
internal fun ModelProvider.remoteTextCatalog(): ModelProvider? {
    if (isHidden || type !in setOf(ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES,
            ModelApiType.ANTHROPIC, ModelApiType.GOOGLE) || !config.supportStream) return null
    val models = (config.modelIds ?: config.modelConfigs.keys.toList()).filter { id ->
        val model = config.modelConfigs[id] ?: ModelConfiguration()
        model.modelType == ModelType.TEXT_GENERATION && model.supportStream != false
    }
    if (models.isEmpty()) return null
    return copy(config = config.copy(modelIds = models))
}

/** Encode the selected model and credential only, never the complete provider catalog. */
internal fun mobileProviderRuntime(provider: ModelProvider, runtimeId: String, modelId: String) = buildJsonObject {
    require(provider.remoteTextCatalog()?.config?.modelIds?.contains(modelId) == true)
    val settings = provider.config
    val model = settings.modelConfigs[modelId] ?: ModelConfiguration()
    require(settings.httpProxy.isEmpty() && settings.socksProxy.isEmpty() && settings.headers.isEmpty() &&
        settings.customEndpoints.isEmpty()) { "Generative Server does not support this provider's connection overrides" }
    require(provider.auth.method != AuthMethod.OAUTH) { "Generative Server cannot resolve local OAuth accounts" }
    require(model.reasoningEffort == null && model.thinkingLevel == null && model.thinkingPassback == null &&
        model.legacySendThinkingContent == null && model.enabledModelTools.isEmpty() && model.modelJson.isEmpty()) {
        "Generative Server does not support this model's runtime configuration"
    }
    put("source", "mobile_ephemeral")
    put("provider", buildJsonObject {
        put("id", runtimeId)
        put("type", when (provider.type) {
            ModelApiType.OPENAI -> "openai"
            ModelApiType.OPENAI_RESPONSES -> "openai_responses"
            ModelApiType.ANTHROPIC -> "anthropic"
            ModelApiType.GOOGLE -> "google"
            else -> error("Unsupported Generative Server provider")
        })
        put("baseUrl", provider.baseUrl)
        put("modelId", modelId)
        put("auth", buildJsonObject {
            if (provider.auth.method == AuthMethod.QUERY_PARAM) {
                require(!provider.auth.key.isNullOrBlank() && provider.auth.token.isNotBlank())
                put("method", "query_param")
                put("key", provider.auth.key)
                put("value", provider.auth.token)
            } else {
                val header = provider.auth.requestHeaders(provider.type).entries.singleOrNull()
                if (header == null) put("method", "none")
                else if (header.key.equals("Authorization", ignoreCase = true) &&
                    header.value.startsWith("Bearer ", ignoreCase = true)) {
                    put("method", "bearer_token")
                    put("value", header.value.substring(7).trim())
                } else {
                    put("method", "custom_header")
                    put("key", header.key)
                    put("value", header.value)
                }
            }
        })
        put("config", buildJsonObject {
            put("supportStream", true)
            put("responsesApi", settings.responsesApi)
            put("maxTokens", settings.maxTokens)
        })
        put("modelConfig", buildJsonObject {
            model.temperature?.let { put("temperature", it) }
            model.topP?.let { put("top_p", it) }
            model.topK?.let { put("top_k", it) }
        })
    })
}
