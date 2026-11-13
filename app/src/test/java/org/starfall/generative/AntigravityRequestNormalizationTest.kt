package org.starfall.generative

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.modelapis.antigravity.normalizeAntigravityRequest
import org.starfall.generative.integrations.modelapis.antigravity.restoreAntigravityToolArguments
import org.starfall.generative.shared.helpers.networking.*

class AntigravityRequestNormalizationTest {
    @Test fun responseCleanupReadsIndividualPartsOnDemand() {
        var reads = 0
        val parts = JsonArray(object : AbstractList<JsonElement>() {
            override val size: Int = 100
            override fun get(index: Int): JsonElement {
                reads++
                return obj("functionCall" to obj("name" to str("optional"),
                    "args" to obj("_" to JsonPrimitive(true))))
            }
        })
        val original = obj("model" to str("claude"), "tools" to JsonArray(listOf(
            obj("functionDeclarations" to JsonArray(listOf(obj("name" to str("optional"),
                "parameters" to obj("type" to str("object")))))))))
        val response = obj("candidates" to JsonArray(listOf(obj("content" to obj("parts" to parts)))))
        val restored = restoreAntigravityToolArguments(original, response)
        assertEquals(0, reads)
        val last = restored.getValue("candidates").jsonArray[0].jsonObject.getValue("content").jsonObject
            .getValue("parts").jsonArray[99].jsonObject.getValue("functionCall").jsonObject.getValue("args").jsonObject
        assertTrue(last.isEmpty())
        assertEquals(1, reads)
    }

    @Test fun claudeKeepsTokenLimitValidatesDeclarationsAndScopesStableSessions() {
        val request = obj(
            "contents" to JsonArray(listOf(
                obj("role" to str("user"), "parts" to JsonArray(listOf(obj("text" to str("first question"))))),
                obj("role" to str("model"), "parts" to JsonArray(listOf(
                    obj("functionCall" to obj("name" to str("optional"), "args" to obj("flag" to str("history"), "_" to JsonPrimitive(true))))
                )))
            )),
            "tools" to JsonArray(listOf(obj("functionDeclarations" to JsonArray(listOf(
                obj("name" to str("optional"), "parameters" to obj(
                    "type" to str("object"), "properties" to obj(
                        "flag" to obj("type" to str("string")),
                        "config" to obj("type" to str("object"), "properties" to obj("value" to obj("type" to str("string"))))
                    )
                )),
                obj("name" to str("declared"), "parameters" to obj(
                    "type" to str("object"), "properties" to obj("_" to obj("type" to str("boolean")))
                ))
            ))))),
            "generationConfig" to obj("maxOutputTokens" to JsonPrimitive(2000), "temperature" to JsonPrimitive(0.2))
        )
        val normalized = normalizeAntigravityRequest(request, "claude-sonnet-4-6", "account-a")
        val generation = normalized.getValue("generationConfig").jsonObject
        assertEquals(2000, generation.getValue("maxOutputTokens").jsonPrimitive.int)
        assertEquals("VALIDATED", normalized.getValue("toolConfig").jsonObject
            .getValue("functionCallingConfig").jsonObject.text("mode"))
        val declarations = normalized.getValue("tools").jsonArray[0].jsonObject
            .getValue("functionDeclarations").jsonArray.map { it.jsonObject }
        val optional = declarations[0].getValue("parameters").jsonObject
        assertEquals(listOf("_"), optional.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("boolean", optional.getValue("properties").jsonObject.getValue("_").jsonObject.text("type"))
        val nested = optional.getValue("properties").jsonObject.getValue("config").jsonObject
        assertEquals(listOf("_"), nested.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val declared = declarations[1].getValue("parameters").jsonObject
        assertEquals(setOf("_"), declared.getValue("properties").jsonObject.keys)
        assertEquals(listOf("_"), declared.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(request["contents"], normalized["contents"])

        val longer = JsonObject(request + ("contents" to JsonArray(request.getValue("contents").jsonArray +
            obj("role" to str("user"), "parts" to JsonArray(listOf(obj("text" to str("next question"))))))))
        assertEquals(normalized.text("sessionId"), normalizeAntigravityRequest(longer, "claude-sonnet-4-6", "account-a").text("sessionId"))
        assertNotEquals(normalized.text("sessionId"),
            normalizeAntigravityRequest(request, "claude-sonnet-4-6", "account-b").text("sessionId"))
        val explicit = JsonObject(request + ("sessionId" to str(" -123 ")))
        assertEquals("-123", normalizeAntigravityRequest(explicit, "claude-sonnet-4-6", "account-a").text("sessionId"))
    }

    @Test fun nonClaudeDropsOnlyTokenLimitAndImagesDoNotInventSessions() {
        val request = obj(
            "contents" to JsonArray(listOf(obj("role" to str("user"),
                "parts" to JsonArray(listOf(obj("text" to str("hello"))))))),
            "generationConfig" to obj("maxOutputTokens" to JsonPrimitive(2000), "temperature" to JsonPrimitive(0.4))
        )
        val gemini = normalizeAntigravityRequest(request, "gemini-3.1-pro", "account-a")
        assertFalse(gemini.getValue("generationConfig").jsonObject.containsKey("maxOutputTokens"))
        assertEquals(0.4, gemini.getValue("generationConfig").jsonObject.getValue("temperature").jsonPrimitive.double, 0.0)
        assertTrue(gemini.text("sessionId").startsWith("-"))
        assertFalse(gemini.containsKey("toolConfig"))

        val image = normalizeAntigravityRequest(request, "gemini-3.1-flash-image", "account-a")
        assertFalse(image.getValue("generationConfig").jsonObject.containsKey("maxOutputTokens"))
        assertFalse(image.containsKey("sessionId"))
        val explicit = JsonObject(request + ("sessionId" to str("-999")))
        assertEquals("-999", normalizeAntigravityRequest(explicit, "gemini-3.1-flash-image", "account-a").text("sessionId"))
    }

    @Test fun responseCleanupRemovesOnlyInjectedValidatedPlaceholders() {
        val original = obj(
            "model" to str("claude-sonnet-4-6"),
            "tools" to JsonArray(listOf(obj("functionDeclarations" to JsonArray(listOf(
                obj("name" to str("optional"), "parameters" to obj("type" to str("object"), "properties" to obj(
                    "flag" to obj("type" to str("string")),
                    "config" to obj("type" to str("object"), "properties" to obj("value" to obj("type" to str("string"))))
                ))),
                obj("name" to str("declared"), "parameters" to obj("type" to str("object"),
                    "properties" to obj("_" to obj("type" to str("boolean"))))),
                obj("name" to str("required"), "parameters" to obj("type" to str("object"),
                    "properties" to obj("flag" to obj("type" to str("string"))), "required" to JsonArray(listOf(str("flag"))))),
                obj("name" to str("empty"))
            )))))
        )
        fun call(name: String, args: JsonObject) = obj("functionCall" to obj("name" to str(name), "args" to args))
        val response = obj("candidates" to JsonArray(listOf(obj("content" to obj("parts" to JsonArray(listOf(
            call("optional", obj("flag" to str("x"), "_" to JsonPrimitive(true),
                "config" to obj("value" to str("nested"), "_" to JsonPrimitive(true)))),
            call("declared", obj("_" to JsonPrimitive(false))),
            call("required", obj("flag" to str("x"), "_" to JsonPrimitive(true))),
            call("unknown", obj("_" to JsonPrimitive(true))),
            call("empty", obj("_" to JsonPrimitive(true)))
        )))))))
        val parts = restoreAntigravityToolArguments(original, response).getValue("candidates").jsonArray[0].jsonObject
            .getValue("content").jsonObject.getValue("parts").jsonArray.map {
                it.jsonObject.getValue("functionCall").jsonObject.getValue("args").jsonObject
            }
        assertEquals(setOf("flag", "config"), parts[0].keys)
        assertEquals(setOf("value"), parts[0].getValue("config").jsonObject.keys)
        assertEquals(false, parts[1].getValue("_").jsonPrimitive.boolean)
        assertEquals(true, parts[2].getValue("_").jsonPrimitive.boolean)
        assertEquals(true, parts[3].getValue("_").jsonPrimitive.boolean)
        assertTrue(parts[4].isEmpty())
    }
}
