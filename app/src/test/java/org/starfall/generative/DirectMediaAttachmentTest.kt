package org.starfall.generative


import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.*
import org.starfall.generative.features.mcp.shared.ui.*
import org.starfall.generative.integrations.mcp.*
import org.starfall.generative.features.machines.shared.ui.*
import org.starfall.generative.integrations.remote.*
import org.starfall.generative.features.home.chat.*
import org.starfall.generative.integrations.modelapis.*
import org.starfall.generative.shared.subfeatures.media.logic.*
import org.starfall.generative.integrations.media.*
import org.starfall.generative.shared.model.media.*
import org.starfall.generative.shared.helpers.media.optionAt
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.database.preferences.*
import java.nio.file.Files
import java.util.Base64
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class DirectMediaAttachmentTest {
    @Test fun directOpenAiImageModeUploadsMultipleAttachmentsToEditsAndKeepsOptions() = runBlocking {
        val root = Files.createTempDirectory("direct-image").toFile()
        val attachment = Files.createTempFile("user-image", ".png").toFile().apply { writeBytes(referencePng) }
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"image_edit.completed\",\"b64_json\":\"${Base64.getEncoder().encodeToString(referencePng)}\"}\n\ndata: [DONE]\n\n"))
            val provider = ModelProvider("p", "local", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString())
            val files = ToolFiles(root)
            val events = engine(files).generateMedia(provider, "gpt-image-1", ModelType.IMAGE_GENERATION, "edit",
                obj("quality" to str("high"), "stream" to JsonPrimitive(true)), listOf(attachment.absolutePath, attachment.absolutePath)).toList()
            val sent = server.takeRequest()
            assertEquals("/v1/images/edits", sent.path)
            assertTrue(sent.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            val multipart = String(sent.body.readByteArray(), Charsets.ISO_8859_1)
            assertEquals(2, Regex("name=\"image\\[\\]\"").findAll(multipart).count())
            assertTrue(multipart.contains(String(referencePng, Charsets.ISO_8859_1)))
            assertTrue(multipart.contains("high") && multipart.contains("true"))
            assertFalse(multipart.contains(attachment.absolutePath))
            val activity = events.filterIsInstance<GenerationEvent.Tool>().last().activity
            assertEquals("success", activity.status)
            assertTrue(activity.files.any { it.endsWith(".png") })
            val arguments = Json.parseToJsonElement(activity.arguments).jsonObject
            assertEquals(2, arguments["input_images"]!!.jsonArray.size)
            assertFalse(events.toString().contains(attachment.absolutePath))
        } finally { server.shutdown(); root.deleteRecursively(); attachment.delete() }
    }

    @Test fun directVideoModePassesAttachmentAsMultipartReference() = runBlocking {
        val root = Files.createTempDirectory("direct-video").toFile()
        val attachment = Files.createTempFile("user-image", ".png").toFile().apply { writeBytes(referencePng) }
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"id":"v1","status":"completed"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(
                ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) })))
            val provider = ModelProvider("p", "local", ModelApiType.OPENAI_RESPONSES, baseUrl = server.url("/v1").toString())
            val events = engine(ToolFiles(root)).generateMedia(provider, "MiniMax-H3", ModelType.VIDEO_GENERATION,
                "animate", attachments = listOf(attachment.absolutePath)).toList()
            val sent = server.takeRequest()
            assertEquals("/v1/videos", sent.path)
            val multipart = String(sent.body.readByteArray(), Charsets.ISO_8859_1)
            assertTrue(multipart.contains("name=\"input_reference\""))
            assertTrue(multipart.contains(String(referencePng, Charsets.ISO_8859_1)))
            assertEquals("/v1/videos/v1/content", server.takeRequest().path)
            val activity = events.filterIsInstance<GenerationEvent.Tool>().last().activity
            assertEquals("success", activity.status)
            assertTrue(activity.files.any { it.endsWith(".mp4") })
            assertTrue(Json.parseToJsonElement(activity.arguments).jsonObject.text("input_image").startsWith("tool-file:"))
        } finally { server.shutdown(); root.deleteRecursively(); attachment.delete() }
    }

    @Test fun directGeminiImageModeSendsNativeInlinePartsWithPromptAndOptions() = runBlocking {
        val root = Files.createTempDirectory("direct-google-image").toFile()
        val attachment = Files.createTempFile("user-image", ".png").toFile().apply { writeBytes(referencePng) }
        val server = MockWebServer().apply { start() }
        try {
            val output = referencePng + ByteArray(64)
            server.enqueue(MockResponse().setBody(obj("candidates" to JsonArray(listOf(obj("content" to obj("parts" to JsonArray(listOf(
                obj("inlineData" to obj("mimeType" to str("image/png"), "data" to str(Base64.getEncoder().encodeToString(output))))))))))).toString()))
            val provider = ModelProvider("p", "local", ModelApiType.GOOGLE, baseUrl = server.url("/v1beta").toString())
            val options = obj("generationConfig" to obj("imageConfig" to obj("aspectRatio" to str("16:9"))))
            val events = engine(ToolFiles(root)).generateMedia(provider, "gemini-image", ModelType.IMAGE_GENERATION,
                "edit", options, listOf(attachment.absolutePath)).toList()
            val sent = server.takeRequest()
            assertEquals("/v1beta/models/gemini-image:generateContent", sent.path)
            val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            val parts = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
            assertEquals("edit", parts.first().jsonObject.text("text"))
            val inline = parts.last().jsonObject["inlineData"]!!.jsonObject
            assertEquals("image/png", inline.text("mimeType"))
            assertArrayEquals(referencePng, Base64.getDecoder().decode(inline.text("data")))
            assertEquals("16:9", body.optionAt("generationConfig.imageConfig.aspectRatio")!!.jsonPrimitive.content)
            assertTrue(body.optionAt("generationConfig.responseModalities")!!.jsonArray.contains(str("IMAGE")))
            assertEquals("success", events.filterIsInstance<GenerationEvent.Tool>().last().activity.status)
        } finally { server.shutdown(); root.deleteRecursively(); attachment.delete() }
    }

    @Test fun invalidDirectAttachmentsFailBeforeNetworkWithUsefulErrors() = runBlocking {
        val root = Files.createTempDirectory("direct-invalid").toFile()
        val attachment = Files.createTempFile("user-document", ".txt").toFile().apply { writeText("not an image") }
        val server = MockWebServer().apply { start() }
        try {
            val provider = ModelProvider("p", "local", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString())
            assertTrue(runCatching { engine(ToolFiles(root)).generateMedia(provider, "image", ModelType.IMAGE_GENERATION,
                "edit", attachments = listOf(attachment.absolutePath)).toList() }.exceptionOrNull()!!.message!!.contains("PNG"))
            assertTrue(runCatching { engine(ToolFiles(root)).generateMedia(provider, "video", ModelType.VIDEO_GENERATION,
                "animate", attachments = listOf(attachment.absolutePath, attachment.absolutePath)).toList() }.exceptionOrNull()!!.message!!.contains("one reference image"))
            assertEquals(0, server.requestCount)
        } finally { server.shutdown(); root.deleteRecursively(); attachment.delete() }
    }

    private fun engine(files: ToolFiles): ToolChatViewModel {
        val http = ToolHttp(files)
        return ToolChatViewModel(http, McpService(http), AiService(ApplicationProvider.getApplicationContext<Context>()))
    }
}
