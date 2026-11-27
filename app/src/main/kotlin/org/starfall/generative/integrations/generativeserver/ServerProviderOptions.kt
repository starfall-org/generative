package org.starfall.generative.integrations.generativeserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider

/** Negotiated provider options; absent negotiation keeps the original v5 contract. */
data class ServerProviderOptions(
    val version: Int = 1,
    val maxTokens: Int = 131072,
    val httpProxy: Boolean = false,
    val socksProxy: Boolean = false,
    val adapters: Map<String, ServerProviderAdapterOptions> = emptyMap(),
    val minTokens: Int = 0,
) {
    internal fun adapter(provider: ModelProvider): ServerProviderAdapterOptions? {
        val type = if (provider.type == ModelApiType.OPENAI && provider.config.responsesApi)
            "openai_responses" else provider.type.name.lowercase()
        if (version >= 2) return adapters[type]
        return ServerProviderAdapterOptions(parameters = when (type) {
            "openai", "openai_responses" -> setOf("max_tokens", "temperature", "top_p")
            "google" -> setOf("max_tokens", "temperature", "top_p", "top_k")
            else -> setOf("max_tokens")
        })
    }

    companion object {
        internal fun fromNegotiation(negotiation: JsonObject?): ServerProviderOptions {
            val version = negotiation?.get("version")?.jsonPrimitive?.intOrNull ?: 1
            if (version < 2 || negotiation == null) return ServerProviderOptions()
            val proxy = negotiation["proxy"]?.jsonObject
            val tokens = negotiation["parameters"]?.jsonObject?.get("max_tokens")?.jsonObject
            val min = tokens?.get("min")?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: 0
            val max = tokens?.get("max")?.jsonPrimitive?.intOrNull?.takeIf { it >= min } ?: Int.MAX_VALUE
            val adapters = negotiation["adapters"]?.jsonObject.orEmpty().mapValues { (_, value) ->
                val adapter = value.jsonObject
                fun enabled(key: String) = adapter[key]?.jsonPrimitive?.booleanOrNull == true
                ServerProviderAdapterOptions(
                    parameters = adapter["parameters"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet(),
                    customHeaders = enabled("custom_headers"),
                    customEndpoints = enabled("custom_endpoints"),
                    modelMetadata = adapter["model_json"]?.jsonPrimitive?.content == "metadata",
                    nonStreaming = enabled("non_streaming"),
                    thinkingPassback = enabled("thinking_passback"),
                )
            }
            return ServerProviderOptions(version, max,
                proxy?.get("http")?.jsonPrimitive?.booleanOrNull == true,
                proxy?.get("socks5")?.jsonPrimitive?.booleanOrNull == true, adapters, min)
        }
    }
}

data class ServerProviderAdapterOptions(
    val parameters: Set<String> = emptySet(),
    val customHeaders: Boolean = false,
    val customEndpoints: Boolean = false,
    val modelMetadata: Boolean = false,
    val nonStreaming: Boolean = false,
    val thinkingPassback: Boolean = false,
)
