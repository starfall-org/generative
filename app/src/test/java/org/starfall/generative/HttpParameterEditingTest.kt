package org.starfall.generative

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.features.httptools.logic.*

class HttpParameterEditingTest {
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun renamingKeepsNestedSchemaAndRootConstraintsAndUpdatesRequired() {
        val root = obj("""{"type":"object","additionalProperties":false,"properties":{"old":{"type":"object","properties":{"id":{"type":"integer"}}},"keep":{"type":"string"}},"required":["old","keep"]}""")
        val definition = root["properties"]!!.jsonObject.getValue("old").jsonObject
        val renamed = updateHttpParameter(root, "old", "filters", definition, true)
        assertEquals(definition, renamed["properties"]!!.jsonObject["filters"])
        assertFalse("old" in renamed["properties"]!!.jsonObject)
        assertEquals(JsonPrimitive(false), renamed["additionalProperties"])
        assertEquals(setOf("filters", "keep"), renamed["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val removed = removeHttpParameter(renamed, "filters")
        assertEquals(listOf(JsonPrimitive("keep")), removed["required"]!!.jsonArray.toList())
        assertTrue(runCatching { updateHttpParameter(root, null, "keep", definition, false) }.isFailure)
    }

    @Test fun createsTypedDefaultsEnumsAndAdvancedConstraints() {
        val definition = httpParameterDefinition("integer", "Limit", true, "5", "[5,10]", """{"minimum":1,"maximum":20}""")
        assertEquals(JsonPrimitive(5), definition["default"])
        assertEquals(JsonPrimitive(1), definition["minimum"])
        assertEquals(JsonPrimitive("Limit"), definition["description"])
        assertEquals(JsonPrimitive(""), httpParameterDefinition("string", "", true, "", "", "{}")["default"])
        assertTrue(runCatching { httpParameterDefinition("integer", "", true, "oops", "", "{}") }.isFailure)
        assertTrue(runCatching { httpParameterDefinition("integer", "", true, "3", "[1,2]", "{}") }.isFailure)
        val advanced = httpParameterDefinition("advanced", "", false, "", "", """{"type":["string","null"],"pattern":"^[A-Z]"}""")
        assertEquals(Json.parseToJsonElement("""["string","null"]"""), advanced["type"])
    }

    @Test fun renamesTemplatesThroughJsonWithoutCorruptingEscapedNamesOrValues() {
        val original = """{"value":"{{old}}","message":"prefix {{ old }}","literal":"old"}"""
        val renamed = obj(renameHttpParameterTokens(original, "old", "customer-id"))
        assertEquals(JsonPrimitive("{{customer-id}}"), renamed["value"])
        assertEquals(JsonPrimitive("prefix {{customer-id}}"), renamed["message"])
        assertEquals(JsonPrimitive("old"), renamed["literal"])
        assertEquals("raw {{new}}", renameHttpParameterTokens("raw {{old}}", "old", "new"))
    }
}
