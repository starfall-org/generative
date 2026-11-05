package org.starfall.generative

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.*
import org.starfall.generative.integrations.media.*
import org.starfall.generative.shared.helpers.media.*
import org.starfall.generative.shared.subfeatures.media.logic.*
import org.starfall.generative.shared.model.media.*
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.database.preferences.*
import java.nio.file.Files
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider

class ImageToolOptionsTest {
    @Test fun settingsSurviveSerializationAndRemainSeparateForEachModel() {
        val legacy = Json.decodeFromString<SystemToolConfig>("""{"enabled":true,"providerId":"p","modelId":"a"}""")
        assertTrue(legacy.imageOptions.isEmpty())
        val a = legacy.withImageOptions(obj("quality" to str("high")))
        val b = a.copy(modelId = "b").withImageOptions(obj("size" to str("512x512")))
        val restored = Json.decodeFromString<SystemToolConfig>(Json.encodeToString(b))
        assertEquals("high", restored.copy(modelId = "a").imageOptions.text("quality"))
        assertEquals("512x512", restored.imageOptions.text("size"))
        assertTrue(restored.copy(providerId = "other").imageOptions.isEmpty())
    }

    @Test fun googleOptionsMergeWithoutLosingPromptOrImageModality() {
        val options = Json.parseToJsonElement("""{"generationConfig":{"imageConfig":{"aspectRatio":"16:9","imageSize":"2K"},"temperature":0.8},"safetySettings":[]}""").jsonObject
        val request = imageGenerationRequest(ModelApiType.GOOGLE, "gemini-image", "landscape", options)
        assertEquals("16:9", request.optionAt("generationConfig.imageConfig.aspectRatio")!!.jsonPrimitive.content)
        assertTrue(request.optionAt("generationConfig.responseModalities")!!.jsonArray.contains(str("IMAGE")))
        assertTrue(request["contents"].toString().contains("landscape"))
        assertEquals(JsonArray(emptyList()), request["safetySettings"])
        val imagen = imageGenerationRequest(ModelApiType.GOOGLE, "imagen-4", "tree",
            Json.parseToJsonElement("""{"parameters":{"sampleCount":4,"aspectRatio":"3:4","outputOptions":{"mimeType":"image/jpeg"}}}""").jsonObject)
        assertEquals(4, imagen.optionAt("parameters.sampleCount")!!.jsonPrimitive.int)
        assertEquals("image/jpeg", imagen.optionAt("parameters.outputOptions.mimeType")!!.jsonPrimitive.content)
        assertTrue(imagen["instances"].toString().contains("tree"))
    }

    @Test fun invalidSettingsAreRejectedBeforeNetworkRequest() {
        listOf("""{"n":11}""", """{"n":"2"}""", """{"output_compression":101}""",
            """{"background":"transparent","output_format":"jpeg"}""", """{"partial_images":2}""",
            """{"model":"unexpected"}""").forEach { json ->
            assertTrue(json, runCatching { validateImageOptions(ModelApiType.OPENAI, "image", Json.parseToJsonElement(json).jsonObject) }.isFailure)
        }
    }

    @Test fun streamedBase64IsSavedAndPartialFilesAreRemoved() = runBlocking {
        val root = Files.createTempDirectory("image-stream-test").toFile()
        val server = MockWebServer()
        server.start()
        try {
            val bytes = ByteArray(60000) { 42 }.also { it[0] = 0x89.toByte(); it[1] = 0x50 }
            val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
            server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                "event: image_generation.partial_image\r\ndata: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"$encoded\"}\r\n\r\n" +
                "event: image_generation.completed\r\ndata: {\"type\":\"image_generation.completed\",\"b64_json\":\"$encoded\"}\r\n\r\n" +
                "data: [DONE]\r\n\r\n"))
            val store = ToolFiles(root)
            val provider = ModelProvider("p", "test", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString())
            val result = SystemMediaTools(ToolHttp(store)).generate("generate_image", provider, "image", "tree",
                obj("stream" to JsonPrimitive(true), "partial_images" to JsonPrimitive(1), "quality" to str("high"), "custom_option" to obj("enabled" to JsonPrimitive(true))))
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertTrue(request["stream"]!!.jsonPrimitive.boolean)
            assertEquals("high", request.text("quality"))
            assertTrue(request["custom_option"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertEquals("image", request.text("model"))
            assertEquals("tree", request.text("prompt"))
            assertFalse(result.toString().contains(encoded))
            assertEquals(1, store.list().size)
            assertArrayEquals(bytes, store.list().single().readBytes())
            assertFalse(root.listFiles()!!.any { it.extension == "part" })
        } finally { server.shutdown(); root.deleteRecursively() }
    }
    @Test fun stoppingImageStreamClosesResponseAndRemovesTemporaryFiles() = runBlocking {
        val root = Files.createTempDirectory("image-stop-test").toFile()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n").setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
            val provider = ModelProvider("p", "test", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString())
            val job = launch {
                SystemMediaTools(ToolHttp(ToolFiles(root))).generate("generate_image", provider, "image", "tree",
                    obj("stream" to JsonPrimitive(true)))
            }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)) }
            delay(100)
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { server.shutdown(); root.deleteRecursively() }
    }

}
