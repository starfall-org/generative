package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CustomHttpToolChatTest {
    @Test fun advertisesEnabledToolsExecutesHttpAndReturnsStructuredResultToModel() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = java.nio.file.Files.createTempDirectory("http-chat").toFile()
        try {
            MockWebServer().use { server ->
                server.start()
                var modelCalls = 0
                var httpCalls = 0
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/weather?city=Hanoi") {
                            httpCalls++
                            return MockResponse().addHeader("Content-Type", "application/json").setBody("""{"data":{"temperature":25}}""")
                        }
                        if (request.path == "/v1/models") return MockResponse().addHeader("Content-Type", "application/json")
                            .setBody("""{"data":[{"id":"chat"}]}""")
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val toolNames = body["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject.text("name") }
                        assertEquals(listOf("http_weather"), toolNames)
                        modelCalls++
                        val message = if (modelCalls == 1) obj("role" to str("assistant"), "content" to str(""),
                            "tool_calls" to JsonArray(listOf(obj("id" to str("http1"), "type" to str("function"),
                                "function" to obj("name" to str("http_weather"), "arguments" to str("""{"city":"Hanoi"}"""))))))
                        else {
                            assertTrue(body.toString().contains("temperature"))
                            obj("role" to str("assistant"), "content" to str("25 degrees"))
                        }
                        return MockResponse().addHeader("Content-Type", "application/json")
                            .setBody(obj("choices" to JsonArray(listOf(obj("message" to message)))).toString())
                    }
                }
                val tool = CustomHttpTool(name = "weather", description = "Get weather", enabled = true,
                    url = server.url("/weather").toString(),
                    parameters = Json.parseToJsonElement("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""").jsonObject,
                    query = obj("city" to str("{{city}}")), responseMapping = obj("temperature" to str("/data/temperature")))
                val provider = ModelProvider("p", "Chat", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString(),
                    config = ProviderConfiguration(supportStream = false))
                val http = ToolHttp(ToolFiles(root))
                val events = ToolChatViewModel(http, McpService(http), AiService(context),
                    customHttpTools = { listOf(tool, tool.copy(id = "disabled", name = "disabled", enabled = false)) }).generate(
                    provider, "chat", listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Weather?")))),
                    "", emptyList(), listOf(provider), { ToolSettings() }
                ).toList()
                assertEquals(1, httpCalls)
                assertEquals(2, modelCalls)
                assertTrue(events.filterIsInstance<GenerationEvent.Tool>().any { it.activity.wireName == "http_weather" && it.activity.status == "success" })
                assertEquals("25 degrees", events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
            }
        } finally { root.deleteRecursively() }
    }
}
