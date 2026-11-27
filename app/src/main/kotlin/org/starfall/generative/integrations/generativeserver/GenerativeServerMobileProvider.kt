package org.starfall.generative.integrations.generativeserver

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.requestHeaders
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.language.passbackMode
import org.starfall.generative.shared.model.language.requestReasoningEffort
import org.starfall.generative.shared.model.provider.AnthropicPromptCache
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.streamEnabledFor

/** Only adapters supported by Generative Server's mobile_ephemeral runtime are selectable. */
internal fun ModelProvider.remoteTextCatalog(options: ServerProviderOptions = ServerProviderOptions()): ModelProvider? {
    if (isHidden || type !in setOf(ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES,
            ModelApiType.ANTHROPIC, ModelApiType.GOOGLE)) return null
    if (options.adapter(this) == null) return null
    val models = (config.modelIds ?: config.modelConfigs.keys.toList()).filter { id ->
        val model = config.modelConfigs[id] ?: ModelConfiguration()
        model.modelType == ModelType.TEXT_GENERATION &&
            (options.version >= 2 || streamEnabledFor(id))
    }
    if (models.isEmpty()) return null
    return copy(config = config.copy(modelIds = models))
}

internal enum class MobileProviderFailure { OAUTH, CONNECTION_OPTIONS, MODEL_OPTIONS, CREDENTIALS }

internal class MobileProviderConfigurationException(
    val reason: MobileProviderFailure,
    val field: String,
    val code: String = "unsupported_provider_configuration",
) : IllegalArgumentException() {
    val diagnostic: String get() = RpcError(code, "", field = this.field).diagnostic
}

/** Encode the selected model and credential only, never the complete provider catalog. */
internal fun mobileProviderRuntime(
    provider: ModelProvider, runtimeId: String, modelId: String,
    options: ServerProviderOptions = ServerProviderOptions(),
) = buildJsonObject {
    val settings = provider.config
    val model = settings.modelConfigs[modelId] ?: ModelConfiguration()
    fun reject(reason: MobileProviderFailure, field: String, code: String = "unsupported_provider_configuration"): Nothing =
        throw MobileProviderConfigurationException(reason, field, code)
    val connection = MobileProviderFailure.CONNECTION_OPTIONS
    val modelOptions = MobileProviderFailure.MODEL_OPTIONS
    val adapter = options.adapter(provider)
        ?: reject(connection, "provider.type", "unsupported_provider_adapter")
    val reasoning = model.requestReasoningEffort
    if (provider.auth.method == AuthMethod.OAUTH) reject(MobileProviderFailure.OAUTH, "provider.auth.method")
    if (settings.httpProxy.isNotEmpty() && !options.httpProxy) reject(connection, "provider.config.httpProxy")
    if (settings.socksProxy.isNotEmpty() && (!options.socksProxy || settings.httpProxy.isNotEmpty()))
        reject(connection, "provider.config.socksProxy")
    if (settings.headers.isNotEmpty() && !adapter.customHeaders) reject(connection, "provider.config.headers")
    if (settings.customEndpoints.isNotEmpty() && !adapter.customEndpoints) reject(connection, "provider.config.customEndpoints")
    if (settings.responsesApi && !provider.type.isOpenAi) reject(connection, "provider.config.responsesApi")
    if (provider.type == ModelApiType.ANTHROPIC && options.version < 2 && settings.anthropicPromptCache != AnthropicPromptCache())
        reject(connection, "provider.config.anthropicPromptCache")
    if (provider.remoteTextCatalog(options)?.config?.modelIds?.contains(modelId) != true)
        reject(modelOptions, "provider.modelId")
    if (!provider.streamEnabledFor(modelId) && !adapter.nonStreaming) reject(modelOptions, "provider.modelConfig.supportStream")
    if (reasoning != null && "reasoning_effort" !in adapter.parameters) reject(modelOptions, "provider.modelConfig.reasoning_effort")
    if (model.passbackMode == ThinkingPassback.ON && !adapter.thinkingPassback)
        reject(modelOptions, "provider.modelConfig.thinking_passback")
    if (model.enabledModelTools.isNotEmpty()) reject(modelOptions, "provider.modelConfig.enabled_model_tools")
    if (model.temperature != null && "temperature" !in adapter.parameters) reject(modelOptions, "provider.modelConfig.temperature")
    if (model.topP != null && "top_p" !in adapter.parameters) reject(modelOptions, "provider.modelConfig.top_p")
    if (model.topK != null && "top_k" !in adapter.parameters) reject(modelOptions, "provider.modelConfig.top_k")
    if (settings.maxTokens !in options.minTokens..options.maxTokens)
        reject(modelOptions, "provider.config.maxTokens", "invalid_provider_configuration")
    if (settings.multipleApiKeys && provider.auth.token.isBlank() && provider.auth.method != AuthMethod.NONE)
        reject(MobileProviderFailure.CREDENTIALS, "provider.auth.value", "auth_required")
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
                if (provider.auth.token.isBlank() && options.version >= 2) {
                    put("method", "none")
                } else {
                    if ((provider.auth.key.isNullOrBlank() && options.version < 2) || provider.auth.token.isBlank()) {
                        reject(MobileProviderFailure.CREDENTIALS, "provider.auth.value", "auth_required")
                    }
                    put("method", "query_param")
                    put("key", provider.auth.key?.takeIf { it.isNotBlank() } ?: "key")
                    put("value", provider.auth.token)
                }
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
            put("supportStream", provider.streamEnabledFor(modelId))
            put("responsesApi", settings.responsesApi)
            put("maxTokens", settings.maxTokens)
            val codec = GenerativeServerCodec.json
            if (settings.headers.isNotEmpty()) put("headers", codec.encodeToJsonElement(settings.headers))
            if (settings.customEndpoints.isNotEmpty()) put("customEndpoints", codec.encodeToJsonElement(settings.customEndpoints))
            if (settings.httpProxy.isNotEmpty()) put("httpProxy", codec.encodeToJsonElement(settings.httpProxy))
            if (settings.socksProxy.isNotEmpty()) put("socksProxy", codec.encodeToJsonElement(settings.socksProxy))
            if (options.version >= 2 && provider.type == ModelApiType.ANTHROPIC) {
                put("anthropicPromptCache", codec.encodeToJsonElement(settings.anthropicPromptCache))
            }
        })
        put("modelConfig", buildJsonObject {
            model.temperature?.let { put("temperature", it) }
            model.topP?.let { put("top_p", it) }
            model.topK?.let { put("top_k", it) }
            if (options.version >= 2) {
                put("supportsThinking", model.supportsThinking)
                reasoning?.let { put("reasoning_effort", it) }
                put("thinking_passback", model.passbackMode.name.lowercase())
                if (adapter.modelMetadata && model.modelJson.isNotEmpty()) put("model_json", model.modelJson)
            }
        })
    })
}
