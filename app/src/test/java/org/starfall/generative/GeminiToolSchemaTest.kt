package org.starfall.generative

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.helpers.networking.geminiFunctionParameters

class GeminiToolSchemaTest {
    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test fun stripsFieldsGeminiRejects() {
        val result = geminiFunctionParameters(parse("""{
            "${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":false,
            "properties":{"uri":{"type":"string","description":"d"},
              "files":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,
                 "properties":{"kind":{"const":"video"}},"required":["kind","missing"]}}},
            "required":["uri","ghost"],
            "anyOf":[{"required":["uri"]},{"required":["files"]}]}"""))!!
        assertNull(result["additionalProperties"]); assertNull(result["\$schema"]); assertNull(result["anyOf"])
        assertEquals(listOf("uri"), result["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val item = result["properties"]!!.jsonObject["files"]!!.jsonObject["items"]!!.jsonObject
        assertNull(item["additionalProperties"])
        assertEquals(listOf("kind"), item["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("video", item["properties"]!!.jsonObject["kind"]!!.jsonObject["enum"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test fun nullableTypeArraysAndEmptyObjects() {
        val result = geminiFunctionParameters(parse("""{"type":"object","properties":{"n":{"type":["string","null"]}}}"""))!!
        val n = result["properties"]!!.jsonObject["n"]!!.jsonObject
        assertEquals("string", n["type"]!!.jsonPrimitive.content)
        assertEquals(true, n["nullable"]!!.jsonPrimitive.boolean)
        assertNull(geminiFunctionParameters(parse("""{"type":"object","properties":{}}""")))
    }
}
