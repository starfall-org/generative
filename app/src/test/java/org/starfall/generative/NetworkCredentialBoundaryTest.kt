package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.integrations.mcp.McpOAuthService
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.mcp.McpSession
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.integrations.speech.SpeechSynthesisService
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.room.AppDatabase
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class NetworkCredentialBoundaryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val messages = listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Hello"))))
    private val speech = SpeechService("speech", "Speech", modelId = "tts-1")

    private fun provider(server: MockWebServer, id: String = "chat") = ModelProvider(
        id, id, ModelApiType.OPENAI, baseUrl = server.url("/v1").toString(),
        config = ProviderConfiguration(supportStream = false, maxTokens = 321,
            modelConfigs = mapOf("chat" to ModelConfiguration(temperature = 0.35, reasoningEffort = "high"))),
        credentialState = CredentialLoadState.NOT_REQUESTED
    )

    private fun loaded(provider: ModelProvider) = provider.copy(
        auth = Authorization(value = "test-${provider.id}"),
        config = provider.config.copy(headers = mapOf("X-Resolved" to provider.id)),
        credentialState = CredentialLoadState.LOADED
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun answer() = json("""{"id":"reply","object":"chat.completion","created":1,"model":"chat","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}""")
    private fun image() = json("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))}"}]}""")

    private fun mcpResponse(request: RecordedRequest): MockResponse {
        if (request.method == "DELETE") return MockResponse().setResponseCode(204)
        if (request.method == "GET") return MockResponse().setResponseCode(405)
        val body = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
        val result = when (body["method"]!!.jsonPrimitive.content) {
            "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"test","version":"1"}}"""
            "notifications/initialized" -> return MockResponse().setResponseCode(202)
            "tools/list" -> """{"tools":[]}"""
            else -> error("Unexpected MCP method")
        }
        return json("""{"jsonrpc":"2.0","id":${body["id"]},"result":$result}""")
    }

    private fun scenario(block: suspend (MockWebServer, ToolHttp) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("network-credentials").toFile()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/chat/completions" -> answer()
                "/v1/images/generations" -> image()
                "/v1/audio/speech" -> MockResponse().setBody("audio")
                "/mcp" -> mcpResponse(request)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            withTimeout(20_000) { block(server, ToolHttp(ToolFiles(root))) }
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    private suspend fun blocked(action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected credential rejection, got ${error?.javaClass?.simpleName}", error is IllegalStateException)
        assertTrue(error!!.message.orEmpty().contains("credentials", ignoreCase = true))
    }

    private fun request(server: MockWebServer) = server.takeRequest(5, TimeUnit.SECONDS)
        ?: error("Expected a network request")

    @Test fun unwiredBoundariesRejectEveryIncompleteStateBeforeSendingAuth() = scenario { server, http ->
        val ai = AiService(context)
        val engine = ToolChatViewModel(http, McpService(http), ai)
        for (state in listOf(CredentialLoadState.NOT_REQUESTED, CredentialLoadState.LOADING, CredentialLoadState.ERROR)) {
            // Nonempty placeholders must not be mistaken for permission to send credentials.
            val p = provider(server).copy(auth = Authorization(value = "test-placeholder"), credentialState = state)
            blocked { ai.streamEvents(p, "chat", messages).toList() }
            blocked { engine.completeText(p, "chat", messages, "Title or summary") }
            blocked { engine.generate(p, "chat", messages, "", emptyList(), listOf(p), { ToolSettings() }).toList() }
            blocked { engine.generateMedia(p, "image", ModelType.IMAGE_GENERATION, "A tree").toList() }
            blocked { ai.prepareAccountProvider(p, "chat") }
            blocked { ai.fetchProviderModelCatalog(p) }
            assertTrue(ai.testConnection(p).isFailure)
            assertTrue(ai.testModel(p, "chat").isFailure)
            assertTrue(ai.authorizeProvider(p).isFailure)
            blocked { SpeechSynthesisService().synthesize(p, speech.copy(apiKey = "test-override"), "Hello") }
            blocked { http.request(server.url("/v1/models").toString(), p) }
            val info = McpInfo("mcp", "MCP", url = server.url("/mcp").toString(),
                auth = McpAuthorization(McpAuthMethod.OAUTH2, value = "test-placeholder"), credentialState = state)
            blocked { McpService(http, McpOAuthService(context, http)).session(info) }
            blocked { McpSession(info, http).initialize() }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun loadersCannotReturnIncompleteOrDifferentRecords() = scenario { server, http ->
        val p = provider(server)
        for (state in listOf(CredentialLoadState.NOT_REQUESTED, CredentialLoadState.LOADING, CredentialLoadState.ERROR)) {
            val loader: suspend (ModelProvider) -> ModelProvider = { loaded(it).copy(credentialState = state) }
            blocked { AiService(context, loader).streamEvents(p, "chat", messages).toList() }
            blocked { SpeechSynthesisService(loader).synthesize(p, speech, "Hello") }
            blocked {
                McpService(http, serverLoader = { it.copy(credentialState = state) }).session(
                    McpInfo("mcp", "MCP", url = server.url("/mcp").toString(), credentialState = CredentialLoadState.NOT_REQUESTED))
            }
        }
        assertTrue(runCatching {
            AiService(context) { loaded(it).copy(id = "other") }.streamEvents(p, "chat", messages).toList()
        }.exceptionOrNull()?.message.orEmpty().contains("different provider"))
        assertTrue(runCatching {
            SpeechSynthesisService { loaded(it).copy(id = "other") }.synthesize(p, speech, "Hello")
        }.exceptionOrNull()?.message.orEmpty().contains("different speech provider"))
        assertTrue(runCatching {
            McpService(http, serverLoader = { it.copy(id = "other", credentialState = CredentialLoadState.LOADED) })
                .session(McpInfo("mcp", "MCP", credentialState = CredentialLoadState.NOT_REQUESTED))
        }.exceptionOrNull()?.message.orEmpty().contains("different MCP server"))
        assertEquals(0, server.requestCount)
    }

    @Test fun loadedAndNoConfigProvidersWorkWithoutLoading() = scenario { server, http ->
        val ai = AiService(context) { error("Already-ready records must not be loaded") }
        val engine = ToolChatViewModel(http, McpService(http), ai)
        for (state in listOf(CredentialLoadState.LOADED, CredentialLoadState.NOT_CONFIGURED)) {
            val p = provider(server).copy(credentialState = state)
            assertSame(p, ai.resolveProvider(p))
            assertEquals("OK", engine.completeText(p, "chat", messages, ""))
            assertNull(request(server).getHeader("Authorization"))
            assertArrayEquals("audio".toByteArray(), SpeechSynthesisService {
                error("Already-ready records must not be loaded")
            }.synthesize(p, speech, "Hello"))
            assertNull(request(server).getHeader("Authorization"))
            val beforeMcp = server.requestCount
            assertTrue(McpService(http, serverLoader = { error("Already-ready server must not be loaded") })
                .discover(McpInfo("mcp", "MCP", url = server.url("/mcp").toString(), credentialState = state)).isEmpty())
            repeat(server.requestCount - beforeMcp) { assertNull(request(server).getHeader("Authorization")) }
        }
    }

    @Test fun repositoryLoaderKeepsCallerReasoningAndTokenEditsForEventsAndCompletion() = scenario { server, http ->
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val ai = AiService(context)
            val writer = ModelRepository(db, ai)
            writer.saveProvider(loaded(provider(server)))
            writer.saveProvider(loaded(provider(server, "unrelated")))
            val reader = ModelRepository(db, ai)
            val metadata = reader.allProviders.first()
            val selected = metadata.single { it.id == "chat" }.let { it.copy(config = it.config.copy(
                maxTokens = 77, modelConfigs = mapOf("chat" to ModelConfiguration(temperature = 0.2, reasoningEffort = "low")))) }
            val calls = mutableListOf<String>()
            ai.providerLoader = { calls += it.id; reader.resolveProvider(it) }
            val engine = ToolChatViewModel(http, McpService(http), ai)
            assertEquals("OK", engine.completeText(selected, "chat", messages, "Title", maxOutputTokens = 17))
            val title = request(server)
            assertEquals("Bearer test-chat", title.getHeader("Authorization"))
            assertEquals("chat", title.getHeader("X-Resolved"))
            val titleBody = Json.parseToJsonElement(title.body.readUtf8()).jsonObject
            assertEquals(17, titleBody["max_completion_tokens"]!!.jsonPrimitive.int)
            assertEquals("low", titleBody["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals(0.2, titleBody["temperature"]!!.jsonPrimitive.double, 0.0)
            assertEquals("OK", ai.streamEvents(selected, "chat", messages).toList()
                .filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
            val body = Json.parseToJsonElement(request(server).body.readUtf8()).jsonObject
            assertEquals(77, body["max_completion_tokens"]!!.jsonPrimitive.int)
            assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals(listOf("chat", "chat"), calls)
            assertTrue(metadata.all { it.credentialState == CredentialLoadState.NOT_REQUESTED && it.auth.value == null })
        } finally { db.close() }
    }

    @Test fun configuredMediaLoadsOnlyTheProviderActuallyInvoked() = scenario { server, http ->
        var rounds = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/images/generations" -> image()
                "/v1/chat/completions" -> if (++rounds == 1) json("""{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"generate_image","arguments":"{\"prompt\":\"A tree\"}"}}]}}]}""") else answer()
                else -> MockResponse().setResponseCode(404)
            }
        }
        val p = provider(server)
        val media = provider(server, "image-provider").let { it.copy(config = it.config.copy(
            modelIds = listOf("image"), modelConfigs = mapOf("image" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION)))) }
        val unused = provider(server, "unused")
        val calls = mutableListOf<String>()
        val ai = AiService(context) { calls += it.id; loaded(it) }
        val events = ToolChatViewModel(http, McpService(http), ai).generate(
            p, "chat", messages, "", emptyList(), listOf(p, media, unused),
            { ToolSettings(system = mapOf(
                "generate_image" to SystemToolConfig(true, media.id, "image"),
                "generate_video" to SystemToolConfig(true, unused.id, "video"))) }
        ).toList()
        assertEquals(listOf("chat", "image-provider"), calls)
        assertEquals(3, server.requestCount)
        val chatRequest = request(server)
        val mediaRequest = request(server)
        val followup = request(server)
        assertEquals("Bearer test-chat", chatRequest.getHeader("Authorization"))
        assertEquals("Bearer test-image-provider", mediaRequest.getHeader("Authorization"))
        assertEquals("Bearer test-chat", followup.getHeader("Authorization"))
        val body = Json.parseToJsonElement(chatRequest.body.readUtf8()).jsonObject
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(321, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(events.filterIsInstance<GenerationEvent.Tool>().any { it.activity.name == "generate_image" && it.activity.status == "success" })
    }

    @Test fun unusedConfiguredMediaAndDisabledMcpRemainUnloaded() = scenario { server, http ->
        val calls = mutableListOf<String>()
        val ai = AiService(context) { calls += it.id; loaded(it) }
        val mcp = McpService(http, serverLoader = { error("Disabled MCP must not be resolved") })
        val p = provider(server)
        val other = provider(server, "unused")
        ToolChatViewModel(http, mcp, ai).generate(p, "chat", messages, "",
            listOf(McpInfo("disabled", "Disabled", credentialState = CredentialLoadState.NOT_REQUESTED)),
            listOf(p, other), { ToolSettings(quickMcp = mapOf("disabled" to false),
                system = mapOf("generate_image" to SystemToolConfig(true, other.id, "image"))) }).toList()
        assertEquals(listOf("chat"), calls)
        assertEquals(1, server.requestCount)
    }

    @Test fun directMediaAndSpeechResolveTheirSelectedProvider() = scenario { server, http ->
        val calls = mutableListOf<String>()
        val loader: suspend (ModelProvider) -> ModelProvider = { calls += it.id; loaded(it) }
        val ai = AiService(context, loader)
        val engine = ToolChatViewModel(http, McpService(http), ai)
        val events = engine.generateMedia(provider(server, "direct"), "image", ModelType.IMAGE_GENERATION, "A tree").toList()
        val media = request(server)
        assertEquals("Bearer test-direct", media.getHeader("Authorization"))
        assertEquals("direct", media.getHeader("X-Resolved"))
        assertEquals("success", events.filterIsInstance<GenerationEvent.Tool>().last().activity.status)
        assertArrayEquals("audio".toByteArray(), SpeechSynthesisService(loader)
            .synthesize(provider(server, "voice"), speech, "Hello"))
        val voice = request(server)
        assertEquals("Bearer test-voice", voice.getHeader("Authorization"))
        assertEquals("voice", voice.getHeader("X-Resolved"))
        assertEquals(listOf("direct", "voice"), calls)
    }

    @Test fun mcpResolvesBeforeChoosingOAuthCredentialsAndConnecting() = scenario { server, http ->
        val calls = mutableListOf<String>()
        val mcp = McpService(http, McpOAuthService(context, http), serverLoader = {
            calls += it.id
            it.copy(url = server.url("/mcp").toString(), headers = mapOf("X-Resolved" to it.id),
                auth = McpAuthorization(McpAuthMethod.OAUTH2, value = "test-mcp"), credentialState = CredentialLoadState.LOADED)
        })
        assertTrue(mcp.discover(McpInfo("selected", "Selected", url = server.url("/metadata").toString(),
            credentialState = CredentialLoadState.NOT_REQUESTED)).isEmpty())
        assertEquals(listOf("selected"), calls)
        val requests = List(server.requestCount) { request(server) }
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.path == "/mcp" && it.getHeader("Authorization") == "Bearer test-mcp" })
        assertTrue(requests.all { it.getHeader("X-Resolved") == "selected" })
    }

    @Test fun credentialLoaderCancellationPropagatesWithoutNetworking() = scenario { server, http ->
        val stopped = CancellationException("Stopped")
        val loader: suspend (ModelProvider) -> ModelProvider = { throw stopped }
        assertTrue(runCatching { AiService(context, loader).streamEvents(provider(server), "chat", messages).toList() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { SpeechSynthesisService(loader).synthesize(provider(server), speech, "Hello") }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching {
            McpService(http, serverLoader = { throw stopped }).session(McpInfo("mcp", "MCP", credentialState = CredentialLoadState.NOT_REQUESTED))
        }.exceptionOrNull() is CancellationException)
        assertEquals(0, server.requestCount)
    }
}
