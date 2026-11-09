package org.starfall.generative

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.features.httptools.ui.completeHttpParameter
import org.starfall.generative.features.httptools.ui.httpParameterMatches
import org.starfall.generative.shared.ui.inputs.templateVariableToken

class HttpParameterCompletionTest {
    @Test fun filtersDeclaredNamesAndPrioritizesExactMatches() {
        assertEquals(listOf("city", "city_code"), httpParameterMatches(listOf("country", "city_code", "city"), "CI", true))
        assertEquals(listOf("city", "city_code"), httpParameterMatches(listOf("city_code", "city"), "city", true))
        assertTrue(httpParameterMatches(listOf("city"), "model", true).isEmpty())
        assertTrue(httpParameterMatches(emptyList(), "", false).isEmpty())
    }

    @Test fun replacesCurrentTokenWithoutDuplicateBracesOrChangingAdjacentTokens() {
        val text = """{"q":"{{ci}}", "other":"{{country}}"}"""
        val cursor = text.indexOf("}}")
        val completed = completeHttpParameter(TextFieldValue(text, TextRange(cursor)),
            templateVariableToken(text, cursor)!!, "city", true)
        assertEquals("""{"q":"{{city}}", "other":"{{country}}"}""", completed.text)
        assertEquals(completed.text.indexOf("}}") + 2, completed.selection.end)
        assertNull(templateVariableToken(completed.text, completed.selection.end))
    }

    @Test fun quotesTokensOutsideJsonStringsAndRetainsEscapedNames() {
        val text = """{"value":{{cu}}}"""
        val cursor = text.indexOf("}}")
        val completed = completeHttpParameter(TextFieldValue(text, TextRange(cursor)),
            templateVariableToken(text, cursor)!!, "customer\"id", true)
        assertEquals(JsonPrimitive("{{customer\"id}}"), Json.parseToJsonElement(completed.text).jsonObject["value"])
        assertEquals(listOf("customer\"id"), httpParameterMatches(listOf("customer\"id"), "customer\\\"", true))
    }

    @Test fun rawBodyCompletionPreservesSurroundingTextAndRejectsClosedTokens() {
        val text = "Bearer {{to}} suffix"
        val cursor = text.indexOf("}}")
        val completed = completeHttpParameter(TextFieldValue(text, TextRange(cursor)),
            templateVariableToken(text, cursor)!!, "token", false)
        assertEquals("Bearer {{token}} suffix", completed.text)
        assertNull(templateVariableToken(text, text.length))
        assertNull(templateVariableToken("{{to\nken", 8))
        assertNull(templateVariableToken("{{to\rken", 8))
    }
}
