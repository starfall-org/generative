package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import org.starfall.generative.features.home.shared.logic.ToolRequestService
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.helpers.networking.*

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class ToolTokenUsageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test(timeout = 60000) fun toolRoundsEmitSeparateLatestSnapshotsWithoutReplayingUsageInEitherMode() = runBlocking {
        for (stream in listOf(false, true)) {
            MockWebServer().use { server ->
                val root = Files.createTempDirectory("tool-usage").toFile()
                val modelBodies = mutableListOf<JsonObject>()
                var toolCalls = 0
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "DELETE") return MockResponse().setResponseCode(204)
                        if (request.method == "GET" && request.path == "/mcp") return MockResponse().setResponseCode(405)
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        if (request.path == "/mcp") {
                            val result = when (body.text("method")) {
                                "notifications/initialized" -> return MockResponse().setResponseCode(202)
                                "initialize" -> obj("protocolVersion" to str("2025-06-18"), "capabilities" to obj("tools" to obj()),
                                    "serverInfo" to obj("name" to str("test"), "version" to str("1")))
                                "tools/list" -> obj("tools" to JsonArray(listOf(obj("name" to str("echo"),
                                    "inputSchema" to obj("type" to str("object"))))))
                                "tools/call" -> {
                                    toolCalls++
                                    obj("content" to JsonArray(listOf(obj("type" to str("text"), "text" to str("tool reply")))))
                                }
                                else -> error("Unexpected MCP method")
                            }
                            return json(obj("jsonrpc" to str("2.0"), "id" to body.getValue("id"), "result" to result))
                        }
                        modelBodies += body
                        val first = modelBodies.size == 1
                        val name = body.requireArray("tools").first().jsonObject.requireObject("function").text("name")
                        val message = buildJsonObject {
                            put("role", "assistant")
                            put("content", if (first) "" else "done")
                            if (first) put("tool_calls", JsonArray(listOf(obj("id" to str("call1"), "type" to str("function"),
                                "function" to obj("name" to str(name), "arguments" to str("{}"))))))
                        }
                        val usage = obj("prompt_tokens" to JsonPrimitive(if (first) 100 else 40),
                            "completion_tokens" to JsonPrimitive(if (first) 5 else 3))
                        if (!stream) return json(obj("choices" to JsonArray(listOf(obj("message" to message))), "usage" to usage))
                        val delta = obj("choices" to JsonArray(listOf(obj("delta" to message))))
                        val earlierUsage = JsonObject(usage + ("completion_tokens" to JsonPrimitive(1)))
                        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: $delta\n\ndata: ${obj("usage" to earlierUsage)}\n\ndata: ${obj("usage" to usage)}\n\ndata: [DONE]\n\n")
                    }
                }
                try {
                    val provider = ModelProvider("p", "Usage", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString(),
                        config = ProviderConfiguration(supportStream = stream))
                    val http = ToolHttp(ToolFiles(root))
                    val events = ToolChatViewModel(http, McpService(http), AiService(context)).generate(
                        provider, "chat", listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Call echo")))),
                        "", listOf(McpInfo("s", "MCP", url = server.url("/mcp").toString())), listOf(provider),
                        { ToolSettings() }, maxToolRounds = { 3 }
                    ).toList()
                    assertEquals(1, toolCalls)
                    assertEquals(2, modelBodies.size)
                    val usages = events.filterIsInstance<GenerationEvent.Usage>().map { it.usage }
                    assertEquals(listOf(TokenUsage(100, 5), TokenUsage(40, 3)), usages)
                    assertEquals(TokenUsage(40, 3), usages.last())
                    assertEquals("done", events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
                    assertTrue(modelBodies.last().toString().contains("tool reply"))
                    modelBodies.forEach { body ->
                        val wire = body.toString()
                        assertFalse(wire.contains("\"usage\":"))
                        assertFalse(wire.contains("prompt_tokens"))
                        assertFalse(wire.contains("completion_tokens"))
                        if (stream) assertEquals(JsonPrimitive(true), body["stream_options"]?.jsonObject?.get("include_usage"))
                        else assertNull(body["stream_options"])
                    }
                } finally { root.deleteRecursively() }
            }
        }
    }

    @Test(timeout = 30000) fun requestUsageCallbackIsOptionalAndAbsentUsageDoesNotEmitSyntheticCounts() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(json(obj("choices" to JsonArray(listOf(obj("message" to obj("content" to str("hello")))))))) }
            val provider = ModelProvider("p", "Usage", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString(),
                config = ProviderConfiguration(supportStream = false))
            val service = ToolRequestService(ToolHttp(), AiService(context))
            val history = listOf(obj("role" to str("user"), "content" to str("hi")))
            val withoutCallback = service.request(provider, "chat", history, emptyList(), "", false, {}, {})
            val usages = mutableListOf<TokenUsage>()
            val withCallback = service.request(provider, "chat", history, emptyList(), "", false, {}, {}, { usages += it })
            assertEquals("hello", withoutCallback.text("content"))
            assertEquals(withoutCallback, withCallback)
            assertTrue(usages.isEmpty())
        }
    }

    private fun json(value: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
}
