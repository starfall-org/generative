package org.starfall.generative


import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Before
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
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.database.preferences.*
import java.nio.file.Files
import java.util.Base64
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class MediaToolChainingTest {
    @Suppress("DEPRECATION") // Robolectric 4.17 only exposes MIME registration through this legacy shadow.
    @Before fun mimeTypes() {
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowMimeTypeMap>(android.webkit.MimeTypeMap.getSingleton())
            .addExtensionMimeTypeMapping("png", "image/png")
    }
    @Test fun uploadedImageBecomesReusableReferenceAndVideoAppearsInChat() = scenario(true)
    @Test fun generatedImageResultHidesFileReferencesFromModel() = scenario(false)

    @Test fun modelCanEditUserAttachmentWithDefaultImageTool() = runBlocking {
        kotlinx.coroutines.withTimeout(30000) {
            val root = Files.createTempDirectory("image-edit-loop").toFile()
            val attachment = Files.createTempFile("user-image", ".png").toFile().apply { writeBytes(referencePng) }
            val server = MockWebServer().apply { start() }
            var rounds = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    fun json(value: JsonElement) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
                    return when (request.path) {
                        "/v1/images/edits" -> {
                            val multipart = String(request.body.readByteArray(), Charsets.ISO_8859_1)
                            assertTrue(multipart.contains("name=\"image[]\""))
                            assertTrue(multipart.contains(String(referencePng, Charsets.ISO_8859_1)))
                            json(obj("data" to JsonArray(listOf(obj("b64_json" to str(Base64.getEncoder().encodeToString(referencePng)))))))
                        }
                        "/v1/chat/completions" -> {
                            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            assertFalse(body.toString().contains(attachment.absolutePath))
                            rounds++
                            val message = if (rounds == 1) {
                                val text = body["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray.first().jsonObject.text("text")
                                val uri = Regex("tool-file:[A-Za-z0-9._-]+").find(text)!!.value
                                val args = obj("prompt" to str("edit"), "input_images" to JsonArray(listOf(str(uri))))
                                obj("role" to str("assistant"), "content" to str(""), "tool_calls" to JsonArray(listOf(
                                    obj("id" to str("edit1"), "type" to str("function"), "function" to obj(
                                        "name" to str("generate_image"), "arguments" to str(args.toString()))))))
                            } else {
                                val result = Json.parseToJsonElement(body["messages"]!!.jsonArray.last().jsonObject.text("content")).jsonObject
                                assertFalse(result.toString().contains("tool-file:"))
                                obj("role" to str("assistant"), "content" to str("Edited image ready."))
                            }
                            json(obj("choices" to JsonArray(listOf(obj("message" to message)))))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            try {
                val provider = ModelProvider("p", "local", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString(),
                    config = ProviderConfiguration(supportStream = false, modelIds = listOf("chat", "image"), modelConfigs = mapOf(
                        "chat" to ModelConfiguration(supportsToolCalls = true), "image" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION))))
                val http = ToolHttp(ToolFiles(root))
                val events = ToolChatViewModel(http, McpService(http), AiService(ApplicationProvider.getApplicationContext<Context>())).generate(
                    provider, "chat", listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Edit this image", files = listOf(attachment.absolutePath))))),
                    "", emptyList(), listOf(provider), { ToolSettings(system = mapOf("generate_image" to SystemToolConfig(true, "p", "image"))) }).toList()
                assertEquals(2, rounds)
                val activity = events.filterIsInstance<GenerationEvent.Tool>().last().activity
                assertEquals("success", activity.status)
                assertTrue(activity.files.any { it.endsWith(".png") })
                assertFalse(events.filterIsInstance<GenerationEvent.Tool>().any { it.activity.name == "send_file" })
            } finally { server.shutdown(); root.deleteRecursively(); attachment.delete() }
        }
    }

    private fun scenario(uploaded: Boolean) = runBlocking {
        kotlinx.coroutines.withTimeout(30000) {
            val root = Files.createTempDirectory("media-chain").toFile()
            val attachment = Files.createTempFile("private-attachment", ".png").toFile().apply { writeBytes(referencePng) }
            val server = MockWebServer().apply { start() }
            val files = ToolFiles(root)
            var rounds = 0
            var inputUri = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    fun json(value: JsonElement) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
                    return when (request.path) {
                        "/v1/images/generations" -> json(obj("data" to JsonArray(listOf(obj("b64_json" to str(Base64.getEncoder().encodeToString(referencePng)))))))
                        "/v1/videos" -> {
                            val multipart = String(request.body.readByteArray(), Charsets.ISO_8859_1)
                            assertTrue(multipart.contains("name=\"input_reference\""))
                            assertTrue(multipart.contains(String(referencePng, Charsets.ISO_8859_1)))
                            assertFalse(multipart.contains(attachment.absolutePath))
                            json(obj("id" to str("v1"), "status" to str("completed")))
                        }
                        "/v1/videos/v1/content" -> MockResponse().setHeader("Content-Type", "video/mp4").setBody(
                            okio.Buffer().write(ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) }))
                        "/v1/chat/completions" -> {
                            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            assertFalse(body.toString().contains(attachment.absolutePath))
                            assertFalse(body.toString().contains(root.absolutePath))
                            assertFalse(body["tools"]!!.jsonArray.any { it.jsonObject["function"]!!.jsonObject.text("name") == "send_file" })
                            rounds++
                            val messages = body["messages"]!!.jsonArray
                            val shouldCreateImage = !uploaded && rounds == 1
                            val shouldCreateVideo = uploaded && rounds == 1
                            if (!uploaded && rounds == 2) assertFalse(messages.last().jsonObject.text("content").contains("tool-file:"))
                            val message = if (shouldCreateImage || shouldCreateVideo) {
                                if (shouldCreateVideo) {
                                    inputUri = if (uploaded) {
                                        val text = messages.first().jsonObject["content"]!!.jsonArray.first().jsonObject.text("text")
                                        Regex("tool-file:[A-Za-z0-9._-]+").find(text)!!.value
                                    } else {
                                        Json.parseToJsonElement(messages.last().jsonObject.text("content")).jsonObject["files"]!!.jsonArray.first().jsonPrimitive.content
                                    }
                                    assertArrayEquals(referencePng, resolveInputImage(inputUri, files).file.readBytes())
                                }
                                val args = buildJsonObject { put("prompt", "animate"); if (shouldCreateVideo) put("input_image", inputUri) }
                                obj("role" to str("assistant"), "content" to str(""), "tool_calls" to JsonArray(listOf(
                                    obj("id" to str("call$rounds"), "type" to str("function"), "function" to obj(
                                        "name" to str(if (shouldCreateImage) "generate_image" else "generate_video"), "arguments" to str(args.toString()))))))
                            } else obj("role" to str("assistant"), "content" to str(if (uploaded) "Video ready." else "Image ready."))
                            json(obj("choices" to JsonArray(listOf(obj("message" to message)))))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            try {
                val provider = ModelProvider("p", "local", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString(),
                    config = ProviderConfiguration(supportStream = false, modelIds = listOf("chat", "image", "video"),
                        modelConfigs = mapOf("chat" to ModelConfiguration(supportsToolCalls = true),
                            "image" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION),
                            "video" to ModelConfiguration(modelType = ModelType.VIDEO_GENERATION))))
                val http = ToolHttp(files)
                val events = ToolChatViewModel(http, McpService(http), AiService(ApplicationProvider.getApplicationContext<Context>())).generate(
                    provider, "chat", listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Animate an image",
                        files = if (uploaded) listOf(attachment.absolutePath) else emptyList())))), "", emptyList(), listOf(provider),
                    { ToolSettings(system = mapOf("generate_image" to SystemToolConfig(!uploaded, "p", "image"),
                        "generate_video" to SystemToolConfig(true, "p", "video"))) }).toList()
                assertEquals(2, rounds)
                val mediaActivity = events.filterIsInstance<GenerationEvent.Tool>().last { it.activity.name == if (uploaded) "generate_video" else "generate_image" }.activity
                assertEquals("success", mediaActivity.status)
                assertTrue(mediaActivity.files.any { it.endsWith(if (uploaded) ".mp4" else ".png") })
                assertFalse(events.filterIsInstance<GenerationEvent.Tool>().any { it.activity.name == "send_file" })
                if (uploaded) assertNotNull(files.resolve(inputUri.removePrefix("tool-file:")))
            } finally { server.shutdown(); root.deleteRecursively(); attachment.delete() }
        }
    }
}
