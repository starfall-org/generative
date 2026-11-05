package org.starfall.generative.shared.model

import kotlinx.serialization.Serializable
import org.starfall.generative.shared.model.provider.ModelProvider

@Serializable
enum class SpeechEngine { SYSTEM, PROVIDER, ELEVENLABS, FISH_AUDIO, CARTESIA, SPEECHIFY;
    val standalone: Boolean get() = this !in listOf(SYSTEM, PROVIDER)
}

/** Stable brand key for dedicated speech APIs; independent of the user's service name. */
val SpeechEngine.iconMatchName: String?
    get() = when (this) {
        SpeechEngine.ELEVENLABS -> "ElevenLabs"
        SpeechEngine.FISH_AUDIO -> "Fish Audio"
        SpeechEngine.CARTESIA -> "Cartesia"
        SpeechEngine.SPEECHIFY -> "Speechify"
        else -> null
    }

@Serializable
data class SpeechService(
    val id: String,
    val name: String,
    val icon: String? = null,
    val provider: String = "system", // "system" or ModelProvider.id
    val modelId: String? = null,
    val voice: String = "Default",
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val apiKey: String = "",
    val sortOrder: Int = Int.MAX_VALUE,
    val instructions: String = "",
    val responseFormat: String = "mp3",
    val languageCode: String = "",
    val engine: SpeechEngine = if (provider.equals("system", true)) SpeechEngine.SYSTEM else SpeechEngine.PROVIDER,
    val voiceName: String = "",
    val maxWordsPerChunk: Int = 20,
    val extraBody: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
)

/** Returns the configured TTS service, falling back to Android System TTS and then the first service. */
fun List<SpeechService>.effectiveTtsProviderId(selectedSpeechServiceId: String?): String? =
    firstOrNull { it.id == selectedSpeechServiceId }?.id
        ?: firstOrNull { it.engine == SpeechEngine.SYSTEM }?.id
        ?: firstOrNull()?.id
