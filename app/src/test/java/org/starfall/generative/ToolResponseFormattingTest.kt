package org.starfall.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.features.home.shared.ui.toolResponseLanguage
import org.starfall.generative.features.home.shared.ui.unescapeToolResponse
import org.starfall.generative.shared.code.CodeTokenKind
import org.starfall.generative.shared.code.codeTokens

class ToolResponseFormattingTest {
    @Test fun jsonObjectRetainsEscapesAndJsonHighlighting() {
        val raw = """{"output":"first\nsecond","path":"C:\\Users\\test","quote":"say \"hello\""}"""
        val displayed = unescapeToolResponse(raw)

        assertEquals(raw, displayed)
        assertEquals("json", toolResponseLanguage(displayed))
        assertEquals(Json.parseToJsonElement(raw), Json.parseToJsonElement(displayed))
        assertTrue(codeTokens(toolResponseLanguage(displayed), displayed).any { it.kind == CodeTokenKind.ATTRIBUTE })
    }

    @Test fun jsonArrayAndLargeJsonStayValid() {
        val array = """[{"type":"text","text":"line 1\nline 2"}]"""
        assertEquals(array, unescapeToolResponse(array))
        assertEquals("json", toolResponseLanguage(unescapeToolResponse(array)))

        val large = """{"payload":"${"a".repeat(100_100)}\n"}"""
        assertEquals(large, unescapeToolResponse(large))
        assertEquals("json", toolResponseLanguage(large))
    }

    @Test fun encodedJsonStringIsDecodedAndDetected() {
        val wrapped = JsonPrimitive("""{"ready":true}""").toString()
        val displayed = unescapeToolResponse(wrapped)

        assertEquals("""{"ready":true}""", displayed)
        assertEquals("json", toolResponseLanguage(displayed))
    }

    @Test fun escapedPlainTextIsStillDecodedButNotClassifiedAsJson() {
        val displayed = unescapeToolResponse("""first\nsecond\titem""")
        assertEquals("first\nsecond\titem", displayed)
        assertEquals("text", toolResponseLanguage(displayed))
        assertEquals("text", toolResponseLanguage("""{"unquoted":value}"""))
    }
}
