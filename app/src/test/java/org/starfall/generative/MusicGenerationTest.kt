package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.media.MusicGenerationClient
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.integrations.speech.pcmWave
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.media.*
import org.starfall.generative.shared.helpers.networking.ToolHttp
import java.nio.file.Files
import java.util.Base64

class MusicGenerationTest {
    private fun profile(type: MediaResponseType = MediaResponseType.AUDIO) = MediaRequestProfile("track", "My music",
        chain = MediaRequestChain(steps = listOf(MediaRequestStep(endpoint = "https://music.example/compose",
            body = """{"prompt":"{{prompt}}","model_id":"vendor-music"}""")),
            finalResponse = MediaFinalResponse(type = type, mediaPath = "audio", valueKind = MediaValueKind.BASE64)))
    @Test fun emptyLibraryAndIndependentSelectionSurviveSerialization() {
        assertNull(Json.decodeFromString<ToolSettings>("{}").system["generate_music"])
        val music = SystemToolConfig(enabled = true, musicRequestProfiles = mapOf("track" to profile()), activeMusicRequestProfileId = "track")
        assertTrue(systemMediaToolAvailable("generate_music", music, emptyList()))
        assertEquals(music, Json.decodeFromString<SystemToolConfig>(Json.encodeToString(music)))
        assertEquals(1, music.profiles(MediaKind.MUSIC).size)
        val removed = music.withMusicProfiles(emptyMap(), "track")
        assertFalse(removed.enabled); assertEquals("", removed.activeMusicRequestProfileId)
        assertFalse(systemMediaToolAvailable("generate_music", removed, emptyList()))
        assertNull(music.copy(musicRequestProfiles = mapOf("track" to profile().copy(mode = MediaGenerationMode.DEFAULT))).activeMusicRequestProfile)
        assertThrows(IllegalArgumentException::class.java) {
            ToolSettings(system = mapOf("generate_music" to music.copy(musicRequestProfiles =
                mapOf("track" to profile().copy(encryptedCredential = "plain-key"))))).validateMusicSecrets()
        }
        assertFalse(profile().copy(chain = profile().chain.copy(steps = listOf(MediaRequestStep(endpoint = "https://music.example", headers = mapOf("Authorization" to "raw-key"))))).validMusicProfile())
    }
    @Test fun binaryAndJsonBase64SaveAudioAndStepTestsCreateNoFile() = runBlocking {
        val server = MockWebServer(); server.start()
        val directory = Files.createTempDirectory("music-test").toFile()
        try {
            val files = ToolFiles(directory)
            val http = ToolHttp(files, OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build())
            val client = MusicGenerationClient(http)
            val wave = pcmWave(ByteArray(400))
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wave)))
            val result = client.generate(profile(), "Ambient piano")
            assertTrue(result["files"]!!.jsonArray.single().jsonPrimitive.content.endsWith(".wav"))
            val request = server.takeRequest()
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("vendor-music", body["model_id"]!!.jsonPrimitive.content)
            assertEquals("Ambient piano", body["prompt"]!!.jsonPrimitive.content)
            assertNull(request.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("""{"audio":"${Base64.getEncoder().encodeToString(wave)}"}"""))
            val json = client.generate(profile(MediaResponseType.JSON), "Piano")
            assertTrue(json["files"]!!.jsonArray.single().jsonPrimitive.content.endsWith(".wav"))
            val before = files.list().size
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wave)))
            client.test(profile(), "Test", 0)
            assertEquals(before, files.list().size)
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody("not audio"))
            try { client.generate(profile(), "Test"); fail("Corrupt audio must fail") } catch (_: IllegalStateException) {}
            assertEquals(before, files.list().size)
        } finally { server.shutdown(); directory.deleteRecursively() }
    }
    @Test fun audioLinksAndRedirectsNeverReceiveTheProfileCredential() = runBlocking {
        val server = MockWebServer(); server.start()
        val directory = Files.createTempDirectory("music-links").toFile()
        try {
            val http = ToolHttp(ToolFiles(directory), OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build())
            val chain = profile(MediaResponseType.JSON).chain.let { current -> current.copy(
                steps = current.steps.map { it.copy(headers = mapOf("Authorization" to "Bearer {{api_token}}")) },
                finalResponse = current.finalResponse.copy(valueKind = MediaValueKind.LINK)) }
            server.enqueue(MockResponse().setBody("""{"audio":"https://music.example/download"}"""))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://cdn.example/result"))
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(pcmWave(ByteArray(400)))))
            org.starfall.generative.integrations.media.MediaRequestChainExecutor(http, music = true, credential = "private-key")
                .execute(chain, null, "", "Music", emptyList())
            assertEquals("Bearer private-key", server.takeRequest().getHeader("Authorization"))
            assertNull(server.takeRequest().getHeader("Authorization"))
            assertNull(server.takeRequest().getHeader("Authorization"))
            assertEquals(1, http.requireFiles().list().size)
        } finally { server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun asyncPollingUsesJobResultsAndRejectsFailureWithoutSaving() = runBlocking {
        val server = MockWebServer(); server.start()
        val directory = Files.createTempDirectory("music-poll").toFile()
        try {
            val http = ToolHttp(ToolFiles(directory), OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build())
            val chain = MediaRequestChain(listOf(
                MediaRequestStep(endpoint = "https://music.example/create"),
                MediaRequestStep(endpoint = "https://music.example/jobs/{{step1.response.body.id}}", method = "GET",
                    observation = MediaResponseObservation(statusPath = "status", retry = MediaRetryPolicy(3, 0, 1000)))),
                MediaFinalResponse(type = MediaResponseType.JSON, mediaPath = "audio", valueKind = MediaValueKind.BASE64))
            val music = profile().copy(chain = chain)
            server.enqueue(MockResponse().setBody("""{"id":"job"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"done","audio":"${Base64.getEncoder().encodeToString(pcmWave(ByteArray(400)))}"}"""))
            MusicGenerationClient(http).generate(music, "Music")
            assertEquals(1, http.requireFiles().list().size)
            server.enqueue(MockResponse().setBody("""{"id":"job"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"failed","error":"secret"}"""))
            try { MusicGenerationClient(http).generate(music, "Music"); fail("Failure must stop") }
            catch (failure: IllegalStateException) { assertFalse(failure.message!!.contains("secret")) }
            assertEquals(1, http.requireFiles().list().size)
        } finally { server.shutdown(); directory.deleteRecursively() }
    }
}
