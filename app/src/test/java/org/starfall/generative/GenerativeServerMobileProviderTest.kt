package org.starfall.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.generativeserver.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.provider.AnthropicPromptCache
import org.starfall.generative.shared.model.provider.AnthropicCacheTtl
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderApiKey
import org.starfall.generative.shared.model.provider.ProviderConfiguration

class GenerativeServerMobileProviderTest {
    private val negotiation = Json.parseToJsonElement("""{
        "version":2,"parameters":{"max_tokens":{"max":2147483647}},
        "proxy":{"http":true,"socks5":false},
        "adapters":{
            "openai":{"parameters":["max_tokens","temperature","top_p","reasoning_effort"],
                "custom_headers":true,"custom_endpoints":true,"model_json":"metadata",
                "non_streaming":true,"thinking_passback":true},
            "openai_responses":{"parameters":["max_tokens","temperature","top_p","reasoning_effort"],
                "custom_headers":true,"custom_endpoints":true,"model_json":"metadata","non_streaming":true},
            "anthropic":{"parameters":["max_tokens","temperature","top_p","top_k","reasoning_effort"],
                "custom_headers":true,"custom_endpoints":true,"model_json":"metadata","non_streaming":true}
        }
    }""").jsonObject
    private val options = ServerProviderOptions.fromNegotiation(negotiation)
    private val metadata = Json.parseToJsonElement("""{"family":"fixture"}""").jsonObject
    private val provider = ModelProvider("p", "Gateway", ModelApiType.OPENAI,
        auth = Authorization(AuthMethod.BEARER_TOKEN, value = "selected-key"),
        baseUrl = "https://example.invalid/v1",
        config = ProviderConfiguration(
            headers = mapOf("X-Extra" to "extra"), customEndpoints = mapOf("chat" to "https://example.invalid/chat"),
            httpProxy = mapOf("host" to "proxy.example.invalid", "port" to "8080"),
            maxTokens = 200000, modelIds = listOf("m", "other"), multipleApiKeys = true,
            apiKeys = listOf(ProviderApiKey("other-key", value = "unselected-secret")),
            modelConfigs = mapOf("m" to ModelConfiguration(
                supportStream = false, reasoningEffort = "high", thinkingPassback = ThinkingPassback.ON,
                modelJson = metadata, temperature = 0.4, topP = 0.9),
                "other" to ModelConfiguration(displayName = "Other model"))
        ))

    @Test fun negotiatedOptionsReachRuntimeWithoutSendingOtherCredentials() {
        val runtime = mobileProviderRuntime(provider, "local:p", "m", options)
        val wire = runtime.getValue("provider").jsonObject
        val config = wire.getValue("config").jsonObject
        val model = wire.getValue("modelConfig").jsonObject
        assertEquals("false", config.getValue("supportStream").jsonPrimitive.content)
        assertEquals("200000", config.getValue("maxTokens").jsonPrimitive.content)
        assertEquals("extra", config.getValue("headers").jsonObject.getValue("X-Extra").jsonPrimitive.content)
        assertEquals("https://example.invalid/chat", config.getValue("customEndpoints").jsonObject.getValue("chat").jsonPrimitive.content)
        assertEquals("8080", config.getValue("httpProxy").jsonObject.getValue("port").jsonPrimitive.content)
        assertEquals("high", model.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("on", model.getValue("thinking_passback").jsonPrimitive.content)
        assertEquals(metadata, model.getValue("model_json"))
        assertEquals("selected-key", wire.getValue("auth").jsonObject.getValue("value").jsonPrimitive.content)
        assertFalse(runtime.toString().contains("unselected-secret"))
        assertFalse(runtime.toString().contains("Other model"))
        assertEquals(listOf("m", "other"), provider.remoteTextCatalog(options)?.config?.modelIds)
        assertEquals(listOf("other"), provider.remoteTextCatalog()?.config?.modelIds)
    }

    @Test fun legacyAndDisabledCapabilitiesRejectRatherThanDropSettings() {
        fun rejection(value: ModelProvider, policy: ServerProviderOptions): MobileProviderFailure {
            return try {
                mobileProviderRuntime(value, "local:p", "m", policy)
                throw AssertionError("Unsupported settings were accepted")
            } catch (e: MobileProviderConfigurationException) { e.reason }
        }
        assertEquals(MobileProviderFailure.CONNECTION_OPTIONS, rejection(provider, ServerProviderOptions()))
        assertEquals(MobileProviderFailure.CONNECTION_OPTIONS, rejection(provider, options.copy(httpProxy = false)))
        assertEquals(MobileProviderFailure.MODEL_OPTIONS, rejection(
            provider.copy(config = provider.config.copy(responsesApi = true)), options))
        assertEquals(MobileProviderFailure.MODEL_OPTIONS, rejection(provider.copy(config = provider.config.copy(
            modelConfigs = mapOf("m" to ModelConfiguration(topK = 4)))), options))
    }

    @Test fun missingV2AdapterDoesNotFallBackToLegacyCapabilities() {
        val onlyChat = options.copy(adapters = options.adapters.filterKeys { it == "openai" })
        val responses = provider.copy(config = provider.config.copy(responsesApi = true))
        assertNull(responses.remoteTextCatalog(onlyChat))
        try {
            mobileProviderRuntime(responses, "local:p", "m", onlyChat)
            fail("Unadvertised adapter accepted")
        } catch (e: MobileProviderConfigurationException) {
            assertEquals("provider.type", e.field)
        }
    }

    @Test fun legacyMetadataDoesNotAffectExecutionCompatibility() {
        val legacy = provider.copy(config = ProviderConfiguration(modelIds = listOf("m"),
            modelConfigs = mapOf("m" to ModelConfiguration(modelJson = metadata))))
        val model = mobileProviderRuntime(legacy, "local:p", "m").getValue("provider").jsonObject
            .getValue("modelConfig").jsonObject
        assertFalse(model.containsKey("model_json"))
    }

    @Test fun anthropicRuntimePreservesCacheAndSupportsThinkingAndSocksProxy() {
        val cache = AnthropicPromptCache(enabled = false, ttl = AnthropicCacheTtl.ONE_HOUR,
            cacheSystem = false, cacheTools = false)
        val anthropic = provider.copy(type = ModelApiType.ANTHROPIC, config = ProviderConfiguration(
            modelIds = listOf("m"), anthropicPromptCache = cache,
            socksProxy = mapOf("url" to "socks5://proxy.example.invalid:1080"),
            modelConfigs = mapOf("m" to ModelConfiguration(supportsThinking = false, reasoningEffort = "high", topK = 12))))
        val wire = mobileProviderRuntime(anthropic, "local:p", "m", options.copy(socksProxy = true))
            .getValue("provider").jsonObject
        val config = wire.getValue("config").jsonObject
        assertEquals(cache, GenerativeServerCodec.json.decodeFromString<AnthropicPromptCache>(
            config.getValue("anthropicPromptCache").toString()))
        assertEquals("socks5://proxy.example.invalid:1080", config.getValue("socksProxy").jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("false", wire.getValue("modelConfig").jsonObject.getValue("supportsThinking").jsonPrimitive.content)
        assertEquals("12", wire.getValue("modelConfig").jsonObject.getValue("top_k").jsonPrimitive.content)
    }

    @Test fun explicitModelStreamingOverridesProviderDefault() {
        val streaming = provider.copy(config = ProviderConfiguration(supportStream = false,
            modelIds = listOf("m"), modelConfigs = mapOf("m" to ModelConfiguration(supportStream = true))))
        assertEquals(listOf("m"), streaming.remoteTextCatalog()?.config?.modelIds)
        val wire = mobileProviderRuntime(streaming, "local:p", "m").getValue("provider").jsonObject
        assertEquals("true", wire.getValue("config").jsonObject.getValue("supportStream").jsonPrimitive.content)
    }
}
