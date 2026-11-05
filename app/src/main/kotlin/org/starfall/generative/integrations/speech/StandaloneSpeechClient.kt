package org.starfall.generative.integrations.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.SpeechEngine
import kotlinx.serialization.json.*
import java.util.Base64
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLog
import org.starfall.generative.shared.model.logging.DebugLogCategory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// Only counts, sizes, engine and result are logged; text, audio and credentials stay private.
class StandaloneSpeechClient(private val http: ToolHttp = ToolHttp(client = OkHttpClient.Builder()
    .followRedirects(false).callTimeout(90, TimeUnit.SECONDS).build())) {
    suspend fun synthesize(service: SpeechService, text: String): ByteArray = withContext(Dispatchers.IO) {
        require(text.isNotBlank() && text.length <= 10_000) { "Speech text must contain 1 to 10000 characters." }
        val logId = DebugLog.beginOperation(DebugLogCategory.NETWORK, "Speech",
            "Synthesis via ${service.engine}", "inputCharacters=${text.length}")
        try {
            http.withResponse(adapter(service.engine).synthesis(service, text)) { response ->
                http.requireSuccess(response)
                val mime = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                val speechify = service.engine == SpeechEngine.SPEECHIFY
                require(if (speechify) mime == "application/json" else
                    (mime.startsWith("audio/") || mime == "application/octet-stream")) {
                    "Speech API did not return the expected audio response."
                }
                val bytes = if (speechify) {
                    decodeSpeechifyAudio(Json.parseToJsonElement(http.readJson(response,
                        sanitizeMedia = false, maxChars = 46 * 1024 * 1024)))
                } else response.body.byteStream().use { source ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 32 * 1024 * 1024) { "Speech audio exceeds 32 MB." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                require(bytes.isNotEmpty() && bytes.size <= 32 * 1024 * 1024) { "Speech audio is empty or exceeds 32 MB." }
                require(org.starfall.generative.shared.helpers.audio.audioMime(bytes) != null) { "Speech API returned unsupported audio." }
                bytes
            }.also { result ->
                DebugLog.finishOperation(logId, "Speech synthesized", details = "audioBytes=${result.size}")
            }
        } catch (error: Exception) {
            DebugLog.finishOperation(logId, "Speech synthesis failed", failed = true,
                details = error::class.java.simpleName, error = error)
            throw error
        }
    }
    suspend fun models(service: SpeechService): List<SpeechSynthesisModel> = SpeechModelCatalog(http).load(service)

    suspend fun voices(service: SpeechService, query: String, cursor: String? = null, owned: Boolean = false): VoicePage =
        withContext(Dispatchers.IO) {
            require(service.apiKey.isNotBlank()) { "Enter an API key to fetch voices." }
            val engine = adapter(service.engine)
            val speechifySearch = service.engine == SpeechEngine.SPEECHIFY && query.isNotBlank()
            var next = cursor
            val matches = mutableListOf<SpeechVoice>()
            var requests = 0
            // Speechify provides cursor pagination but no name-search parameter.
            // Search fetched pages locally, retaining the next server cursor.
            do {
                val page = engine.page(http.json(engine.voices(service.apiKey, query, next, owned)), next)
                matches += page.voices.filter { voice ->
                    !speechifySearch || listOf(voice.name, voice.id, voice.description)
                        .plus(voice.languages).any { it.contains(query.trim(), ignoreCase = true) }
                }
                next = page.nextCursor
                requests++
            } while (speechifySearch && next != null && matches.size < 50 && requests < 6)
            VoicePage(matches.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(100), next)
        }
}

data class SpeechVoice(val id: String, val name: String, val description: String = "", val languages: List<String> = emptyList())
data class VoicePage(val voices: List<SpeechVoice>, val nextCursor: String? = null)


/** Decode Speechify's Base64 JSON response, bounded before allocating audio bytes. */
internal fun decodeSpeechifyAudio(value: JsonElement): ByteArray {
    val body = value as? JsonObject ?: error("Speechify returned invalid JSON.")
    val encoded = (body["audio_data"] as? JsonPrimitive)?.contentOrNull
        ?: error("Speechify response has no audio_data.")
    require(encoded.isNotBlank() && encoded.length <= 45 * 1024 * 1024) {
        "Speechify audio is empty or exceeds the audio size limit."
    }
    return try {
        Base64.getDecoder().decode(encoded).also {
            require(it.isNotEmpty() && it.size <= 32 * 1024 * 1024) {
                "Speechify audio is empty or exceeds 32 MB."
            }
        }
    } catch (_: IllegalArgumentException) {
        error("Speechify returned invalid Base64 audio.")
    }
}
