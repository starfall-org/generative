package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.integrations.modelapis.ModelApiAdapter
import org.starfall.generative.integrations.modelapis.ModelApiRequest
import org.starfall.generative.integrations.modelapis.anthropic.AnthropicMessagesAdapter
import org.starfall.generative.integrations.modelapis.gemini.GeminiAdapter
import org.starfall.generative.integrations.modelapis.ollama.OllamaAdapter
import org.starfall.generative.integrations.modelapis.openai.OpenAIChatCompletionsAdapter
import org.starfall.generative.integrations.modelapis.openairesponses.OpenAIResponsesAdapter
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NormalTextAdapterTokenUsageEventsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prettyJson = Json { prettyPrint = true }
    private val attachments by lazy { AttachmentResolver(context) }
    private val sdk by lazy { OfficialProviderSdk(attachments) }
    private val httpClient = HttpClient(OkHttp)
    private val protocols = listOf(ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES,
        ModelApiType.ANTHROPIC, ModelApiType.GOOGLE, ModelApiType.OLLAMA)

    @After fun closeClient() { httpClient.close() }

    private fun adapter(type: ModelApiType): ModelApiAdapter = when (type) {
        ModelApiType.OPENAI -> OpenAIChatCompletionsAdapter(sdk)
        ModelApiType.OPENAI_RESPONSES -> OpenAIResponsesAdapter(sdk)
        ModelApiType.ANTHROPIC -> AnthropicMessagesAdapter(sdk)
        ModelApiType.GOOGLE -> GeminiAdapter(sdk)
        ModelApiType.OLLAMA -> OllamaAdapter(context, httpClient, attachments, Json)
        else -> error("Not a normal text protocol: $type")
    }

    private fun request(server: MockWebServer, type: ModelApiType, streaming: Boolean = true) = ModelApiRequest(
        provider = ModelProvider(id = "usage", name = "Usage test", type = type,
            baseUrl = server.url(if (type == ModelApiType.GOOGLE) "/v1beta" else "/v1").toString(),
            auth = Authorization(key = "local-usage-fixture"), config = ProviderConfiguration(supportStream = streaming)),
        modelName = "custom", messages = listOf(StoredMessage(id = "user", role = ChatRole.USER,
            versions = listOf(MessageVersion(content = "Hello")))), systemPrompt = "",
        temperature = null, topP = null, topK = null, maxTokens = 50)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(vararg chunks: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" })
    private fun anthropicSse(vararg chunks: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") {
            "event: ${Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content}\ndata: $it\n\n"
        })

    private fun chatChunk(usage: String = "null", text: String? = null) = buildJsonObject {
        put("id", "chat-usage"); put("object", "chat.completion.chunk"); put("created", 1); put("model", "custom")
        put("choices", buildJsonArray {
            if (text != null) add(buildJsonObject {
                put("index", 0); put("delta", buildJsonObject { put("content", text) })
            })
        })
        put("usage", Json.parseToJsonElement(usage))
    }.toString()

    private fun completeBody(type: ModelApiType, usage: String): String = when (type) {
        ModelApiType.OPENAI -> """{"id":"chat","object":"chat.completion","created":1,"model":"custom","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":$usage}"""
        ModelApiType.OPENAI_RESPONSES -> """{"id":"resp","object":"response","created_at":1,"status":"completed","model":"custom","error":null,"output":[{"type":"message","id":"msg","status":"completed","role":"assistant","content":[{"type":"output_text","text":"ok","annotations":[]}]}],"usage":$usage}"""
        ModelApiType.ANTHROPIC -> """{"id":"msg","type":"message","role":"assistant","model":"custom","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,"usage":$usage}"""
        ModelApiType.GOOGLE -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}],"usageMetadata":$usage}"""
        ModelApiType.OLLAMA -> """{"message":{"role":"assistant","content":"ok"},"done":true,$usage}"""
        else -> error("Not a normal text protocol: $type")
    }

    private fun usageJson(type: ModelApiType, input: String? = "10", output: String? = "3"): String {
        val fields = when (type) {
            ModelApiType.OPENAI -> "prompt_tokens" to "completion_tokens"
            ModelApiType.GOOGLE -> "promptTokenCount" to "candidatesTokenCount"
            ModelApiType.OLLAMA -> "prompt_eval_count" to "eval_count"
            else -> "input_tokens" to "output_tokens"
        }
        val value = buildJsonObject {
            input?.let { put(fields.first, Json.parseToJsonElement(it)) }
            output?.let { put(fields.second, Json.parseToJsonElement(it)) }
        }.toString()
        return if (type == ModelApiType.OLLAMA) value.removeSurrounding("{", "}") else value
    }

    private fun assertEvents(events: List<GenerationEvent>, expected: TokenUsage?, text: String = "ok") {
        assertEquals(text, events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
        assertEquals(expected?.let { listOf(GenerationEvent.Usage(it)) }.orEmpty(),
            events.filterIsInstance<GenerationEvent.Usage>())
        if (expected != null) assertEquals(GenerationEvent.Usage(expected), events.last())
    }

    @Test fun nonstreamingAllFiveProtocolsEmitOneUsageEvent() = runBlocking {
        for (type in protocols) MockWebServer().use { server ->
            server.enqueue(json(completeBody(type, usageJson(type))))
            assertEvents(adapter(type).stream(request(server, type, streaming = false)).toList(), TokenUsage(10, 3))
            val body = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            if (type == ModelApiType.OPENAI) assertFalse(body.containsKey("stream_options"))
        }
    }

    @Test fun allFiveProtocolsAcceptZeroAndInputOnlyButNeverInventInput() = runBlocking {
        for (type in protocols) MockWebServer().use { server ->
            for ((input, output, expected) in listOf(
                Triple("0", "0", TokenUsage(0, 0)), Triple("9", null, TokenUsage(9)),
                Triple(null, "4", null), Triple(null, null, null))) {
                val usage = usageJson(type, input, output).ifEmpty { "\"done_reason\":\"stop\"" }
                server.enqueue(json(completeBody(type, usage)))
                assertEvents(adapter(type).stream(request(server, type, streaming = false)).toList(), expected)
            }
        }
    }

    @Test fun invalidUsageDoesNotBreakNonstreamingContent() = runBlocking {
        for (type in protocols) MockWebServer().use { server ->
            for ((input, output) in listOf("-1" to "3", "10" to "-1")) {
                server.enqueue(json(completeBody(type, usageJson(type, input, output))))
                assertEvents(adapter(type).stream(request(server, type, streaming = false)).toList(), null)
            }
        }
    }

    @Test fun malformedSdkUsageDoesNotBreakContent() = runBlocking {
        for (type in listOf(ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES, ModelApiType.ANTHROPIC)) {
            MockWebServer().use { server ->
                for (usage in listOf("[]", "\"bad\"", usageJson(type, output = "\"bad\""))) {
                    server.enqueue(json(completeBody(type, usage)))
                    assertEvents(adapter(type).stream(request(server, type, streaming = false)).toList(), null)
                }
            }
        }
    }

    @Test fun openAiRequestsUsageAndReadsFinalEmptyChoicesChunk() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(chatChunk(text = "ok"), chatChunk("""{"prompt_tokens":10,"completion_tokens":1}"""),
                chatChunk("""{"prompt_tokens":10,"completion_tokens":2}"""),
                """{"usage":{"prompt_tokens":10,"completion_tokens":3}}""", "[DONE]"))
            assertEvents(adapter(ModelApiType.OPENAI).stream(request(server, ModelApiType.OPENAI)).toList(), TokenUsage(10, 3))
            val body = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            assertTrue(body["stream"]!!.jsonPrimitive.boolean)
            assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun openAiInputOnlySurvivesMalformedLaterUsageChunks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(chatChunk("""{"prompt_tokens":0}""", "ok"),
                chatChunk("""{"prompt_tokens":0,"completion_tokens":"bad"}"""),
                chatChunk("""{"prompt_tokens":-1,"completion_tokens":3}"""), "[DONE]"))
            assertEvents(adapter(ModelApiType.OPENAI).stream(request(server, ModelApiType.OPENAI)).toList(), TokenUsage(0))
        }
    }

    @Test fun reusedAdapterDoesNotCarryUsageAcrossRequests() = runBlocking {
        MockWebServer().use { server ->
            val adapter = adapter(ModelApiType.OPENAI)
            val request = request(server, ModelApiType.OPENAI)
            server.enqueue(sse(chatChunk("""{"prompt_tokens":10,"completion_tokens":3}""", "ok"), "[DONE]"))
            server.enqueue(sse(chatChunk(text = "ok"), "[DONE]"))
            assertEvents(adapter.stream(request).toList(), TokenUsage(10, 3))
            assertEvents(adapter.stream(request).toList(), null)
        }
    }

    @Test fun responsesUseCompletedAndIncompleteFinalUsageRatherThanCreatedUsage() = runBlocking {
        for (terminal in listOf("completed", "incomplete")) MockWebServer().use { server ->
            val created = completeBody(ModelApiType.OPENAI_RESPONSES, """{"input_tokens":999,"output_tokens":999}""")
            val finalResponse = completeBody(ModelApiType.OPENAI_RESPONSES, """{"input_tokens":10,"output_tokens":3}""")
                .replace("\"status\":\"completed\"", "\"status\":\"$terminal\"")
            server.enqueue(sse(
                """{"type":"response.created","sequence_number":0,"response":$created}""",
                """{"type":"response.output_text.delta","sequence_number":1,"item_id":"msg","output_index":0,"content_index":0,"delta":"ok"}""",
                """{"type":"response.$terminal","sequence_number":2,"response":$finalResponse}"""))
            assertEvents(adapter(ModelApiType.OPENAI_RESPONSES).stream(request(server, ModelApiType.OPENAI_RESPONSES)).toList(),
                TokenUsage(10, 3))
        }
    }

    @Test fun responsesFinalInputOnlyIsStillUseful() = runBlocking {
        MockWebServer().use { server ->
            val response = completeBody(ModelApiType.OPENAI_RESPONSES, """{"input_tokens":10}""")
            server.enqueue(sse("""{"type":"response.completed","sequence_number":0,"response":$response}"""))
            assertEvents(adapter(ModelApiType.OPENAI_RESPONSES).stream(request(server, ModelApiType.OPENAI_RESPONSES)).toList(),
                TokenUsage(10), text = "")
        }
    }

    @Test fun anthropicAddsCacheCategoriesAndReplacesCumulativeDeltaCounts() = runBlocking {
        MockWebServer().use { server ->
            val start = completeBody(ModelApiType.ANTHROPIC,
                """{"input_tokens":3,"cache_creation_input_tokens":5,"cache_read_input_tokens":7,"output_tokens":1}""")
            server.enqueue(anthropicSse(
                """{"type":"message_start","message":$start}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                """{"type":"message_delta","delta":{"stop_reason":null},"usage":{"output_tokens":2}}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":3,"cache_creation_input_tokens":5,"cache_read_input_tokens":7,"output_tokens":4}}""",
                """{"type":"message_delta","delta":{},"usage":{"output_tokens":"bad"}}""",
                """{"type":"message_stop"}"""))
            assertEvents(adapter(ModelApiType.ANTHROPIC).stream(request(server, ModelApiType.ANTHROPIC)).toList(), TokenUsage(15, 4))
        }
    }

    @Test fun anthropicNonstreamingIncludesBothCacheCategories() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(completeBody(ModelApiType.ANTHROPIC,
                """{"input_tokens":3,"cache_creation_input_tokens":5,"cache_read_input_tokens":7,"output_tokens":4}""")))
            assertEvents(adapter(ModelApiType.ANTHROPIC).stream(request(server, ModelApiType.ANTHROPIC, streaming = false)).toList(),
                TokenUsage(15, 4))
        }
    }

    @Test fun anthropicStreamingPreservesInputOnlyAndRejectsOutputWithoutInput() = runBlocking {
        MockWebServer().use { server ->
            for ((usage, expected) in listOf("""{"input_tokens":0}""" to TokenUsage(0), """{"output_tokens":3}""" to null)) {
                val start = completeBody(ModelApiType.ANTHROPIC, usage)
                server.enqueue(anthropicSse("""{"type":"message_start","message":$start}""", """{"type":"message_stop"}"""))
                assertEvents(adapter(ModelApiType.ANTHROPIC).stream(request(server, ModelApiType.ANTHROPIC)).toList(), expected, text = "")
            }
        }
    }

    @Test fun anthropicOverflowingCacheTotalsAreIgnored() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(completeBody(ModelApiType.ANTHROPIC,
                """{"input_tokens":9223372036854775807,"cache_read_input_tokens":1,"output_tokens":0}""")))
            assertEvents(adapter(ModelApiType.ANTHROPIC).stream(request(server, ModelApiType.ANTHROPIC, streaming = false)).toList(), null)
        }
    }

    @Test fun geminiUsesLastUsageOnlyChunkAndAddsThoughtsWithoutAddingCacheAgain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(completeBody(ModelApiType.GOOGLE, """{"promptTokenCount":10,"candidatesTokenCount":1}"""),
                """{"usageMetadata":{"promptTokenCount":10,"cachedContentTokenCount":7,"candidatesTokenCount":2,"thoughtsTokenCount":4}}"""))
            assertEvents(adapter(ModelApiType.GOOGLE).stream(request(server, ModelApiType.GOOGLE)).toList(), TokenUsage(10, 6))
        }
    }

    @Test fun geminiNonstreamingAddsThoughtsAndDoesNotCountCachedPromptTwice() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(completeBody(ModelApiType.GOOGLE,
                """{"promptTokenCount":10,"cachedContentTokenCount":7,"candidatesTokenCount":2,"thoughtsTokenCount":4}""")))
            assertEvents(adapter(ModelApiType.GOOGLE).stream(request(server, ModelApiType.GOOGLE, streaming = false)).toList(),
                TokenUsage(10, 6))
        }
    }

    @Test fun ollamaUsesFinalCountsAndSkipsMalformedUsageWithoutDroppingText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(listOf(
                """{"message":{"content":"ok"},"done":false,"prompt_eval_count":10,"eval_count":1}""",
                """{"message":{"content":""},"done":true,"prompt_eval_count":10,"eval_count":3}""",
                """{"message":{"content":""},"prompt_eval_count":10,"eval_count":"bad"}"""
            ).joinToString("\n", postfix = "\n")))
            assertEvents(adapter(ModelApiType.OLLAMA).stream(request(server, ModelApiType.OLLAMA)).toList(), TokenUsage(10, 3))
        }
    }

    @Test fun ollamaNonstreamingReadsPrettyPrintedJsonAndInputOnlyUsage() = runBlocking {
        MockWebServer().use { server ->
            val body = prettyJson.encodeToString(JsonObject.serializer(),
                Json.parseToJsonElement(completeBody(ModelApiType.OLLAMA, "\"prompt_eval_count\":9")).jsonObject)
            server.enqueue(json(body))
            assertEvents(adapter(ModelApiType.OLLAMA).stream(request(server, ModelApiType.OLLAMA, streaming = false)).toList(), TokenUsage(9))
        }
    }

    @Test fun ollamaMalformedNonstreamingUsageKeepsContent() = runBlocking {
        MockWebServer().use { server ->
            for ((input, output) in listOf("\"bad\"" to "3", "10" to "\"bad\"", "10" to "1.5")) {
                server.enqueue(json(completeBody(ModelApiType.OLLAMA, usageJson(ModelApiType.OLLAMA, input, output))))
                assertEvents(adapter(ModelApiType.OLLAMA).stream(request(server, ModelApiType.OLLAMA, streaming = false)).toList(), null)
            }
        }
    }
}
