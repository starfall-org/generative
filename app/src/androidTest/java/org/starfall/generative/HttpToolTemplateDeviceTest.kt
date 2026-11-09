package org.starfall.generative

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.httptools.logic.renameHttpParameterTokens
import org.starfall.generative.features.httptools.logic.unknownHttpRequestParameters
import org.starfall.generative.integrations.httptools.buildHttpToolRequest
import org.starfall.generative.shared.model.CustomHttpTool

/** Android's ICU regex parser rejects unescaped closing braces that the JVM accepts. */
@RunWith(AndroidJUnit4::class)
class HttpToolTemplateDeviceTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun scansEmptyEditorAndNestedParameterReferences() {
        assertTrue(unknownHttpRequestParameters("{}", true, emptyList()).isEmpty())
        assertEquals(setOf("missing"), unknownHttpRequestParameters(
            """{"nested":["{{ city }}","prefix {{missing}}"]}""", true, listOf("city")
        ))
        assertEquals(setOf("city"), unknownHttpRequestParameters("hello {{ city }}", false, emptyList()))
    }

    @Test fun renamesLiteralParameterNamesInJsonAndRawText() {
        assertEquals(obj("""{"q":"{{city}}","keep":"{{axb}}"}"""), obj(
            renameHttpParameterTokens("""{"q":"{{ a.b }}","keep":"{{axb}}"}""", "a.b", "city")
        ))
        assertEquals("hello {{city}}", renameHttpParameterTokens("hello {{ a.b }}", "a.b", "city"))
    }

    @Test fun validatesAndResolvesQueryHeadersAndTypedBodyOnAndroid() {
        val tool = CustomHttpTool(
            name = "weather", method = "POST", url = "https://example.test",
            parameters = obj("""{"type":"object","properties":{"city":{"type":"string"},"count":{"type":"integer"}},"required":["city","count"]}"""),
            query = obj("""{"q":"{{ city }}"}"""),
            headers = obj("""{"X-City":"prefix {{city}}"}"""),
            body = """{"count":"{{ count }}"}"""
        )
        tool.validate()
        val request = buildHttpToolRequest(tool, obj("""{"city":"Ha Noi","count":3}"""))
        assertEquals("Ha Noi", request.url.queryParameter("q"))
        assertEquals("prefix Ha Noi", request.header("X-City"))
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        assertEquals(obj("""{"count":3}"""), obj(buffer.readUtf8()))
        assertThrows(IllegalArgumentException::class.java) {
            tool.copy(query = obj("""{"q":"{{missing}}"}""")).validate()
        }
    }
}
