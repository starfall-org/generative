package org.starfall.generative

import java.io.EOFException
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.starfall.generative.integrations.modelapis.devin.DevinWire
import org.starfall.generative.integrations.modelapis.devin.devinModelUID
import org.starfall.generative.integrations.modelapis.geminicli.GeminiCliAdapter
import org.starfall.generative.integrations.modelapis.geminicli.geminiCliEnvelope
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.defaultAuthorization

class GeminiCliDevinContractTest {
    @Test fun bothNewProvidersAreOAuthAndHaveCorrectUpstreams() {
        listOf(ModelApiType.GEMINI_CLI, ModelApiType.DEVIN).forEach { type ->
            assertTrue(type.isAccountProvider)
            assertEquals(AuthMethod.OAUTH, type.defaultAuthorization().method)
        }
        assertEquals("https://cloudcode-pa.googleapis.com/v1internal", ModelApiType.GEMINI_CLI.defaultBaseUrl)
        assertEquals("https://server.codeium.com", ModelApiType.DEVIN.defaultBaseUrl)
        assertTrue(GeminiCliAdapter.MODEL_IDS.contains("gemini-2.5-flash"))
    }

    @Test fun geminiCliEnvelopeWrapsModelAndProjectWithoutLosingTools() {
        val body = buildJsonObject {
            put("model", "gemini-3.5-flash"); put("stream", true)
            put("contents", JsonArray(listOf(buildJsonObject { put("role", "user") })))
            put("tools", JsonArray(listOf(buildJsonObject { put("functionDeclarations", JsonArray(emptyList())) })))
        }
        val output = geminiCliEnvelope("my-project", body)
        assertEquals("my-project", output.getValue("project").jsonPrimitive.content)
        assertEquals("gemini-3.5-flash", output.getValue("model").jsonPrimitive.content)
        val request = output.getValue("request").jsonObject
        assertTrue("tools" in request)
        assertTrue("contents" in request)
        assertFalse("model" in request)
        assertFalse("stream" in request)
    }

    @Test fun devinEffortRoutingFollowsCatalog() {
        val levels = listOf("medium", "high", "max")
        assertEquals("swe-2-high", devinModelUID("swe-2", null, levels))
        assertEquals("swe-2-max", devinModelUID("swe-2", "max", levels))
        assertEquals("swe-2-high", devinModelUID("swe-2-high", "max", levels))
        assertEquals("glm-5-2-max", devinModelUID("glm-5-2", "max", levels))
        assertEquals("MODEL_PRIVATE_11", devinModelUID("claude-haiku-4-5", null, emptyList()))
        assertEquals("MODEL_CHAT_GPT_4_1_2025_04_14", devinModelUID("gpt-4-1", null, emptyList()))
    }

    @Test fun devinRequestUsesNativeFieldsAndEmbedsTools() {
        val tool = ToolDefinition("get_weather", "Get the weather", buildJsonObject { put("type", "object") })
        val request = DevinWire.buildChat("session-token", "swe-2-high", "system",
            listOf(DevinWire.Turn(1, "Hello", id = "msg-123")), listOf(tool), null, 4000,
            "session-123", 0)
        val fields = DevinWire.fields(request)
        val meta = DevinWire.fields(fields.first { it.number == 1 }.data)
        assertEquals("chisel", meta.first { it.number == 1 }.text())
        assertEquals("session-token", meta.first { it.number == 3 }.text())
        assertEquals(732, meta.first { it.number == 31 }.text().length)
        val history = DevinWire.fields(fields.first { it.number == 3 }.data)
        assertEquals(1L, history.first { it.number == 2 }.numberValue)
        assertEquals("Hello", history.first { it.number == 3 }.text())
        val definition = DevinWire.fields(fields.first { it.number == 10 }.data)
        assertEquals("get_weather", definition.first { it.number == 1 }.text())
        assertEquals("swe-2-high", fields.first { it.number == 21 }.text())
    }

    @Test fun devinToolDeltaAndUsageKeepCorrectFieldMappings() {
        val tool = DevinWire.ProtoWriter().apply {
            string(1, "call-a"); string(2, "get_weather")
            string(3, "{\"city\":\"HN\"}"); string(4, "raw-invalid")
            number(6, 1)
        }.build()
        val usage = DevinWire.ProtoWriter().apply {
            number(2, 10); number(3, 4); number(4, 100); number(5, 25)
        }.build()
        val delta = DevinWire.decodeDelta(DevinWire.ProtoWriter().apply {
            string(3, "Hello"); string(9, "Think"); data(6, tool); data(7, usage)
            number(5, 10)
        }.build())
        assertEquals("Hello", delta.text)
        assertEquals("Think", delta.reasoning)
        assertEquals("call-a", delta.calls.single().id)
        assertEquals("raw-invalid", delta.calls.single().invalidArguments)
        assertEquals(10L, delta.stopReason)
        val usageSnapshot = requireNotNull(delta.usage)
        assertEquals(10L, usageSnapshot.input)
        assertEquals(25L, usageSnapshot.cached)
        assertEquals(100L, usageSnapshot.written)
    }

    @Test fun devinConnectFrameRoundTripAndRejectsTruncation() {
        val payload = DevinWire.ProtoWriter().apply { string(3, "ok") }.build()
        val framed = DevinWire.envelope(payload)
        val parsed = DevinWire.nextFrame(framed.inputStream())!!
        assertEquals(0, parsed.flag)
        assertTrue(payload.contentEquals(parsed.payload))
        try {
            DevinWire.nextFrame(framed.copyOf(6).inputStream())
            fail("Expected truncated Connect frame to throw")
        } catch (_: EOFException) { }
    }
}
