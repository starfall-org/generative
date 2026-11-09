package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.httptools.*
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.model.CustomHttpTool
import org.starfall.generative.shared.model.HttpToolResponseMode
import org.starfall.generative.shared.model.HttpToolBodyFormat
import kotlinx.serialization.encodeToString

class CustomHttpToolServiceTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private val schema = obj("""{"type":"object","properties":{"q":{"type":"string"},"count":{"type":"integer","default":3},"flags":{"type":"array"}},"required":["q"]}""")

    @Test fun mapsQueryHeadersAndNestedBodyWithoutLosingJsonTypes() {
        val service = CustomHttpToolService(ToolHttp())
        val tool = CustomHttpTool(name = "search", method = "POST", url = "https://example.com/search?fixed=1", parameters = schema,
            query = obj("""{"q":"{{q}}","tag":["a","b"]}"""), headers = obj("""{"X-Search":"prefix {{q}}"}"""),
            body = """{"nested":{"query":"{{q}}","limit":"{{count}}","flags":"{{flags}}"}}""")
        val request = service.request(tool, obj("""{"q":"a & b","flags":[true,false]}"""))
        assertEquals("a & b", request.url.queryParameter("q"))
        assertEquals(listOf("a", "b"), request.url.queryParameterValues("tag"))
        assertEquals("1", request.url.queryParameter("fixed"))
        assertEquals("prefix a & b", request.header("X-Search"))
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertEquals(obj("""{"nested":{"query":"a & b","limit":3,"flags":[true,false]}}"""), obj(buffer.readUtf8()))
    }

    @Test fun omitsUnprovidedOptionalMappingsAndEscapesQuotesInBody() {
        val service = CustomHttpToolService(ToolHttp())
        val tool = CustomHttpTool(name = "search", url = "https://example.com", method = "POST", parameters = schema,
            query = obj("""{"q":"{{q}}","flags":"{{flags}}"}"""),
            headers = obj("""{"X-Flags":"{{flags}}"}"""),
            body = """{"q":"{{q}}","flags":"{{flags}}"}""")
        val request = service.request(tool, buildJsonObject { put("q", "say \"hello\"") })
        assertNull(request.header("X-Flags"))
        assertFalse("flags" in request.url.queryParameterNames)
        assertEquals("*/*", request.header("Accept"))
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertEquals(buildJsonObject { put("q", "say \"hello\"") }, obj(buffer.readUtf8()))
    }

    @Test fun mapsEscapedJsonPointersAndNestedOutputShapes() {
        val source = obj("""{"data":[{"a/b":{"~value":42}}],"ok":true}""")
        val shape = obj("""{"answer":"/data/0/a~1b/~0value","nested":{"all":""},"values":["/ok"]}""")
        val mapped = structureHttpResponse(source, shape).jsonObject
        assertEquals(JsonPrimitive(42), mapped["answer"])
        assertEquals(source, mapped["nested"]!!.jsonObject["all"])
        assertEquals(JsonArray(listOf(JsonPrimitive(true))), mapped["values"])
        assertTrue(runCatching { responsePointer(source, "/missing") }.isFailure)
    }

    @Test fun rejectsMissingParametersAndHeaderInjectionBeforeSending() {
        val service = CustomHttpToolService(ToolHttp())
        val tool = CustomHttpTool(name = "search", url = "https://example.com", parameters = schema,
            headers = obj("""{"X-Test":"{{q}}"}"""))
        assertTrue(runCatching { service.request(tool, obj("{}")) }.isFailure)
        assertTrue(runCatching { service.request(tool, obj("""{"q":"a\r\nX-Injected: yes"}""")) }.isFailure)
        assertTrue(runCatching { tool.copy(body = "{}").validate() }.isFailure)
        assertTrue(runCatching { tool.copy(responseMapping = obj("""{"value":"not-a-pointer"}""")).validate() }.isFailure)
    }

    @Test fun handlesAutoJsonRawAndHttpErrorsAndDisabledTools() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val service = CustomHttpToolService(ToolHttp())
            val tool = CustomHttpTool(name = "fetch", enabled = true, url = server.url("/data").toString())
            server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""{"items":[1,2]}"""))
            val mapped = service.execute(tool.copy(responseMapping = obj("""{"list":"/items"}""")), obj("{}"))
            assertEquals(obj("""{"list":[1,2]}"""), mapped["result"])
            server.enqueue(MockResponse().setBody("plain text"))
            assertEquals(JsonPrimitive("plain text"), service.execute(tool.copy(responseMode = HttpToolResponseMode.RAW), obj("{}"))["result"])
            server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("[1,true,null]"))
            assertEquals(Json.parseToJsonElement("[1,true,null]"), service.execute(tool, obj("{}"))["result"])
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
            assertTrue(runCatching { service.execute(tool, obj("{}")) }.isFailure)
            assertTrue(runCatching { service.execute(tool.copy(enabled = false), obj("{}")) }.isFailure)
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun savesBinaryMediaAndRetainsFilesWhenMappingOmitsUri() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("http-media").toFile()
        try {
            MockWebServer().use { server ->
                server.start()
                val files = org.starfall.generative.shared.subfeatures.media.logic.ToolFiles(root)
                val service = CustomHttpToolService(ToolHttp(files))
                val bytes = "RIFF0000WAVE".toByteArray() + ByteArray(40)
                server.enqueue(MockResponse().addHeader("Content-Type", "audio/wav").setBody(okio.Buffer().write(bytes)))
                val tool = CustomHttpTool(name = "audio", url = server.url("/audio").toString(), enabled = true,
                    responseMapping = obj("""{"type":"/mimeType"}"""))
                val result = service.execute(tool, obj("{}"))
                assertEquals(JsonPrimitive("audio/wav"), result["result"]!!.jsonObject["type"])
                val reference = result["files"]!!.jsonArray.single().jsonPrimitive.content.removePrefix("tool-file:")
                assertArrayEquals(bytes, files.resolve(reference)!!.readBytes())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun usesOneTemplatePerRequestSectionWithTypedNestedValues() {
        val service = CustomHttpToolService(ToolHttp())
        val tool = CustomHttpTool(name = "custom", method = "POST", url = "https://example.test?search=old", parameters =
            obj("""{"type":"object","properties":{"customer-id":{"type":"string"},"filters":{"type":"object"},"enabled":{"type":"boolean","default":false}}}"""),
            query = obj("""{"search":"{{customer-id}}"}"""),
            headers = obj("""{"X-Customer":"{{customer-id}}"}"""),
            body = """{"keep":123,"request":{"filters":"{{filters}}"},"items":[{"enabled":"{{enabled}}"}]}""")
        val request = service.request(tool, obj("""{"customer-id":"a & b","filters":{"limit":10,"tags":["a","b"]}}"""))
        assertEquals(listOf("a & b"), request.url.queryParameterValues("search"))
        assertEquals("a & b", request.header("X-Customer"))
        assertEquals(1, request.headers.values("X-Customer").size)
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertEquals(obj("""{"keep":123,"request":{"filters":{"limit":10,"tags":["a","b"]}},"items":[{"enabled":false}]}"""), obj(buffer.readUtf8()))
        assertEquals(tool, Json.decodeFromString<CustomHttpTool>(Json.encodeToString(tool)))
    }

    @Test fun validatesCustomTypesEnumsAndTemplateReferencesBeforeSending() {
        val service = CustomHttpToolService(ToolHttp())
        val tool = CustomHttpTool(name = "custom", url = "https://example.test", parameters =
            obj("""{"type":"object","properties":{"mode":{"type":"string","enum":["fast","slow"]}},"required":["mode"]}"""),
            query = obj("""{"strategy":"{{mode}}"}"""))
        assertTrue(runCatching { service.request(tool, obj("""{"mode":"invalid"}""")) }.isFailure)
        assertTrue(runCatching { service.request(tool, obj("""{"mode":123}""")) }.isFailure)
        assertTrue(runCatching { tool.copy(query = obj("""{"q":"{{missing}}"}""")).validate() }.isFailure)
        assertTrue(runCatching { tool.copy(body = """{"x":"{{mode}}"}""").validate() }.isFailure)
    }

    @Test fun supportsWholeBodyArraysAndOptionalTemplates() {
        val service = CustomHttpToolService(ToolHttp())
        val tool = CustomHttpTool(name = "custom", method = "POST", url = "https://example.test", parameters =
            obj("""{"type":"object","properties":{"values":{"type":"array"},"optional":{"type":"string"}}}"""),
            body = "\"{{values}}\"", headers = obj("""{"X-Optional":"{{optional}}"}"""))
        val request = service.request(tool, obj("""{"values":[1,true,{"ok":2}]}"""))
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertEquals(Json.parseToJsonElement("""[1,true,{"ok":2}]"""), Json.parseToJsonElement(buffer.readUtf8()))
        assertNull(request.header("X-Optional"))
    }

    @Test fun rejectsMalformedJsonBodiesAndDuplicateHeadersButAllowsExplicitTextBodies() {
        val tool = CustomHttpTool(name = "custom", method = "POST", url = "https://example.test", parameters = schema,
            body = "not json {{q}}")
        assertTrue(runCatching { tool.validate() }.isFailure)
        val request = buildHttpToolRequest(tool.copy(bodyFormat = HttpToolBodyFormat.TEXT), obj("""{"q":"hello"}"""))
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertEquals("not json hello", buffer.readUtf8())
        assertEquals("text/plain; charset=utf-8", request.body!!.contentType().toString())
        assertTrue(runCatching { tool.copy(body = "{}", headers = obj("""{"X-Test":"a","x-test":"b"}""")).validate() }.isFailure)
    }

}
