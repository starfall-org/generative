package org.starfall.generative

import java.nio.file.Files
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.shared.logic.ToolMessageHistory
import org.starfall.generative.integrations.modelapis.ToolJsonFileStore
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class ToolJsonFileStoreTest {
    @Test fun fragmentedArgumentsGrowStorageLinearlyAndStayInOrder() {
        val root = Files.createTempDirectory("tool-argument-chunks-").toFile()
        val store = ToolJsonFileStore(root)
        try {
            val chunks = ToolArgumentChunks(store)
            val fragment = "1234567890abcdef".repeat(2)
            repeat(1000) {
                chunks.append(4, fragment)
                chunks.append(7, "x")
            }
            assertEquals(fragment.repeat(1000), chunks.read(4))
            assertEquals("x".repeat(1000), chunks.read(7))
            assertEquals(32000, chunks.length(4))
            assertNull(chunks.read(8))
            assertTrue(root.listFiles().orEmpty().sumOf { it.length() } < 100_000L)
        } finally { store.close(); root.deleteRecursively() }
    }

    @Test fun incrementalSanitizerPreservesMediaAndText() = runBlocking {
        val root = Files.createTempDirectory("tool-json-media-").toFile()
        try {
            val files = ToolFiles(root)
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(64)
            val encoded = java.util.Base64.getEncoder().encodeToString(png)
            val source = """{"message":"Tiếng Việt","url":"data:image/png;base64,$encoded"}"""
            val previous = files.sanitize(StringReader(source))
            val output = StringBuilder()
            files.sanitize(StringReader(source), output)
            assertEquals(Json.parseToJsonElement(previous), Json.parseToJsonElement(output.toString()))
            val result = Json.parseToJsonElement(output.toString()).jsonObject
            assertEquals("Tiếng Việt", result.text("message"))
            assertTrue(result.text("url").startsWith("tool-file:"))
        } finally { root.deleteRecursively() }
    }

    @Test fun normalizedCallsRemainAvailableForAllSupportedWireProtocols() {
        val root = Files.createTempDirectory("tool-protocols-").toFile()
        val store = ToolJsonFileStore(root)
        try {
            val entries = (0 until 24).toList()
            val openAiCalls = entries.map { i -> obj("id" to str("id-$i"),
                "function" to obj("name" to str("lookup"), "arguments" to str("{\"value\":$i}"))) }
            val responses = mapOf(
                ModelApiType.OPENAI to obj("choices" to JsonArray(listOf(obj("message" to obj("tool_calls" to JsonArray(openAiCalls)))))),
                ModelApiType.OLLAMA to obj("message" to obj("tool_calls" to JsonArray(openAiCalls))),
                ModelApiType.ANTHROPIC to obj("content" to JsonArray(entries.map { i -> obj(
                    "type" to str("tool_use"), "id" to str("id-$i"), "name" to str("lookup"),
                    "input" to obj("value" to JsonPrimitive(i))) })),
                ModelApiType.GOOGLE to obj("candidates" to JsonArray(listOf(obj("content" to obj("parts" to JsonArray(
                    entries.map { i -> obj("thoughtSignature" to str("signature-$i"), "functionCall" to obj(
                        "name" to str("lookup"), "args" to obj("value" to JsonPrimitive(i)))) }
                )))))),
                ModelApiType.OPENAI_RESPONSES to obj("output" to JsonArray(entries.map { i -> obj(
                    "type" to str("function_call"), "call_id" to str("id-$i"), "name" to str("lookup"),
                    "arguments" to str("{\"value\":$i}")) }))
            )
            responses.forEach { (type, response) ->
                val calls = providerTurn(type, response, store).requireArray("tool_calls")
                assertEquals(type.toString(), 24, calls.size)
                assertEquals(23, calls.last().jsonObject.requireObject("function")
                    .requireObject("arguments").getValue("value").jsonPrimitive.int)
                if (type == ModelApiType.GOOGLE) {
                    assertEquals("signature-23", calls.last().jsonObject.requireObject("googlePart").text("thoughtSignature"))
                }
            }
        } finally { store.close(); root.deleteRecursively() }
    }

    @Test fun entriesAndRequestBodyAreReadOnDemandAndCleanedUp() {
        val root = Files.createTempDirectory("tool-json-store-").toFile()
        try {
            val store = ToolJsonFileStore(root)
            try {
                val calls = store.list()
                val original = mutableMapOf<String, JsonElement>("id" to str("first"))
                calls.add(JsonObject(original))
                original["id"] = str("changed")
                calls.add(obj("id" to str("second"), "unicode" to str("Tiếng Việt 🌱")))
                assertEquals("first", calls[0].jsonObject.text("id"))
                val history = ToolMessageHistory(store)
                history.add(obj("role" to str("assistant"), "tool_calls" to JsonArray(calls)))
                history.add(obj("role" to str("tool"), "content" to str("result")))
                val expected = obj("messages" to JsonArray(history))
                val body = StreamingJsonBody(expected)
                val sink = Buffer()
                body.writeTo(sink)
                val bytes = sink.readByteArray()
                assertEquals(bytes.size.toLong(), body.contentLength())
                assertEquals(expected, Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)))
                assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(".spill") })
            } finally { store.close() }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun largeSingleEventAndNonStreamingResponseKeepEveryCall() = runBlocking {
        for (stream in listOf(true, false)) {
            val root = Files.createTempDirectory("tool-json-response-").toFile()
            MockWebServer().use { server ->
                val count = 1200
                val calls = (0 until count).map { index -> obj(
                    "index" to JsonPrimitive(index), "id" to str("call-$index"),
                    "function" to obj("name" to str("lookup"),
                        "arguments" to str("{\"query\":\"${"x".repeat(1000)}\"}"))
                ) }
                val payload = if (stream) {
                    "data: ${obj("choices" to JsonArray(listOf(obj("delta" to obj("tool_calls" to JsonArray(calls))))))}\n\ndata: [DONE]\n\n"
                } else {
                    obj("choices" to JsonArray(listOf(obj("message" to obj("tool_calls" to JsonArray(calls)))))).toString()
                }
                assertTrue(payload.length > 1024 * 1024)
                server.enqueue(MockResponse().setBody(payload))
                val files = ToolFiles(root)
                val store = ToolJsonFileStore(root)
                try {
                    val provider = ModelProvider("p", "Provider", ModelApiType.OPENAI, baseUrl = server.url("/").toString())
                    val response = ToolHttp(files).modelResponse(server.url("/chat").toString(), obj(), provider,
                        stream, {}, {}, store = store)
                    val turn = providerTurn(ModelApiType.OPENAI, response, store)
                    val loaded = turn.requireArray("tool_calls")
                    assertEquals(count, loaded.size)
                    assertEquals("call-0", loaded.first().jsonObject.text("id"))
                    assertEquals("call-${count - 1}", loaded.last().jsonObject.text("id"))
                    assertEquals("x".repeat(1000), loaded.last().jsonObject.requireObject("function")
                        .requireObject("arguments").text("query"))
                } finally { store.close() }
                assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".spill") })
            }
            root.deleteRecursively()
        }
    }
}
