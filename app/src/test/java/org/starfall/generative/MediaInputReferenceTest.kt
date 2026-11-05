package org.starfall.generative


import kotlinx.coroutines.runBlocking
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
import java.util.Base64
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.features.home.shared.logic.prepareToolResult

internal val referencePng: ByteArray get() = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=")

class MediaInputReferenceTest {
    @Test fun videoSchemaAllowsOptionalImageAndRejectsMalformedArguments() {
        val schema = mediaToolSchema("generate_video")
        assertEquals(JsonArray(listOf(str("prompt"))), schema["required"])
        assertEquals("string", schema["properties"]!!.jsonObject["input_image"]!!.jsonObject.text("type"))
        assertFalse(mediaToolSchema("generate_image")["properties"]!!.jsonObject.containsKey("input_image"))
        assertEquals("array", mediaToolSchema("generate_image")["properties"]!!.jsonObject["input_images"]!!.jsonObject.text("type"))
        assertEquals(listOf("tool-file:a.png"), mediaInputImagesArgument(obj("input_images" to JsonArray(listOf(str("tool-file:a.png"))))))
        listOf(str("tool-file:a.png"), JsonArray(emptyList()), JsonArray(listOf(JsonPrimitive(7)))).forEach {
            assertTrue(runCatching { mediaInputImagesArgument(obj("input_images" to it)) }.isFailure)
        }
        assertNull(mediaInputImageArgument(obj()))
        listOf(JsonNull, JsonPrimitive(7), str(""), obj()).forEach {
            assertTrue(runCatching { mediaInputImageArgument(obj("input_image" to it)) }.isFailure)
        }
    }

    @Test fun referencesValidateSchemeContentsSizeAndStorageBoundary() = runBlocking {
        val root = Files.createTempDirectory("media-ref").toFile()
        val outside = Files.createTempFile("outside", ".png").toFile()
        try {
            val files = ToolFiles(root)
            val name = files.save(referencePng.inputStream(), "image/png")
            assertEquals("image/png", resolveInputImage("tool-file:$name", files).mimeType)
            val text = files.save("not an image".byteInputStream(), "image/png")
            val fake = java.io.File(root, "fake.png").apply { writeText("not a PNG") }
            val large = java.io.File(root, "large.png")
            java.io.RandomAccessFile(large, "rw").use { it.setLength(MAX_MEDIA_INPUT_BYTES + 1) }
            Files.createSymbolicLink(root.toPath().resolve("escape.png"), outside.toPath())
            listOf("/etc/passwd", "file:///etc/passwd", "/sdcard/image.png", "https://example.org/image.png",
                "tool-file:../outside.png", "tool-file:%2e%2e%2foutside.png", "tool-file:missing.png",
                "tool-file:$text", "tool-file:${fake.name}", "tool-file:large.png", "tool-file:escape.png").forEach {
                assertTrue(it, runCatching { resolveInputImage(it, files) }.isFailure)
            }
            assertNull(files.resolve("escape.png"))
            assertTrue(runCatching { files.save(referencePng.inputStream(), "image/png", 8) }.isFailure)
            assertFalse(root.listFiles()!!.any { it.extension == "part" })
        } finally { root.deleteRecursively(); outside.delete() }
    }

    @Test fun openAiAndH3UploadImageAndTextToVideoStillWorks() = runBlocking {
        val root = Files.createTempDirectory("video-request").toFile()
        val server = MockWebServer().apply { start() }
        try {
            val files = ToolFiles(root)
            val name = files.save(referencePng.inputStream(), "image/png")
            val http = ToolHttp(files)
            for (model in listOf("sora-2", "MiniMax-H3", "text-video")) {
                server.enqueue(MockResponse().setBody("""{"id":"video_1","status":"completed"}"""))
                val video = ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) }
                server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(video)))
                val result = SystemMediaTools(http).generate("generate_video",
                    ModelProvider("p", "test", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString()),
                    model, "animate", inputImage = if (model == "text-video") null else "tool-file:$name")
                val request = server.takeRequest()
                val body = request.body.readByteArray()
                val text = String(body, Charsets.ISO_8859_1)
                assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
                assertTrue(text.contains("name=\"model\"\r\n") && text.contains(model))
                assertEquals(model != "text-video", text.contains("name=\"input_reference\""))
                if (model != "text-video") {
                    assertTrue(text.contains("Content-Type: image/png"))
                    assertTrue(text.contains(String(referencePng, Charsets.ISO_8859_1)))
                }
                assertFalse(text.contains(root.absolutePath))
                assertEquals("/v1/videos/video_1/content", server.takeRequest().path)
                assertTrue(result["files"]!!.jsonArray.single().jsonPrimitive.content.startsWith("tool-file:"))
            }
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun googleUsesInlineImageAndUnsupportedProviderFailsBeforeNetwork() = runBlocking {
        val root = Files.createTempDirectory("google-video").toFile()
        val server = MockWebServer().apply { start() }
        try {
            val files = ToolFiles(root)
            val name = files.save(referencePng.inputStream())
            val image = resolveInputImage("tool-file:$name", files)
            val request = googleVideoRequest("animate", image)
            val inline = request["instances"]!!.jsonArray.single().jsonObject["image"]!!.jsonObject["inlineData"]!!.jsonObject
            assertEquals("image/png", inline.text("mimeType"))
            assertArrayEquals(referencePng, Base64.getDecoder().decode(inline.text("data")))
            assertFalse(googleVideoRequest("animate")["instances"]!!.jsonArray.single().jsonObject.containsKey("image"))
            server.enqueue(MockResponse().setBody(obj("name" to str("operations/video_1"), "done" to JsonPrimitive(true),
                "response" to obj("video" to obj("uri" to str(server.url("/video").toString())))).toString()))
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(
                ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) })))
            val result = SystemMediaTools(ToolHttp(files)).generate("generate_video",
                ModelProvider("p", "test", ModelApiType.GOOGLE, baseUrl = server.url("/v1beta").toString()),
                "veo-3.1", "animate", inputImage = "tool-file:$name")
            val sent = server.takeRequest()
            assertEquals("/v1beta/models/veo-3.1:predictLongRunning", sent.path)
            assertEquals(request, Json.parseToJsonElement(sent.body.readUtf8()))
            assertEquals("/video", server.takeRequest().path)
            assertTrue(result["files"]!!.jsonArray.isNotEmpty())
            val error = runCatching {
                SystemMediaTools(ToolHttp(files)).generate("generate_video",
                    ModelProvider("p", "test", ModelApiType.ANTHROPIC, baseUrl = server.url("/").toString()),
                    "video", "animate", inputImage = "tool-file:$name")
            }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("image-to-video"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun largeToolResultsPreserveReusableMediaReferences() = runBlocking {
        val root = Files.createTempDirectory("tool-result-ref").toFile()
        try {
            val files = ToolFiles(root)
            val name = files.save(referencePng.inputStream())
            val uri = "tool-file:$name"
            val summary = prepareToolResult(obj("file" to str(uri), "detail" to str("x".repeat(20000))), files)
            assertTrue(summary.content["files"]!!.jsonArray.contains(str(uri)))
            assertArrayEquals(referencePng, resolveInputImage(uri, files).file.readBytes())
        } finally { root.deleteRecursively() }
    }
}
