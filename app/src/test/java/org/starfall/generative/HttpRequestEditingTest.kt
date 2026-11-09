package org.starfall.generative

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.features.httptools.logic.insertHttpParameter
import org.starfall.generative.features.httptools.logic.previewHttpRequest
import org.starfall.generative.features.httptools.logic.unknownHttpRequestParameters
import org.starfall.generative.shared.model.CustomHttpTool

class HttpRequestEditingTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun insertsQuotedTypedTokenAtCursorAndReplacesSelection() {
        val (text, cursor) = insertHttpParameter("{\"count\": }", 10, 10, "limit", json = true)
        assertEquals(obj("""{"count":"{{limit}}"}"""), obj(text))
        assertEquals(text.length - 1, cursor)
        val original = "{\"auth\":\"Bearer old\"}"
        val start = original.indexOf("old")
        val replaced = insertHttpParameter(original, start + 3, start, "token", json = true).first
        assertEquals(JsonPrimitive("Bearer {{token}}"), obj(replaced)["auth"])
        assertEquals("raw {{x}}", insertHttpParameter("raw ", 4, 4, "x", json = false).first)
    }

    @Test fun insertionInsideEscapedStringKeepsJsonValid() {
        val original = """{"value":"say \"hello\" "}"""
        val cursor = original.lastIndexOf('"')
        val text = insertHttpParameter(original, cursor, cursor, "customer-id", json = true).first
        assertEquals(JsonPrimitive("say \"hello\" {{customer-id}}"), obj(text)["value"])
    }

    @Test fun findsDeletedParameterReferencesInNestedTemplatesAndRawText() {
        val template = """{"nested":[{"value":"prefix {{ old }}","keep":"{{current}}"}]}"""
        assertEquals(setOf("old"), unknownHttpRequestParameters(template, true, listOf("current")))
        assertEquals(setOf("old"), unknownHttpRequestParameters("hello {{old}}", false, emptyList()))
        assertTrue(unknownHttpRequestParameters("{broken", true, emptyList()).isEmpty())
    }

    @Test fun previewUsesRuntimeDefaultsTypesOptionalOmissionAndQueryEncoding() {
        val tool = CustomHttpTool(name = "search", method = "POST", url = "https://example.test?limit=old",
            parameters = obj("""{"type":"object","properties":{"q":{"type":"string"},"limit":{"type":"integer","default":5},"optional":{"type":"string"}},"required":["q"]}"""),
            query = obj("""{"q":"{{q}}","limit":"{{limit}}","extra":"{{optional}}"}"""),
            headers = obj("""{"X-Query":"{{q}}","X-Optional":"{{optional}}"}"""),
            body = """{"q":"{{q}}","limit":"{{limit}}","extra":"{{optional}}"}""")
        val preview = previewHttpRequest(tool, """{"q":"a & b"}""")
        assertTrue(preview.contains("q=a%20%26%20b"))
        assertTrue(preview.contains("limit=5"))
        assertFalse(preview.contains("limit=old"))
        assertTrue(preview.contains("X-Query: a & b"))
        assertTrue(preview.contains("Content-Type: application/json"))
        assertFalse(preview.contains("X-Optional"))
        assertFalse(preview.contains("extra"))
        assertEquals(JsonPrimitive(5), obj(preview.substringAfter("\n\n"))["limit"])
        assertTrue(runCatching { previewHttpRequest(tool, "{}") }.isFailure)
        assertTrue(runCatching { previewHttpRequest(tool, """{"q":3}""") }.isFailure)
    }
}
