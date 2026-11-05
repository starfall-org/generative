package org.starfall.generative

import org.starfall.generative.integrations.security.OAuthRecordStorageOwner


import org.starfall.generative.integrations.modelapis.account.*

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.starfall.generative.shared.subfeatures.auth.logic.*
import org.starfall.generative.integrations.modelapis.githubcopilot.copilotProtocol
import org.robolectric.annotation.Config
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.starfall.generative.integrations.modelapis.account.AccountProviderAdapterRegistry
import org.starfall.generative.integrations.modelapis.openaicodex.CODEX_SESSION_SCOPE
import org.starfall.generative.integrations.modelapis.openaicodex.CodexTokenState
import org.starfall.generative.integrations.modelapis.openaicodex.CodexTokenStore
import org.starfall.generative.integrations.modelapis.openaicodex.OpenAICodexAdapter
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.subfeatures.media.logic.*
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.database.preferences.*
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.features.home.shared.logic.toolAttachmentParts
import org.starfall.generative.features.home.shared.logic.toolMessageContent
import org.starfall.generative.shared.model.auth.defaultAuthorization

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class AccountAdapterTest {
    @Before fun prepareKeystore() = installTestAndroidKeyStore()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun antigravityResolvesCanonicalIdsToBackendRoutes() {
        val models = org.starfall.generative.integrations.modelapis.antigravity.AntigravityModels
        assertEquals("gemini-pro-agent", models.resolve("gemini-3.1-pro"))
        assertEquals("gemini-3.1-pro-low", models.resolve("gemini-3.1-pro", "low"))
        assertEquals("gemini-3-flash-agent", models.resolve("gemini-3.5-flash"))
        assertEquals("claude-opus-4-6-thinking", models.resolve("claude-opus-4-6"))
        assertEquals("claude-sonnet-4-6", models.resolve("claude-sonnet-4-6-thinking"))
        assertEquals("custom-model", models.resolve("custom-model"))
        // Levels are never swapped for another one.
        assertEquals("gemini-3.6-flash-minimal", models.resolve("gemini-3.6-flash", "minimal"))
        assertEquals("gemini-3.7-flash-xhigh", models.resolve("gemini-3.7-flash", "xhigh"))
        assertEquals("gemini-3.5-flash-minimal", models.resolve("gemini-3.5-flash", "minimal"))
        assertEquals("gemini-3.1-pro-minimal", models.resolve("gemini-3.1-pro", "minimal"))
        assertEquals("gemini-3.1-pro-medium", models.resolve("gemini-3.1-pro", "medium"))
    }

    @Test fun allAccountProvidersUseOAuthAndAreRegistered() {
        val registry = AccountProviderAdapterRegistry(context, AttachmentResolver(context))
        for (type in listOf(ModelApiType.OPENAI_CODEX, ModelApiType.CLAUDE_CODE, ModelApiType.ANTIGRAVITY, ModelApiType.GITHUB_COPILOT)) {
            assertTrue(type.isAccountProvider)
            assertEquals(AuthMethod.OAUTH, type.defaultAuthorization().method)
            assertNotNull(registry.get(type))
            assertEquals("", Authorization(AuthMethod.OAUTH, value = "private-token").token)
        }
    }

    @Test fun copilotSetsInitiatorAndVisionHeadersFromRequest() {
        val adapter = org.starfall.generative.integrations.modelapis.githubcopilot.GitHubCopilotAdapter(context, AttachmentResolver(context))
        val source = ModelProvider("copilot", "Copilot", ModelApiType.GITHUB_COPILOT, baseUrl = ModelApiType.GITHUB_COPILOT.defaultBaseUrl)
        val wire = source.copy(type = ModelApiType.OPENAI)
        val userBody = obj("messages" to JsonArray(listOf(obj("role" to str("user"), "content" to JsonArray(listOf(
            obj("type" to str("image_url"), "image_url" to obj("url" to str("data:image/png;base64,aA==")))
        ))))))
        val user = adapter.prepareRequestProvider(source, wire, userBody)
        assertEquals("user", user.config.headers["x-initiator"])
        assertEquals("true", user.config.headers["Copilot-Vision-Request"])
        val tool = adapter.prepareRequestProvider(source, wire, obj("messages" to JsonArray(listOf(obj("role" to str("tool"))))))
        assertEquals("agent", tool.config.headers["x-initiator"])
        assertNull(tool.config.headers["Copilot-Vision-Request"])
        val responses = adapter.normalizeToolRequest(source, wire.copy(type = ModelApiType.OPENAI_RESPONSES), obj(), "User prompt")
        assertEquals("User prompt", responses.text("instructions"))
        assertEquals(false, responses.getValue("store").jsonPrimitive.boolean)
    }

    @Test fun codeAssistStreamUnwrapsEnvelopeAndPreservesFunctionCalls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"plan\",\"thought\":true},{\"text\":\"hello\"},{\"functionCall\":{\"name\":\"lookup\",\"args\":{}}}]}}]}}\n\n"))
            val provider = ModelProvider("test", "Test", ModelApiType.GOOGLE, baseUrl = server.url("/").toString(),
                auth = Authorization(AuthMethod.CUSTOM_HEADER, "Authorization", "Bearer test"))
            val text = StringBuilder(); val reasoning = StringBuilder()
            val response = ToolHttp().modelResponse(server.url("/v1internal:generateContent").toString(), obj(), provider, true,
                { text.append(it) }, { reasoning.append(it) }, { it.getValue("response").jsonObject })
            assertEquals("hello", text.toString()); assertEquals("plan", reasoning.toString())
            val parts = response.getValue("candidates").jsonArray.first().jsonObject.getValue("content").jsonObject.getValue("parts").jsonArray
            assertTrue(parts.any { it.jsonObject["functionCall"] != null })
            val request = server.takeRequest()
            assertEquals("/v1internal:streamGenerateContent?alt=sse", request.path)
            assertEquals("Bearer test", request.getHeader("Authorization"))
            assertNull(request.getHeader("x-goog-api-key"))
        }
    }

    @Test fun oauthPollingAllowsPendingWithoutWeakeningNormalErrors() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("{\"error\":\"authorization_pending\"}")) }
            val http = ToolHttp()
            val request = http.request(server.url("/token").toString()).get().build()
            assertEquals("authorization_pending", http.json(request, allowOAuthError = true).text("error"))
            try { http.json(request); fail("Normal provider errors must still fail") } catch (_: IllegalStateException) { }
        }
    }
    @Test fun refreshRotatesCredentialsAndPreservesIdentityProjectAndRoute() {
        val old = AccountTokenState("old", "refresh", "Bearer", 100, "account", "user@example.com", "project",
            "https://daily-cloudcode-pa.googleapis.com")
        val rotated = accountTokenFromResponse(obj("access_token" to str("new"),
            "refresh_token" to str("rotated"), "expires_in" to JsonPrimitive(3600)), old, now = 1000)
        assertEquals("new", rotated.accessToken)
        assertEquals("rotated", rotated.refreshToken)
        assertEquals(3601000L, rotated.expiresAt)
        assertEquals(old.accountId, rotated.accountId)
        assertEquals(old.email, rotated.email)
        assertEquals(old.projectId, rotated.projectId)
        assertEquals(old.codeAssistBaseUrl, rotated.codeAssistBaseUrl)
        val partial = accountTokenFromResponse(obj("access_token" to str("newer")), rotated)
        assertEquals("rotated", partial.refreshToken)
        assertNull(partial.expiresAt)
        assertEquals(old.codeAssistBaseUrl, partial.codeAssistBaseUrl)
    }

    @Test fun encryptedCredentialsAreIsolatedByProviderAndAdapter() {
        val claude = AccountTokenStore(context, "test_claude")
        val google = AccountTokenStore(context, "test_google")
        val first = AccountTokenState("private-access-one", "private-refresh-one")
        val second = AccountTokenState("private-access-two", "private-refresh-two")
        claude.save("one", first); claude.save("two", second); google.save("one", second)
        assertEquals(first, claude.load("one")); assertEquals(second, claude.load("two"))
        assertEquals(second, google.load("one"))
        val encrypted = (context.applicationContext as OAuthRecordStorageOwner).oauthRecordStorage
            .get("generative.test_claude.oauth", "one")!!
        assertFalse(encrypted.contains(first.accessToken))
        assertFalse(encrypted.contains(first.refreshToken))
        claude.delete("one")
        assertNull(claude.load("one")); assertEquals(second, claude.load("two"))
        assertEquals(second, google.load("one"))
    }

    @Test fun copilotSelectsProtocolsAndRecognizesToolResultContinuation() {
        assertEquals(ModelApiType.ANTHROPIC, copilotProtocol("claude-test", listOf("/v1/messages")))
        assertEquals(ModelApiType.OPENAI_RESPONSES, copilotProtocol("gpt-10.1", emptyList()))
        assertEquals(ModelApiType.OPENAI, copilotProtocol("gpt-5-mini", listOf("/chat/completions")))
        assertEquals(ModelApiType.OPENAI_RESPONSES, copilotProtocol("custom", listOf("/responses")))
        val adapter = org.starfall.generative.integrations.modelapis.githubcopilot.GitHubCopilotAdapter(context, AttachmentResolver(context))
        val source = ModelProvider("copilot", "Copilot", ModelApiType.GITHUB_COPILOT, baseUrl = "https://api.githubcopilot.com")
        val result = adapter.prepareRequestProvider(source, source.copy(type = ModelApiType.ANTHROPIC),
            obj("messages" to JsonArray(listOf(obj("role" to str("user"), "content" to JsonArray(listOf(
                obj("type" to str("tool_result"), "tool_use_id" to str("call"), "content" to str("result"))
            )))))))
        assertEquals("agent", result.config.headers["x-initiator"])
        assertEquals("user", adapter.prepareRequestProvider(source, source, obj()).config.headers["x-initiator"])
    }

    @Test fun attachmentsUseSelectedWireContentTypes() {
        val message = obj("content" to str("look"), "_attachments" to JsonArray(listOf(
            obj("mimeType" to str("image/png"), "data" to str("aA==")))))
        val response = toolMessageContent(message, ModelApiType.OPENAI_RESPONSES).jsonArray
        assertEquals("input_text", response[0].jsonObject.text("type"))
        assertEquals("input_image", response[1].jsonObject.text("type"))
        assertEquals("data:image/png;base64,aA==", response[1].jsonObject.text("image_url"))
        assertEquals("image", toolAttachmentParts(message, ModelApiType.ANTHROPIC)[0].jsonObject.text("type"))
        assertNotNull(toolAttachmentParts(message, ModelApiType.GOOGLE)[0].jsonObject["inlineData"])
        assertEquals("image_url", toolAttachmentParts(message, ModelApiType.OPENAI)[0].jsonObject.text("type"))
        val pdf = obj("_attachments" to JsonArray(listOf(obj("mimeType" to str("application/pdf"), "data" to str("aA==")))))
        assertEquals("document", toolAttachmentParts(pdf, ModelApiType.ANTHROPIC)[0].jsonObject.text("type"))
    }

    @Test fun nonStreamingEnvelopePreservesReasoningSignatureAndTools() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":{"candidates":[{"content":{"parts":[{"thought":true,"text":"plan"},{"functionCall":{"name":"lookup","args":{}},"thoughtSignature":"signed"}]}}]}}"""))
            val provider = ModelProvider("test", "Test", ModelApiType.GOOGLE, baseUrl = server.url("/").toString())
            val response = ToolHttp().modelResponse(server.url("/generate").toString(), obj(), provider, false,
                {}, {}, { it.getValue("response").jsonObject })
            val parts = response["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
            assertEquals("plan", parts[0].jsonObject.text("text"))
            assertEquals("signed", parts[1].jsonObject.text("thoughtSignature"))
            assertNotNull(parts[1].jsonObject["functionCall"])
        }
    }

    @Test fun cancelledHttpRequestPropagatesCancellation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val request = ToolHttp().request(server.url("/token").toString()).get().build()
            val job = async { ToolHttp().json(request) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            job.cancel()
            try { job.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
    }

    @Test fun cancellingSseAggregationDuringResponseBodyPropagates() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"chatcmpl-test\",\"choices\":[]}\n\n" + " ".repeat(8192))
                .throttleBody(128, 10, java.util.concurrent.TimeUnit.SECONDS))
            val provider = ModelProvider("sse-cancel", "Test", ModelApiType.OPENAI,
                auth = Authorization(AuthMethod.BEARER_TOKEN, value = "token"),
                baseUrl = server.url("/v1").toString())
            val job = async(Dispatchers.IO) {
                ToolHttp().modelResponse(server.url("/v1/chat/completions").toString(), obj(), provider,
                    true, {}, {})
            }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            delay(100) // Allow the streaming parser to enter the throttled response body.
            job.cancel()
            try {
                job.await()
                fail("SSE aggregation cancellation must propagate")
            } catch (_: CancellationException) { }
        }
    }

    @Test fun accountAdaptersGenerateRegularChatWithAndWithoutStreaming() = runBlocking {
        for (type in listOf(ModelApiType.CLAUDE_CODE, ModelApiType.ANTIGRAVITY, ModelApiType.GITHUB_COPILOT)) {
            for (stream in listOf(false, true)) {
                MockWebServer().use { server ->
                    val id = "regular-${type.name}-$stream"
                    AccountTokenStore(context, type.name.lowercase()).save(id,
                        AccountTokenState("test-access", "test-refresh", projectId = "test-project"))
                    val body = when (type) {
                        ModelApiType.CLAUDE_CODE -> """{"id":"msg_test","type":"message","role":"assistant","model":"claude-test","content":[{"type":"thinking","thinking":"plan","signature":"signed"},{"type":"text","text":"hello"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""
                        ModelApiType.ANTIGRAVITY -> """{"response":{"candidates":[{"content":{"parts":[{"thought":true,"text":"plan"},{"text":"hello"}]}}]}}"""
                        else -> """{"id":"chatcmpl-test","object":"chat.completion","created":0,"model":"chat","choices":[{"index":0,"message":{"role":"assistant","content":"hello","reasoning_content":"plan"},"finish_reason":"stop"}]}"""
                    }
                    val eventBody = when (type) {
                        ModelApiType.CLAUDE_CODE -> listOf(
                            """{"type":"message_start","message":{"id":"msg_test","type":"message","role":"assistant","model":"claude-test","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}""",
                            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}""",
                            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed"}}""",
                            """{"type":"content_block_stop","index":0}""",
                            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
                            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"hello"}}""",
                            """{"type":"content_block_stop","index":1}""",
                            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}""",
                            """{"type":"message_stop"}"""
                        ).joinToString("") { event ->
                            "event: " + Json.parseToJsonElement(event).jsonObject.getValue("type").jsonPrimitive.content + "\ndata: $event\n\n"
                        }
                        ModelApiType.ANTIGRAVITY -> "data: $body\n\n"
                        else -> """data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"chat","choices":[{"index":0,"delta":{"content":"hello","reasoning_content":"plan"},"finish_reason":null}]}""" + "\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    }
                    if (type == ModelApiType.GITHUB_COPILOT)
                        server.enqueue(MockResponse().setBody("""{"data":[{"id":"chat","supported_endpoints":["/chat/completions"],"capabilities":{"limits":{"max_output_tokens":1000,"max_prompt_tokens":10000},"supports":{"tool_calls":true}}}]}"""))
                    server.enqueue(MockResponse().setHeader("Content-Type",
                        if (stream) "text/event-stream" else "application/json").setBody(if (stream) eventBody else body))
                    val provider = ModelProvider(id, type.displayName, type,
                        auth = Authorization(AuthMethod.OAUTH, value = "account:${type.name.lowercase()}:v1"),
                        baseUrl = server.url("/v1").toString(), config = ProviderConfiguration(supportStream = stream))
                    val adapter = AccountProviderAdapterRegistry(context, AttachmentResolver(context)).get(type)!!
                    val events = adapter.streamEvents(provider, "chat",
                        listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("hi")))),
                        "User instructions", 4000).toList()
                    assertEquals("$type stream=$stream", "hello",
                        events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
                    assertEquals("$type stream=$stream", "plan",
                        events.filterIsInstance<GenerationEvent.Reasoning>().joinToString("") { it.text })
                    if (type == ModelApiType.GITHUB_COPILOT) server.takeRequest()
                    val request = server.takeRequest()
                    assertEquals("Bearer test-access", request.getHeader("Authorization"))
                    assertNull(request.getHeader("x-api-key")); assertNull(request.getHeader("x-goog-api-key"))
                    assertTrue(request.body.readUtf8().contains("User instructions"))
                    if (type == ModelApiType.CLAUDE_CODE)
                        assertTrue(events.filterIsInstance<GenerationEvent.Reasoning>().any { it.signature == "signed" })
                    if (type == ModelApiType.ANTIGRAVITY)
                        assertEquals(if (stream) "/v1/v1internal:streamGenerateContent?alt=sse"
                            else "/v1/v1internal:generateContent", request.path)
                }
            }
        }
    }

    private fun refreshingAdapter(tokenUrl: String) = object : OAuthAccountAdapter(
        context, AttachmentResolver(context), ModelApiType.CLAUDE_CODE, "test-client",
        "https://example.com/authorize", tokenUrl, "scope", 0, "/callback", jsonTokens = true
    ) {
        override suspend fun prepareAuthenticatedProvider(provider: ModelProvider): ModelProvider {
            val token = ensureToken(provider)
            return provider.copy(type = ModelApiType.ANTHROPIC,
                auth = Authorization(AuthMethod.CUSTOM_HEADER, "Authorization", "Bearer ${token.accessToken}"))
        }
        override suspend fun fetchModels(provider: ModelProvider) = listOf("chat")
    }

    @Test fun concurrentRefreshIsSerializedAndPersistsRotatedToken() = runBlocking {
        MockWebServer().use { server ->
            val store = AccountTokenStore(context, "claude_code")
            store.save("refresh", AccountTokenState("expired", "old-refresh", expiresAt = 1,
                accountId = "account", email = "user@example.com", projectId = "project"))
            server.enqueue(MockResponse().setBody("""{"access_token":"fresh","refresh_token":"rotated","expires_in":3600}"""))
            val provider = ModelProvider("refresh", "Claude", ModelApiType.CLAUDE_CODE,
                auth = Authorization(AuthMethod.OAUTH, value = "account:claude_code:v1"), baseUrl = "https://example.com")
            val adapter = refreshingAdapter(server.url("/token").toString())
            val prepared = (1..10).map { async { adapter.prepareAuthenticatedProvider(provider) } }.awaitAll()
            assertTrue(prepared.all { it.auth.value == "Bearer fresh" })
            assertEquals(1, server.requestCount)
            val saved = store.load("refresh")!!
            assertEquals("rotated", saved.refreshToken); assertEquals("account", saved.accountId)
            assertEquals("user@example.com", saved.email); assertEquals("project", saved.projectId)
            assertEquals("account:claude_code:v1", provider.auth.value)
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("refresh_token", request.text("grant_type"))
            assertEquals("old-refresh", request.text("refresh_token"))
        }
    }

    @Test fun codexSessionAndCacheIdentityAreStableExplicitAndConversationScoped() = runBlocking {
        val store = CodexTokenStore(context)
        store.save("codex-session", CodexTokenState("access", "refresh", expiresAt = Long.MAX_VALUE))
        val provider = ModelProvider("codex-session", "Codex", ModelApiType.OPENAI_CODEX,
            auth = Authorization(AuthMethod.OAUTH, value = OpenAICodexAdapter.AUTH_MARKER),
            baseUrl = ModelApiType.OPENAI_CODEX.defaultBaseUrl)
        val adapter = OpenAICodexAdapter(context, AttachmentResolver(context))
        val wire = adapter.prepareAuthenticatedProvider(provider)
        val secondWire = adapter.prepareAuthenticatedProvider(provider)
        assertEquals(wire.config.headers["Session_id"], secondWire.config.headers["Session_id"])

        fun request(scope: String) = obj(
            "model" to str("gpt-5-codex"), CODEX_SESSION_SCOPE to str(scope),
            "input" to JsonArray(listOf(obj("role" to str("user"), "content" to str("same text"))))
        )
        val first = adapter.normalizeToolRequest(provider, wire, request("message-a"), "")
        val repeated = adapter.normalizeToolRequest(provider, wire, request("message-a"), "")
        val independent = adapter.normalizeToolRequest(provider, wire, request("message-b"), "")
        val cache = first.text("prompt_cache_key")
        assertEquals(cache, repeated.text("prompt_cache_key"))
        assertNotEquals(cache, independent.text("prompt_cache_key"))
        assertFalse(first.containsKey(CODEX_SESSION_SCOPE))
        val otherAccount = adapter.normalizeToolRequest(provider.copy(id = "other-account"), wire,
            request("message-a"), "")
        assertNotEquals(cache, otherAccount.text("prompt_cache_key"))

        val scopedWire = adapter.prepareRequestProvider(provider, wire, request("message-a"))
        assertEquals(cache, scopedWire.config.headers["Session_id"])
        assertEquals(cache, scopedWire.config.headers["Conversation_id"])
        val explicit = JsonObject(request("message-a") + ("prompt_cache_key" to str("caller-cache")))
        assertEquals("caller-cache", adapter.normalizeToolRequest(provider, wire, explicit, "").text("prompt_cache_key"))
        val explicitWire = adapter.prepareRequestProvider(provider, wire, explicit)
        assertEquals("caller-cache", explicitWire.config.headers["Session_id"])
        assertEquals("caller-cache", explicitWire.config.headers["Conversation_id"])
    }

    @Test fun codexClientVersionDefaultsAndCanBeOverriddenPerProvider() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"models":[{"slug":"gpt-test"}]}"""))
            }
            CodexTokenStore(context).save("codex-version",
                CodexTokenState("access", "refresh", accountId = "account"))
            val provider = ModelProvider("codex-version", "Codex", ModelApiType.OPENAI_CODEX,
                auth = Authorization(AuthMethod.OAUTH, value = OpenAICodexAdapter.AUTH_MARKER),
                baseUrl = ModelApiType.OPENAI_CODEX.defaultBaseUrl)
            val adapter = OpenAICodexAdapter(context, AttachmentResolver(context),
                modelsEndpoint = server.url("/models").toString())

            assertEquals(listOf("gpt-test"), adapter.fetchModels(provider))
            val defaultRequest = server.takeRequest()
            assertEquals("/models?client_version=0.159.0", defaultRequest.path)
            assertEquals("codex-tui/0.159.0 (Android; arm64) Generative", defaultRequest.getHeader("User-Agent"))

            val custom = provider.copy(config = ProviderConfiguration(codexClientVersion = "0.160.0"))
            assertEquals(listOf("gpt-test"), adapter.fetchModels(custom))
            val customRequest = server.takeRequest()
            assertEquals("/models?client_version=0.160.0", customRequest.path)
            assertEquals("codex-tui/0.160.0 (Android; arm64) Generative", customRequest.getHeader("User-Agent"))
            assertEquals("codex-tui/0.160.0 (Android; arm64) Generative",
                adapter.prepareAuthenticatedProvider(custom).config.headers["User-Agent"])
        }
    }

    @Test fun codexConcurrentRefreshIsSerializedAndPersistsRotatedToken() = runBlocking {
        MockWebServer().use { server ->
            val store = CodexTokenStore(context)
            store.save("codex-refresh", CodexTokenState("expired", "old-refresh", expiresAt = 1,
                accountId = "account", email = "user@example.com"))
            server.enqueue(MockResponse().setBody(
                """{"access_token":"fresh","refresh_token":"rotated","expires_in":3600}"""))
            val provider = ModelProvider("codex-refresh", "Codex", ModelApiType.OPENAI_CODEX,
                auth = Authorization(AuthMethod.OAUTH, value = OpenAICodexAdapter.AUTH_MARKER),
                baseUrl = ModelApiType.OPENAI_CODEX.defaultBaseUrl)
            val adapters = List(2) {
                OpenAICodexAdapter(context, AttachmentResolver(context),
                    tokenEndpoint = server.url("/token").toString())
            }
            val prepared = (1..10).map { index ->
                async { adapters[index % adapters.size].prepareAuthenticatedProvider(provider) }
            }.awaitAll()
            assertTrue(prepared.all { it.auth.token == "fresh" })
            assertEquals(1, server.requestCount)
            val saved = store.load("codex-refresh")!!
            assertEquals("rotated", saved.refreshToken)
            assertEquals("account", saved.accountId)
            assertEquals("user@example.com", saved.email)
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("grant_type=refresh_token"))
            assertTrue(body.contains("refresh_token=old-refresh"))
        }
    }

    @Test fun codexCancellingRefreshKeepsStoredCredentialsAndPropagatesCancellation() = runBlocking {
        MockWebServer().use { server ->
            val original = CodexTokenState("expired", "refresh", expiresAt = 1)
            val store = CodexTokenStore(context)
            store.save("codex-cancel-refresh", original)
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val provider = ModelProvider("codex-cancel-refresh", "Codex", ModelApiType.OPENAI_CODEX,
                auth = Authorization(AuthMethod.OAUTH, value = OpenAICodexAdapter.AUTH_MARKER),
                baseUrl = ModelApiType.OPENAI_CODEX.defaultBaseUrl)
            val adapter = OpenAICodexAdapter(context, AttachmentResolver(context),
                tokenEndpoint = server.url("/token").toString())
            val job = async { adapter.prepareAuthenticatedProvider(provider) }
            withContext(Dispatchers.IO) {
                assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            job.cancel()
            try {
                job.await()
                fail("Codex refresh cancellation must propagate")
            } catch (_: CancellationException) { }
            assertEquals(original, store.load("codex-cancel-refresh"))
        }
    }

    @Test fun cancellingRefreshKeepsStoredCredentialsAndPropagatesCancellation() = runBlocking {
        MockWebServer().use { server ->
            val original = AccountTokenState("expired", "refresh", expiresAt = 1)
            val store = AccountTokenStore(context, "claude_code")
            store.save("cancel-refresh", original)
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val provider = ModelProvider("cancel-refresh", "Claude", ModelApiType.CLAUDE_CODE,
                auth = Authorization(AuthMethod.OAUTH, value = "account:claude_code:v1"), baseUrl = "https://example.com")
            val adapter = refreshingAdapter(server.url("/token").toString())
            val job = async { adapter.prepareAuthenticatedProvider(provider) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            job.cancel()
            try { job.await(); fail("Refresh cancellation must propagate") } catch (_: CancellationException) { }
            assertEquals(original, store.load("cancel-refresh"))
        }
    }

}
