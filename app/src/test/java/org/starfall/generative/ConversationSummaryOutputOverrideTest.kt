package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.logic.generateConversationSummary
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.networking.ToolHttp
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ConversationSummaryOutputOverrideTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val model = ModelConfiguration(contextWindowTokens = 32_000,
        modelJson = Json.parseToJsonElement("""{"max_output_tokens":16384}""").jsonObject)
    private val conversation = Conversation("summary", "Summary request", 0, 0,
        listOf(StoredMessage("turn", ChatRole.USER, listOf(MessageVersion(content = "Keep this decision")))))

    private fun provider(server: MockWebServer) = ModelProvider("fixture", "Summary fixture", ModelApiType.OPENAI,
        baseUrl = server.url("/v1").toString(), config = ProviderConfiguration(supportStream = false,
            maxTokens = 4000, modelConfigs = mapOf("summary-model" to model)))

    private fun answer() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"id":"reply","object":"chat.completion","created":1,"model":"summary-model","choices":[{"index":0,"message":{"role":"assistant","content":"Summary"},"finish_reason":"stop"}]}""")

    private fun requestBody(server: MockWebServer): JsonObject {
        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Expected a summary HTTP request")
        return Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    }

    private fun fixture(block: suspend (MockWebServer, ToolChatViewModel) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("summary-output-fixture").toFile()
        try {
            MockWebServer().use { server ->
                val http = ToolHttp(ToolFiles(root))
                block(server, ToolChatViewModel(http, McpService(http), AiService(context)))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun explicitLargeSummaryTargetsReachHttpWithoutTheProviderDefaultTruncatingThem() = fixture { server, tool ->
        val provider = provider(server)
        for (target in listOf(6001, 8000, 10000)) {
            server.enqueue(answer())
            val text = generateConversationSummary(conversation, model, "", ConversationSummaryRequest(target),
                complete = { messages, prompt, output ->
                    tool.completeText(provider, "summary-model", messages, prompt, maxOutputTokens = output)
                })
            assertEquals("Summary", text)
            val body = requestBody(server)
            assertEquals(target, body.getValue("max_completion_tokens").jsonPrimitive.int)
            val messages = body.getValue("messages").jsonArray
            assertEquals("user", messages.last().jsonObject.getValue("role").jsonPrimitive.content)
            assertTrue(messages.last().jsonObject.getValue("content")
                .jsonPrimitive.content.contains("approximately $target tokens"))
            assertTrue("Summary request must not add a system role", messages.none {
                it.jsonObject.getValue("role").jsonPrimitive.content == "system"
            })
            assertEquals(4000, provider.config.maxTokens)
        }
    }

    @Test fun chunkedSummaryAlsoSendsInstructionsAsLastUserMessage() = fixture { server, tool ->
        val provider = provider(server)
        repeat(2) { server.enqueue(answer()) }
        generateConversationSummary(conversation, model, "", ConversationSummaryRequest(8000, chunked = true),
            complete = { messages, prompt, output ->
                tool.completeText(provider, "summary-model", messages, prompt, maxOutputTokens = output)
            })
        repeat(2) {
            val messages = requestBody(server).getValue("messages").jsonArray
            assertEquals("user", messages.last().jsonObject.getValue("role").jsonPrimitive.content)
            assertTrue(messages.last().jsonObject.getValue("content").jsonPrimitive.content.contains("summary"))
            assertTrue(messages.none { it.jsonObject.getValue("role").jsonPrimitive.content == "system" })
        }
    }

    @Test fun providerDefaultIsUsedOnlyWhenNoOutputOverrideIsGiven() = fixture { server, tool ->
        for (target in listOf(null, 1, 1200, 6000)) {
            server.enqueue(answer())
            tool.completeText(provider(server), "summary-model", conversation.messages, "", maxOutputTokens = target)
            assertEquals(target ?: 4000, requestBody(server).getValue("max_completion_tokens").jsonPrimitive.int)
        }
    }

    @Test fun httpTokenLimitErrorsCannotBeReturnedAsSuccessfulSummaryContent() = fixture { server, tool ->
        for (type in listOf(ModelApiType.OPENAI, ModelApiType.OLLAMA)) {
            server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"output token limit exceeded","type":"invalid_request_error"}}"""))
            val provider = provider(server).copy(type = type)
            val failure = runCatching {
                generateConversationSummary(conversation, model, "", ConversationSummaryRequest(8000),
                    complete = { messages, prompt, output ->
                        tool.completeText(provider, "summary-model", messages, prompt, maxOutputTokens = output)
                    })
            }.exceptionOrNull()
            assertNotNull("HTTP 400 must fail the summary for $type", failure)
            requestBody(server)
        }
    }
}
