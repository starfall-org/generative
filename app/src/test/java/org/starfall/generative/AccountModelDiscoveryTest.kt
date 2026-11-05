package org.starfall.generative

import org.starfall.generative.integrations.modelapis.account.*

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.integrations.modelapis.antigravity.AntigravityAdapter
import org.starfall.generative.integrations.modelapis.antigravity.antigravityTierId
import org.starfall.generative.integrations.modelapis.antigravity.antigravityUserAgent
import org.starfall.generative.shared.subfeatures.auth.logic.*
import org.starfall.generative.integrations.modelapis.githubcopilot.GitHubCopilotAdapter
import org.starfall.generative.integrations.modelapis.githubcopilot.copilotDiscoveredModel
import org.starfall.generative.integrations.modelapis.githubcopilot.copilotProtocol
import org.starfall.generative.integrations.modelapis.openaicodex.parseCodexModelCatalog
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.features.mcp.shared.ui.*
import org.starfall.generative.features.machines.shared.ui.*
import org.starfall.generative.integrations.remote.*
import org.starfall.generative.features.home.chat.*
import org.starfall.generative.integrations.modelapis.*
import org.starfall.generative.shared.subfeatures.media.logic.*
import org.starfall.generative.integrations.speech.*
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.database.preferences.*
import java.util.concurrent.TimeUnit
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.generation.GenerationEvent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccountModelDiscoveryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun keystore() = installTestAndroidKeyStore()
    private fun provider(type: ModelApiType, server: MockWebServer, slot: String) = ModelProvider(
        "provider", type.displayName, type, baseUrl = server.url("/").toString().trimEnd('/'),
        auth = Authorization(AuthMethod.OAUTH, value = "account:${type.name.lowercase()}:v1", oauthAccountId = slot))
    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)

    @Test fun antigravityUsesMinimalLoadMetadataAndLiveProjectCatalogPerAccountWithQuotaAndOpaqueIds() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"cloudaicompanionProject":"project-a"}"""))
            server.enqueue(json("""{"models":{
                "opaque-M7":{"displayName":"Premium model","model":"wire-model-a","quotaInfo":{"remainingFraction":0}},
                "internal":{"displayName":"Internal model","isInternal":true}}}"""))
            server.enqueue(json("""{"projectId":"project-b"}"""))
            server.enqueue(json("""{"models":{"tier-b":{"displayName":"Account B","quotaInfo":{"remainingFraction":0.5}}}}"""))
            server.enqueue(json("""{"models":{"refreshed":{"displayName":"New catalog"}}}"""))
            val store = AccountTokenStore(context, "antigravity")
            store.save("antigravity:a", AccountTokenState("access-a", "refresh-a"))
            store.save("antigravity:b", AccountTokenState("access-b", "refresh-b"))
            val a = provider(ModelApiType.ANTIGRAVITY, server, "antigravity:a")
            val b = provider(ModelApiType.ANTIGRAVITY, server, "antigravity:b")
            val adapter = AntigravityAdapter(context, AttachmentResolver(context))
            val catalogA = adapter.fetchModelCatalog(a)
            assertEquals(listOf("opaque-M7", "internal"), catalogA.map { it.id })
            assertEquals("Premium model", catalogA.first().displayName)
            assertEquals(0, catalogA.first().metadata["quotaInfo"]!!.jsonObject["remainingFraction"]!!.jsonPrimitive.int)
            assertEquals(listOf("tier-b"), adapter.fetchModels(b))
            assertEquals(listOf("refreshed"), adapter.fetchModels(a))
            val requests = List(5) { server.takeRequest(5, TimeUnit.SECONDS)!! }
            listOf(0, 2).forEach { index ->
                val load = requests[index]
                assertEquals("/v1internal:loadCodeAssist", load.path)
                val body = Json.parseToJsonElement(load.body.readUtf8()).jsonObject
                assertEquals(setOf("metadata"), body.keys)
                val metadata = body.getValue("metadata").jsonObject
                assertEquals(setOf("ideType"), metadata.keys)
                assertEquals("ANTIGRAVITY", metadata.text("ideType"))
                val userAgent = load.getHeader("User-Agent")!!
                assertEquals("antigravity/hub/2.24.0 (aidev_client; os_type=darwin; arch=arm64; cl=1000335682)",
                    userAgent)
            }
            listOf(1 to "project-a", 3 to "project-b", 4 to "project-a").forEach { (index, project) ->
                assertEquals("/v1internal:fetchAvailableModels", requests[index].path)
                assertEquals(project, Json.parseToJsonElement(requests[index].body.readUtf8()).jsonObject.text("project"))
            }
            assertEquals(listOf("Bearer access-a", "Bearer access-a", "Bearer access-b", "Bearer access-b", "Bearer access-a"),
                requests.map { it.getHeader("Authorization") })
            assertEquals("project-a", store.load("antigravity:a")!!.projectId)
            assertEquals("project-b", store.load("antigravity:b")!!.projectId)
            val selected = a.copy(config = a.config.copy(modelConfigs = mapOf("opaque-M7" to catalogA.first().configuration())))
            val wire = adapter.prepareModelProvider(selected, "opaque-M7")
            val normalized = adapter.normalizeToolRequest(selected, wire, obj("model" to str("opaque-M7")), "")
            assertEquals("wire-model-a", normalized.text("model"))
            assertEquals("project-a", normalized.text("project"))
        }
    }

    @Test fun antigravityUsesCatalogKeysInsteadOfPlaceholderEnumsForChat() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"models":{
                "gemini-3.8-flash-medium":{"displayName":"Gemini 3.8 Flash (Medium)","model":"MODEL_PLACEHOLDER_M319"},
                "claude-sonnet-4-6":{"displayName":"Claude Sonnet 4.6","model":"MODEL_PLACEHOLDER_M26"}}}"""))
            val response = """{"response":{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}}"""
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $response\n\n")) }
            val store = AccountTokenStore(context, "antigravity")
            store.save("catalog-enums", AccountTokenState("access", "refresh", projectId = "aicode-consumers"))
            val source = provider(ModelApiType.ANTIGRAVITY, server, "catalog-enums")
            val adapter = AntigravityAdapter(context, AttachmentResolver(context))
            val catalog = adapter.fetchModelCatalog(source)
            server.takeRequest(5, TimeUnit.SECONDS)!!
            val selected = source.copy(config = source.config.copy(
                supportStream = true, modelConfigs = catalog.associate { it.id to it.configuration() }))
            for (model in catalog.map { it.id }) {
                val events = adapter.streamEvents(selected, model,
                    listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("hi")))), "", 4000).toList()
                assertEquals("hello", events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/v1internal:streamGenerateContent?alt=sse", request.path)
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(model, body.text("model"))
                assertEquals("aicode-consumers", body.text("project"))
            }
        }
    }

    @Test fun antigravityOptionalVersionAndMatchingChangelistReachRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"allowedTiers":[{"id":"free-tier","isDefault":true}]}"""))
            server.enqueue(json("""{"done":true,"response":{"cloudaicompanionProject":"project-custom"}}"""))
            server.enqueue(json("""{"models":{"custom-model":{"displayName":"Custom model"}}}"""))
            AccountTokenStore(context, "antigravity").save("antigravity:custom",
                AccountTokenState("access-custom", "refresh-custom"))
            val accountProvider = provider(ModelApiType.ANTIGRAVITY, server, "antigravity:custom").let {
                it.copy(config = it.config.copy(
                    antigravityVersion = "2.25.0", antigravityChangelist = "123456789"))
            }
            val adapter = AntigravityAdapter(context, AttachmentResolver(context))
            assertEquals(listOf("custom-model"), adapter.fetchModelCatalog(accountProvider).map { it.id })

            val requests = List(3) { server.takeRequest(5, TimeUnit.SECONDS)!! }
            val userAgent = "antigravity/hub/2.25.0 (aidev_client; os_type=darwin; arch=arm64; cl=123456789)"
            assertEquals("/v1internal:loadCodeAssist", requests[0].path)
            assertEquals(userAgent, requests[0].getHeader("User-Agent"))
            assertEquals("/v1internal:onboardUser", requests[1].path)
            assertEquals("$userAgent google-api-nodejs-client/10.3.0", requests[1].getHeader("User-Agent"))
            assertEquals("2.25.0", Json.parseToJsonElement(requests[1].body.readUtf8()).jsonObject
                .getValue("metadata").jsonObject.text("ide_version"))
            assertEquals("/v1internal:fetchAvailableModels", requests[2].path)
            assertEquals(userAgent, requests[2].getHeader("User-Agent"))
            assertEquals("project-custom", Json.parseToJsonElement(requests[2].body.readUtf8()).jsonObject.text("project"))
            assertEquals("antigravity/hub/2.25.0 (aidev_client; os_type=darwin; arch=arm64)",
                antigravityUserAgent(accountProvider.config.copy(antigravityChangelist = null)))
        }
    }

    @Test fun antigravityManualProjectIsScopedToAccountAndSkipsOnboarding() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"models":{"manual-model":{"displayName":"Manual project model"}}}"""))
            val store = AccountTokenStore(context, "antigravity")
            store.save("antigravity:manual", AccountTokenState("access-manual", "refresh-manual"))
            val accountProvider = provider(ModelApiType.ANTIGRAVITY, server, "antigravity:manual").let {
                it.copy(config = it.config.copy(
                    antigravityProjectIds = mapOf("antigravity:manual" to "project-manual")))
            }
            val adapter = AntigravityAdapter(context, AttachmentResolver(context))
            assertEquals(listOf("manual-model"), adapter.fetchModelCatalog(accountProvider).map { it.id })

            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1internal:fetchAvailableModels", request.path)
            assertEquals("project-manual", Json.parseToJsonElement(request.body.readUtf8()).jsonObject.text("project"))
            assertEquals(1, server.requestCount)
            assertNull(store.load("antigravity:manual")!!.projectId)
            assertEquals(accountProvider.baseUrl, store.load("antigravity:manual")!!.codeAssistBaseUrl)
            assertNull(accountProvider.config.antigravityProjectIds["antigravity:other"])

            // An explicit project also wins over a previously discovered token-store project.
            store.save("antigravity:manual", store.load("antigravity:manual")!!.copy(projectId = "project-discovered"))
            server.enqueue(json("""{"models":{"manual-model":{"displayName":"Manual project model"}}}"""))
            adapter.fetchModelCatalog(accountProvider)
            val overridden = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("project-manual", Json.parseToJsonElement(overridden.body.readUtf8()).jsonObject.text("project"))
            assertEquals("project-discovered", store.load("antigravity:manual")!!.projectId)

            val wire = adapter.prepareModelProvider(accountProvider, "manual-model")
            val normalized = adapter.normalizeToolRequest(accountProvider, wire,
                obj("model" to str("manual-model")), "")
            assertEquals("project-manual", normalized.text("project"))
            assertEquals("project-discovered", store.load("antigravity:manual")!!.projectId)
        }
    }

    @Test fun antigravityEnvelopeUsesResolvedModelFamilyRules() {
        MockWebServer().use { server ->
            AccountTokenStore(context, "antigravity").save("normalize",
                AccountTokenState("access", "refresh", projectId = "project"))
            val base = provider(ModelApiType.ANTIGRAVITY, server, "normalize")
            val selected = base.copy(config = base.config.copy(modelConfigs = mapOf(
                "opaque-image" to ModelConfiguration(modelJson = obj("model" to str("gemini-3.1-flash-image"))),
                "opaque-claude" to ModelConfiguration(modelJson = obj("model" to str("claude-wire")))
            )))
            val adapter = AntigravityAdapter(context, AttachmentResolver(context))
            val wire = selected.copy(type = ModelApiType.GOOGLE)
            fun body(model: String) = obj(
                "model" to str(model),
                "contents" to JsonArray(listOf(obj("role" to str("user"),
                    "parts" to JsonArray(listOf(obj("text" to str("hello"))))))),
                "generationConfig" to obj("maxOutputTokens" to JsonPrimitive(2000), "temperature" to JsonPrimitive(0.3))
            )
            val image = adapter.normalizeToolRequest(selected, wire, body("opaque-image"), "")
            assertEquals("gemini-3.1-flash-image", image.text("model"))
            assertEquals("image_gen", image.text("requestType"))
            assertTrue(image.text("requestId").startsWith("image_gen/"))
            assertFalse(image.getValue("request").jsonObject.containsKey("sessionId"))
            assertFalse(image.getValue("request").jsonObject.getValue("generationConfig").jsonObject
                .containsKey("maxOutputTokens"))

            val claude = adapter.normalizeToolRequest(selected, wire, body("opaque-claude"), "")
            assertEquals("claude-wire", claude.text("model"))
            assertEquals("agent", claude.text("requestType"))
            assertEquals(2000, claude.getValue("request").jsonObject.getValue("generationConfig").jsonObject
                .getValue("maxOutputTokens").jsonPrimitive.int)
            assertEquals("VALIDATED", claude.getValue("request").jsonObject.getValue("toolConfig").jsonObject
                .getValue("functionCallingConfig").jsonObject.text("mode"))
            assertTrue(claude.getValue("request").jsonObject.text("sessionId").startsWith("-"))
        }
    }

    @Test fun antigravityOnboardingRepeatsPostAndUsesDefaultTierAndValidMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"allowedTiers":[{"id":"other"},{"id":"free-tier","isDefault":true}]}"""))
            server.enqueue(json("""{"name":"operations/onboard-test","done":false}"""))
            server.enqueue(json("""{"done":true,"response":{"cloudaicompanionProject":{"id":"provisioned"}}}"""))
            server.enqueue(json("""{"models":{"gemini-test":{"displayName":"Gemini Test"}}}"""))
            AccountTokenStore(context, "antigravity").save("onboard", AccountTokenState("access", "refresh"))
            val adapter = AntigravityAdapter(context, AttachmentResolver(context))
            assertEquals(listOf("gemini-test"), adapter.fetchModels(provider(ModelApiType.ANTIGRAVITY, server, "onboard")))
            val load = server.takeRequest(5, TimeUnit.SECONDS)!!
            val loadMetadata = Json.parseToJsonElement(load.body.readUtf8())
                .jsonObject.getValue("metadata").jsonObject
            assertEquals(setOf("ideType"), loadMetadata.keys)
            val onboard = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1internal:onboardUser", onboard.path)
            val body = Json.parseToJsonElement(onboard.body.readUtf8()).jsonObject
            assertEquals("free-tier", body.text("tier_id"))
            val metadata = body.getValue("metadata").jsonObject
            assertEquals(setOf("ide_type", "ide_version", "ide_name"), metadata.keys)
            assertEquals("ANTIGRAVITY", metadata.text("ide_type"))
            assertEquals("2.24.0", metadata.text("ide_version"))
            assertEquals("antigravity", metadata.text("ide_name"))
            assertTrue(onboard.getHeader("User-Agent")!!.startsWith("antigravity/hub/2.24.0"))
            assertTrue(onboard.getHeader("User-Agent")!!.contains("google-api-nodejs-client/10.3.0"))
            assertEquals("gl-node/22.21.1", onboard.getHeader("X-Goog-Api-Client"))
            assertEquals("*/*", onboard.getHeader("Accept"))
            val poll = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", poll.method)
            assertEquals("/v1internal:onboardUser", poll.path)
            assertEquals(body, Json.parseToJsonElement(poll.body.readUtf8()).jsonObject)
            val catalog = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", catalog.method)
            assertEquals("/v1internal:fetchAvailableModels", catalog.path)
        }
    }

    @Test fun antigravityTierSelectionUsesCurrentThenFreeFallbackAndDailyDefault() {
        val current = Json.parseToJsonElement("""{
            "allowedTiers":[{"id":"","isDefault":true}],
            "currentTier":{"id":"current-tier"}
        }""").jsonObject
        assertEquals("current-tier", antigravityTierId(current))
        assertEquals("free-tier", antigravityTierId(obj()))
        assertEquals("https://daily-cloudcode-pa.googleapis.com", ModelApiType.ANTIGRAVITY.defaultBaseUrl)
    }

    @Test fun antigravityGenerationRouteUsesDailyForBuiltInAndPreservesCustomBase() {
        val adapter = AntigravityAdapter(context, AttachmentResolver(context))
        val store = AccountTokenStore(context, "antigravity")
        val legacy = ModelProvider("legacy", "Antigravity", ModelApiType.ANTIGRAVITY,
            baseUrl = "https://daily-cloudcode-pa.sandbox.googleapis.com",
            auth = Authorization(AuthMethod.OAUTH, value = "legacy-marker", oauthAccountId = "legacy-route"))
        store.save("legacy-route", AccountTokenState("access", "refresh", projectId = "legacy-project",
            codeAssistBaseUrl = "https://autopush-cloudcode-pa.sandbox.googleapis.com"))
        assertEquals("https://daily-cloudcode-pa.googleapis.com/v1internal:generateContent",
            adapter.requestUrl(legacy, legacy.copy(type = ModelApiType.GOOGLE), "unused"))

        MockWebServer().use { server ->
            val custom = provider(ModelApiType.ANTIGRAVITY, server, "custom-route")
            store.save("custom-route", AccountTokenState("access", "refresh", projectId = "project",
                codeAssistBaseUrl = custom.baseUrl))
            assertEquals("${custom.baseUrl}/v1internal:generateContent",
                adapter.requestUrl(custom, custom.copy(type = ModelApiType.GOOGLE), "unused"))
        }
    }

    @Test fun copilotUsesCurrentAccountEntitlementsAndPreservesMetadataThroughService() = runBlocking {
        MockWebServer().use { server ->
            val catalog = """{"data":[
                {"id":"gpt-5-test","name":"Available GPT","model_picker_enabled":true,
                 "policy":{"state":"enabled"},"supported_endpoints":["/chat/completions"],
                 "capabilities":{"limits":{"max_context_window_tokens":300000,"max_output_tokens":64000,"max_prompt_tokens":200000},
                     "supports":{"vision":false,"tool_calls":true,"streaming":false,"reasoning_effort":["low","high"]}}},
                {"id":"restricted","model_picker_enabled":true,"policy":{"state":"disabled"}},
                {"id":"utility","model_picker_enabled":false},
                {"id":"embedding","capabilities":{"type":"embeddings"}}]}"""
            server.enqueue(json(catalog))
            server.enqueue(json("""{"data":[{"id":"account-b","name":"B model","supported_endpoints":["/responses"],"capabilities":{"limits":{"max_output_tokens":1000,"max_prompt_tokens":10000},"supports":{"tool_calls":true}}}]}"""))
            server.enqueue(json(catalog))
            val store = AccountTokenStore(context, "github_copilot")
            store.save("copilot:a", AccountTokenState("token-a", ""))
            store.save("copilot:b", AccountTokenState("token-b", ""))
            val a = provider(ModelApiType.GITHUB_COPILOT, server, "copilot:a")
            val b = provider(ModelApiType.GITHUB_COPILOT, server, "copilot:b")
            val service = AiService(context)
            val catalogA = service.fetchProviderModelCatalog(a)
            assertEquals(listOf("gpt-5-test"), catalogA.map { it.id })
            val model = catalogA.first()
            assertEquals("Available GPT", model.displayName)
            assertEquals(300000, model.contextWindowTokens)
            assertEquals(listOf("/chat/completions"), model.metadata["supported_endpoints"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertFalse(model.configuration().supportsVision)
            assertTrue(model.configuration().supportsThinking)
            assertEquals(false, model.configuration().supportStream)
            assertEquals(listOf("account-b"), service.fetchProviderModels(b))
            val wire = GitHubCopilotAdapter(context, AttachmentResolver(context)).prepareModelProvider(a, "gpt-5-test")
            // Explicit account routing must win even for GPT-5 names.
            assertEquals(ModelApiType.OPENAI, wire.type)
            assertEquals("token-a", wire.auth.token)
            val requests = List(3) { server.takeRequest(5, TimeUnit.SECONDS)!! }
            assertEquals(listOf("Bearer token-a", "Bearer token-b", "Bearer token-a"), requests.map { it.getHeader("Authorization") })
            assertTrue(requests.all { it.path == "/models" && it.getHeader("X-GitHub-Api-Version") == "2026-06-01" })
        }
    }

    @Test fun copilotCatalogFiltersDisabledNonChatAndIncompleteEntries() {
        val model = Json.parseToJsonElement("""{
            "id":"gpt-5-preview", "name":"GPT", "model_picker_enabled":true,
            "policy":{"state":"enabled"}, "supported_endpoints":["/chat/completions"],
            "capabilities":{"limits":{"max_context_window_tokens":128000,"max_output_tokens":16000,"max_prompt_tokens":100000},
                "supports":{"tool_calls":true}}
        }""").jsonObject
        assertEquals("gpt-5-preview", copilotDiscoveredModel(model)?.id)
        assertNull(copilotDiscoveredModel(JsonObject(model + ("policy" to obj("state" to str("disabled"))))))
        assertNull(copilotDiscoveredModel(JsonObject(model + ("model_picker_enabled" to JsonPrimitive(false)))))
        val capabilities = model.getValue("capabilities").jsonObject
        assertNull(copilotDiscoveredModel(JsonObject(model + ("capabilities" to
            JsonObject(capabilities + ("type" to str("embeddings")))))))
        val limits = capabilities.getValue("limits").jsonObject
        assertNull(copilotDiscoveredModel(JsonObject(model + ("capabilities" to JsonObject(capabilities +
            ("limits" to JsonObject(limits - "max_output_tokens")))))))
        assertNull(copilotDiscoveredModel(JsonObject(model + ("supported_endpoints" to
            JsonArray(listOf(str("/v1/embeddings")))))))

        // Display metadata and aggregate context size are optional; serving limits are not.
        val optional = JsonObject(model - "name" - "model_picker_enabled" + ("capabilities" to
            JsonObject(capabilities + ("limits" to JsonObject(limits - "max_context_window_tokens")))))
        assertEquals(100000, copilotDiscoveredModel(optional)?.contextWindowTokens)
        val messages = JsonObject(model + ("id" to str("claude-test")) +
            ("supported_endpoints" to JsonArray(listOf(str("/v1/messages")))))
        val responses = JsonObject(model + ("id" to str("responses-test")) +
            ("supported_endpoints" to JsonArray(listOf(str("/responses")))))
        assertNotNull(copilotDiscoveredModel(messages))
        assertNotNull(copilotDiscoveredModel(responses))
        assertEquals(ModelApiType.ANTHROPIC, copilotProtocol("claude-test", listOf("/v1/messages")))
        assertEquals(ModelApiType.OPENAI_RESPONSES, copilotProtocol("responses-test", listOf("/responses")))
    }

    @Test fun codexCatalogUsesLiveResponseWithoutAnApplicationModelAllowlist() {
        val catalog = parseCodexModelCatalog(Json.parseToJsonElement("""{
            "models": [
                {"slug":"live-model-a","display_name":"Live model A","context_window":128000},
                {"slug":"live-model-b","display_name":"Live model B"},
                {"slug":"internal-model","visibility":"internal"},
                {"slug":"live-model-a","display_name":"Duplicate"},
                {"display_name":"Missing ID"}
            ]
        }""").jsonObject)
        assertEquals(listOf("live-model-a", "live-model-b", "internal-model"), catalog.map { it.id })
        assertEquals("Live model A", catalog.first().displayName)
        assertEquals(128000, catalog.first().contextWindowTokens)
    }

    @Test fun copilotScopesAnthropicBetaAndHonorsExplicitProtocols() = runBlocking {
        MockWebServer().use { server ->
            fun entry(id: String, endpoint: String) = obj(
                "id" to str(id), "supported_endpoints" to JsonArray(listOf(str(endpoint))),
                "capabilities" to obj(
                    "limits" to obj("max_output_tokens" to JsonPrimitive(1000), "max_prompt_tokens" to JsonPrimitive(10000)),
                    "supports" to obj("tool_calls" to JsonPrimitive(true))
                )
            )
            val catalog = obj("data" to JsonArray(listOf(
                entry("chat-model", "/chat/completions"), entry("responses-model", "/responses"),
                entry("messages-model", "/v1/messages")
            ))).toString()
            repeat(3) { server.enqueue(json(catalog)) }
            AccountTokenStore(context, "github_copilot").save("headers", AccountTokenState("token", ""))
            val base = provider(ModelApiType.GITHUB_COPILOT, server, "headers")
            val p = base.copy(config = base.config.copy(headers = mapOf("anthropic-beta" to "caller-value")))
            val adapter = GitHubCopilotAdapter(context, AttachmentResolver(context))
            val chat = adapter.prepareModelProvider(p, "chat-model")
            val responses = adapter.prepareModelProvider(p, "responses-model")
            val messages = adapter.prepareModelProvider(p, "messages-model")
            assertEquals(ModelApiType.OPENAI, chat.type)
            assertEquals(ModelApiType.OPENAI_RESPONSES, responses.type)
            assertEquals(ModelApiType.ANTHROPIC, messages.type)
            assertFalse(chat.config.headers.containsKey("anthropic-beta"))
            assertFalse(responses.config.headers.containsKey("anthropic-beta"))
            assertEquals("interleaved-thinking-2025-05-14", messages.config.headers["anthropic-beta"])
            assertEquals(ModelApiType.OPENAI, copilotProtocol("unmatched-model", emptyList()))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun copilotRefreshDoesNotKeepRemovedModelsOrRouteThemUsingOldAccountMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"data":[{"id":"was-available","supported_endpoints":["/v1/messages"],"capabilities":{"limits":{"max_output_tokens":1000,"max_prompt_tokens":10000},"supports":{"tool_calls":true}}}]}"""))
            server.enqueue(json("""{"data":[{"id":"now-available","supported_endpoints":["/responses"],"capabilities":{"limits":{"max_output_tokens":1000,"max_prompt_tokens":10000},"supports":{"tool_calls":true}}}]}"""))
            server.enqueue(json("""{"data":[]}"""))
            AccountTokenStore(context, "github_copilot").save("refresh", AccountTokenState("token", ""))
            val p = provider(ModelApiType.GITHUB_COPILOT, server, "refresh")
            val adapter = GitHubCopilotAdapter(context, AttachmentResolver(context))
            assertEquals(listOf("was-available"), adapter.fetchModels(p))
            assertEquals(listOf("now-available"), adapter.fetchModels(p))
            val failed = runCatching { adapter.prepareModelProvider(p, "was-available") }.exceptionOrNull()
            assertTrue(failed!!.message!!.contains("unavailable for the selected account"))
        }
    }

    @Test fun copilotCatalogFailureDoesNotRouteUsingModelName() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))
            AccountTokenStore(context, "github_copilot").save("catalog-error", AccountTokenState("token", ""))
            val p = provider(ModelApiType.GITHUB_COPILOT, server, "catalog-error")
            val adapter = GitHubCopilotAdapter(context, AttachmentResolver(context))
            val failed = runCatching { adapter.prepareModelProvider(p, "claude-sonnet-4") }.exceptionOrNull()
            assertNotNull(failed)
        }
    }

    @Test fun copilotCatalogCancellationPropagates() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            AccountTokenStore(context, "github_copilot").save("catalog-cancel", AccountTokenState("token", ""))
            val p = provider(ModelApiType.GITHUB_COPILOT, server, "catalog-cancel")
            val adapter = GitHubCopilotAdapter(context, AttachmentResolver(context))
            val job = async { adapter.prepareModelProvider(p, "claude-sonnet-4") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            job.cancel()
            try {
                job.await()
                fail("Catalog cancellation must propagate")
            } catch (_: CancellationException) { }
        }
    }
}
