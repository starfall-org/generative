package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.*
import org.starfall.generative.features.mcp.shared.ui.*
import org.starfall.generative.features.machines.shared.ui.*
import org.starfall.generative.integrations.remote.*
import org.starfall.generative.features.home.chat.*
import org.starfall.generative.integrations.modelapis.*
import org.starfall.generative.integrations.speech.*
import org.starfall.generative.shared.helpers.speech.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration

class SpeechSynthesisTest {
    @Test fun codeBlocksAreSkippedByDefaultAndCanBeEnabled() {
        val markdown = "Before `inline code`.\n\n```kotlin\nprintln(\"fenced\")\n```\n\nAfter.\n\n    indented_code()\n"
        val spoken = speechText(markdown)
        assertTrue(spoken.contains("Before inline code."))
        assertTrue(spoken.contains("After."))
        assertFalse(spoken.contains("println"))
        assertFalse(spoken.contains("indented_code"))
        assertTrue(speechText(markdown, includeCodeBlocks = true).contains("println"))
        assertTrue(speechText(markdown, includeCodeBlocks = true).contains("indented code"))
        assertEquals("", speechText("```\nonly_code()\n```"))
    }
    private fun provider(type: ModelApiType = ModelApiType.OPENAI, url: String = "https://example.test/v1") =
        ModelProvider("p", "Speech", type, baseUrl = url, auth = Authorization(value = "secret"),
            config = ProviderConfiguration(modelConfigs = mapOf("gemini-tts" to ModelConfiguration(temperature = 0.7))))

    @Test fun readsMarkdownContentWithoutFormattingOrLinkTargets() {
        val text = speechText("""
            # Hello **world**
            - Read *this* and ~~that~~.
            1. [Documentation](https://example.test)
            > A `code` example &amp; escaped \*star\*.
            ---
            | Name | Value |
            | --- | --- |
            | A | B |
        """.trimIndent())
        assertTrue(text.contains("Hello world"))
        assertTrue(text.contains("Read this and that."))
        assertTrue(text.contains("Documentation"))
        assertTrue(text.contains("escaped star."))
        assertFalse(text.contains("&"))
        assertFalse(text.contains("*"))
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("#"))
        assertFalse(text.contains("|"))
        assertFalse(text.contains("---"))
        assertEquals("", speechText("---"))
    }

    @Test fun openAiUsesDefaultVoiceAndConfiguredParameters() {
        val service = SpeechService("s", "Voice", modelId = "gpt-4o-mini-tts", voice = "Default",
            speed = 3f, instructions = "Speak gently", responseFormat = "wav",
            extraBody = buildJsonObject { put("custom", true); put("input", "wrong") })
        val payload = speechPayload(provider(), service, "Hello")
        assertEquals("alloy", payload["voice"]!!.jsonPrimitive.content)
        assertEquals("Hello", payload["input"]!!.jsonPrimitive.content)
        assertEquals("wav", payload["response_format"]!!.jsonPrimitive.content)
        assertEquals(3f, payload["speed"]!!.jsonPrimitive.float, 0f)
        assertEquals("Speak gently", payload["instructions"]!!.jsonPrimitive.content)
        assertTrue(payload["custom"]!!.jsonPrimitive.boolean)
        assertThrows(IllegalArgumentException::class.java) {
            speechPayload(provider(), service.copy(modelId = "tts-1"), "Hello")
        }
    }

    @Test fun geminiUsesNativeSpeechConfigAndModelTemperature() {
        val payload = speechPayload(provider(ModelApiType.GOOGLE),
            SpeechService("s", "Voice", modelId = "gemini-tts", instructions = "Speak slowly", languageCode = "vi-VN"), "Xin chào")
        val config = payload["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", config["responseModalities"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(0.7, config["temperature"]!!.jsonPrimitive.double, 0.0)
        val speech = config["speechConfig"]!!.jsonObject
        assertEquals("vi-VN", speech["languageCode"]!!.jsonPrimitive.content)
        assertEquals("Kore", speech["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("speed"))
        assertTrue(payload["contents"].toString().contains("Speak slowly"))
    }

    @Test fun sendsOpenAiRequestWithAuthenticationAndReportsHttpFailures() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("audio"))
            val service = SpeechService("s", "Voice", modelId = "gpt-4o-mini-tts")
            assertArrayEquals("audio".toByteArray(), SpeechSynthesisService().synthesize(provider(url = server.url("/v1").toString()), service, "Hello"))
            val request = server.takeRequest()
            assertEquals("/v1/audio/speech", request.path)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            assertEquals("alloy", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["voice"]!!.jsonPrimitive.content)
            server.enqueue(MockResponse().setResponseCode(400).setBody("bad voice"))
            try {
                SpeechSynthesisService().synthesize(provider(url = server.url("/v1").toString()), service, "Hello")
                fail("Expected HTTP failure")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("400"))
                assertTrue(e.message!!.contains("bad voice"))
            }
        } finally { server.shutdown() }
    }

    @Test fun geminiPcmResponseIsPlayableWave() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val pcm = byteArrayOf(1, 2, 3, 4)
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;rate=24000","data":"${Base64.getEncoder().encodeToString(pcm)}"}}]}}]}"""))
            val audio = SpeechSynthesisService().synthesize(provider(ModelApiType.GOOGLE, server.url("/v1beta").toString()),
                SpeechService("s", "Voice", modelId = "models/gemini-tts"), "Hello")
            assertEquals("RIFF", audio.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals(24_000, ByteBuffer.wrap(audio, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            assertArrayEquals(pcm, audio.copyOfRange(44, audio.size))
            val request = server.takeRequest()
            assertEquals("/v1beta/models/gemini-tts:generateContent", request.path)
            assertEquals("secret", request.getHeader("x-goog-api-key"))
        } finally { server.shutdown() }
    }

    @Test fun legacyServicesKeepCompatibleDefaults() {
        val service = Json.decodeFromString<SpeechService>("""{"id":"s","name":"Old"}""")
        assertEquals("mp3", service.responseFormat)
        assertTrue(service.extraBody.isEmpty())
    }
}
