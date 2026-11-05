package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.isContentApiPreset
import org.starfall.generative.features.home.chat.logic.tool.resolveContentApiMedia
import org.starfall.generative.shared.helpers.media.*
import org.starfall.generative.shared.subfeatures.media.logic.*
import org.starfall.generative.shared.model.media.*
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.database.preferences.*
import java.nio.file.Files

class ContentApiMediaTest {
    private fun payload(url: String) = obj(
        "success" to JsonPrimitive(true), "platform" to str("douyin"),
        "original_url" to str("https://v.douyin.com/NFUk7zqRj0w/"),
        "title" to str("风中已经有了国庆的味道 #国庆#红旗"),
        "media" to JsonArray(listOf(obj("url" to str(url), "type" to str("video")))),
        "metadata" to obj("extractor" to str("tikvideo"))
    )

    @Test fun directAndWrappedResultsHideMediaUrlsButKeepOriginalPageAndMetadata() {
        val url = "https://cdn.example.test/clip/?token=signed%3D&mime_type=video_mp4"
        val direct = payload(url)
        val result = obj("structuredContent" to direct,
            "content" to JsonArray(listOf(obj("type" to str("text"), "text" to str(direct.toString())))),
            "duplicate" to str("Video link: $url"))
        val prepared = prepareContentApiResult(result)
        assertEquals(listOf(ContentApiMedia(url, "video")), prepared.media)
        assertFalse(prepared.content.toString().contains(url))
        assertFalse(prepared.content.toString().contains("cdn.example.test"))
        assertTrue(prepared.content.toString().contains("v.douyin.com"))
        assertEquals(direct["title"], prepared.content["structuredContent"]!!.jsonObject["title"])
        val text = prepared.content["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("tikvideo", Json.parseToJsonElement(text).jsonObject["metadata"]!!.jsonObject["extractor"]!!.jsonPrimitive.content)
    }

    @Test fun thumbnailsAndUnsupportedMediaLinksAreAlsoHidden() {
        val result = obj("thumbnail_url" to str("https://cdn.test/thumb.jpg"), "media" to JsonArray(listOf(
            obj("url" to str("file:///private/file.mp4"), "type" to str("video")),
            obj("url" to str("https://cdn.test/other"), "type" to str("unknown"))
        )))
        val prepared = prepareContentApiResult(result)
        assertTrue(prepared.media.isEmpty())
        assertFalse(prepared.content.toString().contains("cdn.test"))
        assertFalse(prepared.content.toString().contains("file:///"))
    }

    @Test fun downloadFailuresKeepMaskedExtractionMetadataAndOtherMedia() = runBlocking {
        val server = MockWebServer()
        server.start()
        val root = Files.createTempDirectory("content-api-media").toFile()
        try {
            val expired = server.url("/expired").toString()
            val audio = server.url("/audio").toString()
            val wav = ByteArray(48).also {
                "RIFF".toByteArray().copyInto(it, 0); "WAVE".toByteArray().copyInto(it, 8)
            }
            server.enqueue(MockResponse().setResponseCode(403).setBody("Expired signed URL $expired"))
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(okio.Buffer().write(wav)))
            val result = JsonObject(payload(expired) + ("media" to JsonArray(listOf(
                obj("url" to str(expired), "type" to str("video")),
                obj("url" to str(audio), "type" to str("audio"))
            ))))
            val store = ToolFiles(root)
            val prepared = resolveContentApiMedia(result, ToolHttp(store))
            assertEquals(1, prepared.files.size)
            assertTrue(prepared.files.single().endsWith(".wav"))
            assertArrayEquals(wav, store.resolve(prepared.files.single())!!.readBytes())
            assertFalse(prepared.content.toString().contains(expired))
            assertFalse(prepared.content.toString().contains(audio))
            val media = prepared.content["app_media"]!!.jsonArray
            assertFalse(media[0].jsonObject["displayed_in_chat"]!!.jsonPrimitive.boolean)
            assertTrue(media[1].jsonObject["displayed_in_chat"]!!.jsonPrimitive.boolean)
            assertTrue(media[1].jsonObject["available"]!!.jsonPrimitive.boolean)
            assertFalse(media[1].jsonObject.containsKey("uri"))
            assertFalse(prepared.content.toString().contains(prepared.files.single()))
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun onlyContentApiPresetGetsSpecialHandling() {
        assertTrue(McpInfo("content-api", "Renamed preset").isContentApiPreset())
        assertTrue(McpInfo("custom", "CONTENT-API").isContentApiPreset())
        assertFalse(McpInfo("custom", "Other MCP").isContentApiPreset())
    }
}
