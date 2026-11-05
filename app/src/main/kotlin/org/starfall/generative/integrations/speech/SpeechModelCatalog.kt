package org.starfall.generative.integrations.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.helpers.networking.ToolHttp

/** Synthesis models are separate from voice IDs, including Fish Audio's /model directory. */
internal class SpeechModelCatalog(private val http: ToolHttp) {
    suspend fun load(service: SpeechService): List<SpeechSynthesisModel> = withContext(Dispatchers.IO) {
        val engine = service.engine
        require(engine.standalone)
        val request = if (engine == SpeechEngine.FISH_AUDIO) {
            // The public schema publishes the synthesis header enum. Never send credentials to docs.
            Request.Builder().url("https://docs.fish.audio/api-reference/openapi.json").build()
        } else {
            require(service.apiKey.isNotBlank()) { "Enter an API key." }
            val adapter = adapter(engine)
            Request.Builder().url(adapter.base + when (engine) {
                    SpeechEngine.ELEVENLABS -> "/v1/models"
                    SpeechEngine.CARTESIA -> "/models?capability=tts"
                    SpeechEngine.SPEECHIFY -> "/v1/audio/models"
                    else -> error("Unsupported model catalog.")
                })
                .apply { adapter.headers(service.apiKey).forEach { (key, value) -> header(key, value) } }.build()
        }
        http.withResponse(request) { response ->
            http.requireSuccess(response)
            parseSpeechModels(engine, Json.parseToJsonElement(http.readJson(response, maxChars = 8 * 1024 * 1024)))
        }
    }
}

data class SpeechSynthesisModel(val id: String, val name: String)

internal fun parseSpeechModels(engine: SpeechEngine, response: JsonElement): List<SpeechSynthesisModel> {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val models = when (engine) {
        SpeechEngine.ELEVENLABS -> {
            val entries = response as? JsonArray ?: error("Invalid model list response.")
            entries.mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                if ((item["can_do_text_to_speech"] as? JsonPrimitive)?.booleanOrNull != true) return@mapNotNull null
                SpeechSynthesisModel(item.text("model_id"), item.text("name").ifBlank { item.text("model_id") })
            }
        }
        SpeechEngine.CARTESIA -> {
            val entries = (response as? JsonObject)?.get("data") as? JsonArray ?: error("Invalid model list response.")
            entries.mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                if (item.text("capability") != "tts") return@mapNotNull null
                SpeechSynthesisModel(item.text("id"), item.text("id"))
            }
        }
        SpeechEngine.SPEECHIFY -> {
            val entries = ((response as? JsonObject)?.get("models") as? JsonArray)
                ?: error("Invalid Speechify model list.")
            // TTS models are in `models`; `dialogue_models` cannot synthesize one utterance.
            // Keep deprecated but still selectable IDs visible for existing accounts.
            entries.filterIsInstance<JsonObject>()
                .map { item ->
                    val name = item.text("name").ifBlank { item.text("id") }
                    val deprecated = (item["deprecated"] as? JsonPrimitive)?.booleanOrNull == true
                    SpeechSynthesisModel(item.text("id"), if (deprecated) "$name (deprecated)" else name)
                }
        }
        SpeechEngine.FISH_AUDIO -> {
            val root = response as? JsonObject ?: error("Invalid model schema response.")
            val parameters = ((((root["paths"] as? JsonObject)?.get("/v1/tts") as? JsonObject)
                ?.get("post") as? JsonObject)?.get("parameters") as? JsonArray).orEmpty()
            val parameter = parameters.filterIsInstance<JsonObject>().firstOrNull { it.text("name") == "model" && it.text("in") == "header" }
                ?: error("Synthesis model header not found in the published schema.")
            val choices = (parameter["schema"] as? JsonObject)?.get("enum") as? JsonArray
                ?: error("Synthesis models are not listed in the published schema.")
            choices.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map { SpeechSynthesisModel(it, it) }
        }
        else -> error("Select a standalone speech engine.")
    }
    return models.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(500)
}
