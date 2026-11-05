package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.speech.*
import org.starfall.generative.shared.model.*
import org.starfall.generative.integrations.speech.pcmWave
import org.starfall.generative.shared.helpers.networking.ToolHttp

class StandaloneSpeechTest {
    private fun service(engine: SpeechEngine) = SpeechService("speech", "Speech", engine = engine,
        modelId = when(engine) { SpeechEngine.ELEVENLABS -> "eleven_multilingual_v2"; SpeechEngine.FISH_AUDIO -> "s2.1-pro-free"; SpeechEngine.SPEECHIFY -> "simba-3.5"; else -> "sonic-3.6" },
        voice = "voice/with space", apiKey = "private-key", responseFormat = when(engine) {
            SpeechEngine.ELEVENLABS -> "mp3_44100_128"; SpeechEngine.FISH_AUDIO -> "mp3"; SpeechEngine.SPEECHIFY -> "mp3"; else -> "wav"
        })

    @Test fun oldJsonInfersSystemAndProviderWithoutMistakingEngineIds() {
        assertEquals(SpeechEngine.SYSTEM, Json.decodeFromString<SpeechService>("""{"id":"s","name":"Old"}""").engine)
        assertEquals(SpeechEngine.PROVIDER, Json.decodeFromString<SpeechService>("""{"id":"s","name":"Old","provider":"elevenlabs"}""").engine)
        val speech = service(SpeechEngine.CARTESIA).copy(voiceName = "Friendly voice", maxWordsPerChunk = 12)
        assertEquals(speech, Json.decodeFromString<SpeechService>(Json.encodeToString(speech)))
    }
    @Test fun eachEngineOwnsItsAuthenticationAndPayload() {
        val eleven = ElevenLabsSpeechClient.synthesis(service(SpeechEngine.ELEVENLABS), "Hello")
        assertEquals("private-key", eleven.header("xi-api-key"))
        assertTrue(eleven.url.encodedPath.endsWith("voice%2Fwith%20space"))
        assertEquals("mp3_44100_128", eleven.url.queryParameter("output_format"))
        val fish = FishAudioSpeechClient.synthesis(service(SpeechEngine.FISH_AUDIO), "Hello")
        val fishBody = Buffer().also { fish.body!!.writeTo(it) }.readUtf8().let { Json.parseToJsonElement(it).jsonObject }
        assertEquals("s2.1-pro-free", fish.header("model"))
        assertEquals("voice/with space", fishBody["reference_id"]!!.jsonPrimitive.content)
        assertFalse("model" in fishBody)
        val cartesia = CartesiaSpeechClient.synthesis(service(SpeechEngine.CARTESIA), "Hello")
        val cartesiaBody = Buffer().also { cartesia.body!!.writeTo(it) }.readUtf8().let { Json.parseToJsonElement(it).jsonObject }
        assertEquals("Bearer private-key", cartesia.header("Authorization"))
        assertNull(cartesia.header("X-API-Key"))
        assertEquals(CARTESIA_VERSION, cartesia.header("Cartesia-Version"))
        assertEquals("voice/with space", cartesiaBody["voice"]!!.jsonPrimitive.content)
        assertEquals("wav", cartesiaBody["output_format"]!!.jsonObject["container"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) {
            CartesiaSpeechClient.synthesis(service(SpeechEngine.CARTESIA).copy(extraBody = buildJsonObject {
                put("language", "en"); put("locale", "en-US")
            }), "Hello")
        }
    }
    @Test fun voicePagingUsesEachServicesProtocol() {
        val e = ElevenLabsSpeechClient
        val ep = e.page(Json.parseToJsonElement("""{"voices":[{"voice_id":"v","name":"Voice"}],"has_more":true,"next_page_token":"opaque"}""").jsonObject, null)
        assertEquals("v", ep.voices.single().id)
        assertEquals("opaque", e.voices("key", "calm", ep.nextCursor, false).url.queryParameter("next_page_token"))
        assertEquals("calm", e.voices("key", "calm", null, false).url.queryParameter("search"))
        val f = FishAudioSpeechClient
        val fp = f.page(Json.parseToJsonElement("""{"items":[{"_id":"f","title":"Fish","languages":["en"]}],"total":21,"has_more":null}""").jsonObject, null)
        assertEquals("f", fp.voices.single().id)
        val request = f.voices("key", "warm", fp.nextCursor, true)
        assertEquals("2", request.url.queryParameter("page_number"))
        assertEquals("warm", request.url.queryParameter("title"))
        assertEquals("true", request.url.queryParameter("self"))
        val c = CartesiaSpeechClient
        val cp = c.page(Json.parseToJsonElement("""{"data":[{"id":"c","name":"Cartesia"}],"has_more":true,"next_page":"voice-id"}""").jsonObject, null)
        assertEquals("voice-id", c.voices("key", "soft", cp.nextCursor, false).url.queryParameter("starting_after"))
        assertEquals("soft", c.voices("key", "soft", null, false).url.queryParameter("q"))
    }

    @Test fun speechifyRequestAndVoicePaginationFollowBuildApi() {
        val speechify = SpeechifySpeechClient.synthesis(
            service(SpeechEngine.SPEECHIFY).copy(languageCode = "vi-VN"), "Hello")
        assertEquals("/v1/audio/speech", speechify.url.encodedPath)
        assertEquals("Bearer private-key", speechify.header("Authorization"))
        assertEquals("application/json", speechify.header("Accept"))
        val json = Buffer().also { speechify.body!!.writeTo(it) }.readUtf8()
            .let { Json.parseToJsonElement(it).jsonObject }
        assertEquals("Hello", json["input"]!!.jsonPrimitive.content)
        assertEquals("simba-3.5", json["model"]!!.jsonPrimitive.content)
        assertEquals("voice/with space", json["voice_id"]!!.jsonPrimitive.content)
        assertEquals("vi-VN", json["language"]!!.jsonPrimitive.content)
        assertEquals("mp3", json["audio_format"]!!.jsonPrimitive.content)
        assertFalse("text" in json)

        val page = SpeechifySpeechClient.page(Json.parseToJsonElement("""{
            "voices":[{"id":"first","display_name":"Vietnamese voice","locale":"vi-VN","type":"shared"},
            {"id":"clone","display_name":"My voice","locale":"en-US","type":"personal"}],
            "has_more":true,"next_cursor":"opaque-123"
        }""").jsonObject, null)
        assertEquals(listOf("first", "clone"), page.voices.map { it.id })
        assertEquals("Vietnamese voice", page.voices.first().name)
        assertEquals(listOf("vi-VN"), page.voices.first().languages)
        val next = SpeechifySpeechClient.voices("private-key", "Vietnamese", page.nextCursor, true)
        assertEquals("opaque-123", next.url.queryParameter("cursor"))
        assertEquals("personal", next.url.queryParameter("type"))
        assertNull(next.url.queryParameter("search"))
        assertEquals("100", next.url.queryParameter("limit"))
        assertNull(SpeechifySpeechClient.page(Json.parseToJsonElement(
            """{"voices":[],"has_more":false,"next_cursor":null}""").jsonObject, "opaque-123").nextCursor)

        assertThrows(IllegalArgumentException::class.java) {
            SpeechifySpeechClient.synthesis(service(SpeechEngine.SPEECHIFY), "x".repeat(2_001))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechifySpeechClient.synthesis(service(SpeechEngine.SPEECHIFY).copy(responseFormat = "pcm"), "Hi")
        }
    }

    @Test fun speechifyBase64AudioIsDecodedAndSearchesAcrossSparsePages() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = StandaloneSpeechClient(ToolHttp(client = OkHttpClient.Builder().followRedirects(false)
                .addInterceptor { chain ->
                    val url = chain.request().url
                    chain.proceed(chain.request().newBuilder()
                        .url(server.url(url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""))).build())
                }.build()))
            val wav = pcmWave(ByteArray(300))
            val encoded = java.util.Base64.getEncoder().encodeToString(wav)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"audio_data":"$encoded","audio_format":"wav"}"""))
            assertArrayEquals(wav, client.synthesize(service(SpeechEngine.SPEECHIFY), "Hello"))
            assertEquals("/v1/audio/speech", server.takeRequest().path)
            assertThrows(IllegalStateException::class.java) {
                decodeSpeechifyAudio(Json.parseToJsonElement("""{"audio_data":"bad!"}"""))
            }

            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"voices":[{"id":"a","display_name":"English"}],"has_more":true,"next_cursor":"next"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"voices":[{"id":"vi","display_name":"Vietnamese"}],"has_more":false,"next_cursor":null}"""))
            val page = client.voices(service(SpeechEngine.SPEECHIFY), "vietnamese")
            assertEquals(listOf("vi"), page.voices.map { it.id })
            assertNull(page.nextCursor)
            assertNull(server.takeRequest().requestUrl?.queryParameter("search"))
            assertEquals("next", server.takeRequest().requestUrl?.queryParameter("cursor"))
        } finally { server.shutdown() }
    }

    @Test fun binaryAudioErrorsRedirectsAndCancellation() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = StandaloneSpeechClient(ToolHttp(client = OkHttpClient.Builder().followRedirects(false)
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build()))
            val wave = pcmWave(ByteArray(400))
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wave)))
            assertArrayEquals(wave, client.synthesize(service(SpeechEngine.CARTESIA), "Hello"))
            for (code in listOf(400,401,402,403,422,429,500,503)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("""{"message":"private-key denied"}"""))
                try { client.synthesize(service(SpeechEngine.CARTESIA), "Hello"); fail("Expected HTTP $code") }
                catch (failure: IllegalStateException) { assertTrue(failure.message!!.contains("$code")); assertFalse(failure.message!!.contains("private-key")) }
            }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.test/"))
            try { client.synthesize(service(SpeechEngine.CARTESIA), "Hello"); fail("Redirect must fail") } catch (_: IllegalStateException) {}
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
            try { client.synthesize(service(SpeechEngine.CARTESIA), "Hello"); fail("JSON must fail") } catch (_: IllegalArgumentException) {}
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            try { withTimeout(250) { client.synthesize(service(SpeechEngine.CARTESIA), "Hello") }; fail("Must cancel") } catch (_: TimeoutCancellationException) {}
        } finally { server.shutdown() }
    }
}
