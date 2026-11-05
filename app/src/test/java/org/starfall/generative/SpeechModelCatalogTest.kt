package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.speech.*
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.helpers.networking.ToolHttp

class SpeechModelCatalogTest {
    @Test fun catalogsFilterOutVoicesAndNonTtsModels() {
        val eleven = parseSpeechModels(SpeechEngine.ELEVENLABS, Json.parseToJsonElement("""[
            {"model_id":"tts","name":"Speech","can_do_text_to_speech":true},
            {"model_id":"stt","can_do_text_to_speech":false},
            {"model_id":"tts","can_do_text_to_speech":true}
        ]"""))
        assertEquals(listOf("tts"), eleven.map { it.id })
        assertEquals("Speech", eleven.single().name)
        // Cartesia's modes belong to STT; valid TTS entries omit them.
        val cartesia = parseSpeechModels(SpeechEngine.CARTESIA, Json.parseToJsonElement("""{"data":[
            {"id":"sonic","capability":"tts"}, {"id":"ink","capability":"stt","modes":["batch"]}
        ]}"""))
        assertEquals(listOf("sonic"), cartesia.map { it.id })
        val speechify = parseSpeechModels(SpeechEngine.SPEECHIFY, Json.parseToJsonElement("""{
            "models":[
                {"id":"simba-3.2","name":"Simba 3.2","deprecated":false},
                {"id":"simba-3.5","name":"Simba 3.5","recommended":true,"deprecated":false},
                {"id":"legacy","name":"Legacy","deprecated":true}
            ],
            "dialogue_models":[{"id":"simba-dialogue-1.0"}]
        }"""))
        assertEquals(listOf("simba-3.2", "simba-3.5", "legacy"), speechify.map { it.id })
        assertEquals("Simba 3.2", speechify.first().name)
        assertEquals("Legacy (deprecated)", speechify[2].name)
        val fish = parseSpeechModels(SpeechEngine.FISH_AUDIO, Json.parseToJsonElement("""{
            "paths":{"/v1/tts":{"post":{"parameters":[
                {"in":"header","name":"model","schema":{"enum":["s2.1-pro","drama-3"]}}
            ]}}}
        }"""))
        assertEquals(listOf("s2.1-pro", "drama-3"), fish.map { it.id })
        assertThrows(IllegalStateException::class.java) {
            parseSpeechModels(SpeechEngine.FISH_AUDIO, Json.parseToJsonElement("""{"items":[{"_id":"voice-id"}]}"""))
        }
    }

    @Test fun requestsUseCorrectEndpointsAndNeverSendFishKeyToDocumentation() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = StandaloneSpeechClient(ToolHttp(client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                val url = chain.request().url
                chain.proceed(chain.request().newBuilder().url(server.url(url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""))).build())
            }.build()))
            fun service(engine: SpeechEngine) = SpeechService("s", "Speech", engine = engine, apiKey = "secret")
            server.enqueue(MockResponse().setBody("""[{"model_id":"eleven","can_do_text_to_speech":true}]"""))
            assertEquals("eleven", client.models(service(SpeechEngine.ELEVENLABS)).single().id)
            val eleven = server.takeRequest()
            assertEquals("/v1/models", eleven.path)
            assertEquals("secret", eleven.getHeader("xi-api-key"))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"sonic","capability":"tts"}]}"""))
            assertEquals("sonic", client.models(service(SpeechEngine.CARTESIA)).single().id)
            val cartesia = server.takeRequest()
            assertEquals("/models?capability=tts", cartesia.path)
            assertEquals("Bearer secret", cartesia.getHeader("Authorization"))
            assertEquals(CARTESIA_VERSION, cartesia.getHeader("Cartesia-Version"))
            server.enqueue(MockResponse().setBody("""{"models":[{"id":"simba-3.5","name":"Simba 3.5"}]}"""))
            assertEquals("simba-3.5", client.models(service(SpeechEngine.SPEECHIFY)).single().id)
            val speechify = server.takeRequest()
            assertEquals("/v1/audio/models", speechify.path)
            assertEquals("Bearer secret", speechify.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("""{"paths":{"/v1/tts":{"post":{"parameters":[
                {"in":"header","name":"model","schema":{"enum":["drama-3"]}}
            ]}}}}"""))
            assertEquals("drama-3", client.models(service(SpeechEngine.FISH_AUDIO)).single().id)
            val fish = server.takeRequest()
            assertEquals("/api-reference/openapi.json", fish.path)
            assertNull(fish.getHeader("Authorization"))
            assertNull(fish.getHeader("xi-api-key"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"secret denied"}"""))
            try { client.models(service(SpeechEngine.CARTESIA)); fail("Expected HTTP failure") }
            catch (error: IllegalStateException) { assertTrue(error.message!!.contains("401")); assertFalse(error.message!!.contains("secret")) }
        } finally { server.shutdown() }
    }
}
