package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider

class VideoProtocolTest {
    @Test fun videoProtocolUsesTheGenericOpenAiCompatibleTransport() {
        val proxy = ModelProvider("p", "proxy", ModelApiType.OPENAI, baseUrl = "https://proxy.example/v1")
        assertEquals(VideoApi.OPENAI, videoApi(proxy))
        assertEquals(VideoApi.OPENAI, videoApi(proxy.copy(baseUrl = "https://h3video.cc.cd/v1")))
        assertEquals(VideoApi.OPENAI, videoApi(proxy.copy(baseUrl = "https://video.example/v1")))
    }

    private fun provider(server: MockWebServer, path: String = "/v1") = ModelProvider(
        "p", "video", ModelApiType.OPENAI, baseUrl = server.url(path).toString(),
        auth = Authorization(value = "test-secret")
    )
    private fun response(body: String) = MockResponse().setBody(body)
    private fun video() = MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(
        ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) }))

    @Test fun modelNamesUseGenericProtocolAndOnlySupportedVideoSettings() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("generic-video-models").toFile()
        try {
            val generation = VideoGeneration(ToolHttp(ToolFiles(root)), 0)
            for (model in listOf("video-6s", "video-10s", "video-15s", "custom-video")) {
                assertEquals(listOf("seconds", "size"), videoOptionFields(provider(server), model).map { it.path })
                assertTrue(runCatching { validateVideoOptions(provider(server), model, obj("cf_token" to str("token"))) }.isFailure)
                assertTrue(runCatching { validateVideoOptions(provider(server), model, obj("image_url" to str("https://example.com/image.png"))) }.isFailure)
                server.enqueue(response("""{"id":"job","status":"completed"}"""))
                server.enqueue(video())
                generation.generate(provider(server, "/"), model, "move", null, obj(), null)
                val request = server.takeRequest()
                assertEquals("/videos", request.path)
                assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
                val body = request.body.readUtf8()
                assertTrue(body.contains(model))
                assertFalse(body.contains("aspect_ratio"))
                assertFalse(body.contains("seconds"))
                assertEquals("/videos/job/content", server.takeRequest().path)
            }
            server.enqueue(response("""{"id":"job","status":"completed"}"""))
            server.enqueue(video())
            generation.generate(provider(server, "/"), "sora-2", "move", null,
                obj("seconds" to str("8"), "size" to str("1280x720")), null)
            val configured = server.takeRequest().body.readUtf8()
            assertTrue(configured.contains("name=\"seconds\""))
            assertTrue(configured.contains("1280x720"))
            assertEquals("/videos/job/content", server.takeRequest().path)
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun veoSettingsAreValidatedAndWrittenIntoRequestParameters() {
        val provider = ModelProvider("g", "google", ModelApiType.GOOGLE, baseUrl = "https://generativelanguage.googleapis.com/v1beta")
        val options = obj("parameters" to obj(
            "durationSeconds" to JsonPrimitive(8), "aspectRatio" to str("9:16"), "resolution" to str("1080p")
        ))
        validateVideoOptions(provider, "veo-3.1-generate-preview", options)
        val request = googleVideoRequest("sunrise", options = options)
        assertEquals("9:16", request.optionAt("parameters.aspectRatio")?.jsonPrimitive?.content)
        assertEquals(8, request.optionAt("parameters.durationSeconds")?.jsonPrimitive?.int)
        assertTrue(runCatching { validateVideoOptions(provider, "veo-3.1-generate-preview",
            obj("parameters" to obj("durationSeconds" to JsonPrimitive(5)))) }.isFailure)
        assertTrue(runCatching { validateVideoOptions(provider, "veo-3.1-generate-preview",
            obj("parameters" to obj("resolution" to str("4k")))) }.isFailure)
    }

    @Test fun failedCancelledMissingIdsAndTimeoutNeverDownloadContent() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("video-errors").toFile()
        try {
            val generation = VideoGeneration(ToolHttp(ToolFiles(root)), 0, 100)
            for (body in listOf("""{"id":"job","status":"failed"}""", """{"id":"job","status":"cancelled"}""",
                """{"id":"../escape","status":"completed"}""", """{"status":"queued"}""")) {
                server.enqueue(response(body))
                assertTrue(runCatching { generation.generate(provider(server), "sora-2", "move", null, obj(), null) }.isFailure)
                assertEquals("/v1/videos", server.takeRequest().path)
            }
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun optionsRoundTripAndRemainSeparatePerModelWithoutBreakingOldSettings() {
        val old = Json.decodeFromString<SystemToolConfig>("""{"providerId":"p","modelId":"video-model","imageOptionsByModel":{}}""")
        assertEquals(obj(), old.videoOptions)
        val saved = old.withVideoOptions(obj("quality" to str("high")))
            .copy(modelId = "video-model-2").withVideoOptions(obj("duration" to JsonPrimitive(8)))
        val restored = Json.decodeFromString<SystemToolConfig>(Json.encodeToString(SystemToolConfig.serializer(), saved))
        assertEquals(8, restored.videoOptions["duration"]!!.jsonPrimitive.int)
        assertEquals("high", restored.copy(modelId = "video-model").videoOptions.text("quality"))
        assertEquals(obj(), restored.copy(providerId = "other").videoOptions)
    }

    @Test fun genericVideoGatewaysCanStillReturnNestedMediaWithoutJobId() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("generic-video").toFile()
        try {
            server.enqueue(response(obj("status" to str("completed"), "data" to JsonArray(listOf(obj("url" to str(server.url("/video.mp4").toString()))))).toString()))
            server.enqueue(video())
            val result = SystemMediaTools(ToolHttp(ToolFiles(root))).generate("generate_video", provider(server), "generic-video", "move")
            assertEquals("/v1/videos", server.takeRequest().path)
            assertEquals("/video.mp4", server.takeRequest().path)
            assertTrue(result["files"]!!.jsonArray.single().jsonPrimitive.content.endsWith(".mp4"))
        } finally { server.shutdown(); root.deleteRecursively() }
    }
}
