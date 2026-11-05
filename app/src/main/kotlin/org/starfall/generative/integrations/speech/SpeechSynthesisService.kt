package org.starfall.generative.integrations.speech

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.shared.model.auth.requestHeaders
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.helpers.networking.requireNetworkCredentials

fun supportsSpeechProvider(provider: ModelProvider): Boolean =
    provider.type.isOpenAi || provider.type == ModelApiType.GOOGLE

fun speechVoice(provider: ModelProvider, service: SpeechService): String =
    service.voice.trim().takeUnless { it.isBlank() || it.equals("Default", true) }
        ?: if (provider.type == ModelApiType.GOOGLE) "Kore" else "alloy"

fun speechPayload(provider: ModelProvider, service: SpeechService, text: String): JsonObject {
    val model = service.modelId?.takeIf { it.isNotBlank() } ?: error("No TTS model is selected.")
    require(text.isNotBlank()) { "No text to read." }
    return buildJsonObject {
        service.extraBody.forEach { (key, value) -> put(key, value) }
        if (provider.type == ModelApiType.GOOGLE) {
            put("contents", buildJsonArray { add(buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject {
                    put("text", listOf(service.instructions.trim(), text).filter { it.isNotBlank() }.joinToString("\n\n"))
                }) })
            }) })
            val extraConfig = service.extraBody["generationConfig"] as? JsonObject
            put("generationConfig", buildJsonObject {
                extraConfig?.forEach { (key, value) -> put(key, value) }
                provider.config.modelConfigs[model]?.temperature?.let { put("temperature", it) }
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                put("speechConfig", buildJsonObject {
                    if (service.languageCode.isNotBlank()) put("languageCode", service.languageCode.trim())
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject { put("voiceName", speechVoice(provider, service)) })
                    })
                })
            })
        } else {
            require(provider.type.isOpenAi) { "This provider does not support speech synthesis." }
            require(service.responseFormat in listOf("mp3", "opus", "aac", "flac", "wav", "pcm")) { "Unsupported audio format." }
            put("model", model)
            put("input", text)
            put("voice", speechVoice(provider, service))
            put("response_format", service.responseFormat)
            put("speed", service.speed.coerceIn(0.25f, 4f))
            if (service.instructions.isNotBlank()) {
                require(model !in listOf("tts-1", "tts-1-hd")) { "This model does not support voice instructions." }
                put("instructions", service.instructions.trim())
            }
        }
    }
}

internal fun pcmWave(pcm: ByteArray, sampleRate: Int = 24_000): ByteArray {
    require(sampleRate in 8_000..192_000) { "Unsupported PCM sample rate." }
    val buffer = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
        .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
    return buffer.array()
}

class SpeechSynthesisService(
    private val providerLoader: suspend (ModelProvider) -> ModelProvider = { it }
) {
    suspend fun synthesize(provider: ModelProvider, service: SpeechService, text: String): ByteArray =
        withContext(Dispatchers.IO) {
            val resolved = if (provider.credentialState == CredentialLoadState.NOT_REQUESTED) providerLoader(provider) else provider
            check(resolved.id == provider.id) { "Credential loader returned a different speech provider." }
            resolved.requireNetworkCredentials()
            require(supportsSpeechProvider(resolved)) { "Provider ${resolved.name} does not support TTS." }
            val payload = speechPayload(resolved, service, text)
            val google = resolved.type == ModelApiType.GOOGLE
            val base = resolved.baseUrl.trimEnd('/')
            var endpoint = if (google) {
                val model = URLEncoder.encode(service.modelId!!.removePrefix("models/"), "UTF-8").replace("+", "%20")
                resolved.config.customEndpoints["generate"]?.takeIf { it.isNotBlank() }?.replace("{model}", model)?.replace("{method}", "generateContent") ?: "$base/models/$model:generateContent"
            } else resolved.config.customEndpoints["speech"]?.takeIf { it.isNotBlank() } ?: "$base/audio/speech"
            val auth = if (service.apiKey.isNotBlank()) Authorization(value = service.apiKey) else resolved.auth
            if (auth.method == AuthMethod.QUERY_PARAM && auth.token.isNotBlank()) {
                val key = URLEncoder.encode(auth.key?.ifBlank { "key" } ?: "key", "UTF-8")
                endpoint += (if (endpoint.contains('?')) "&" else "?") + key + "=" + URLEncoder.encode(auth.token, "UTF-8")
            }
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", if (google) "application/json" else "audio/*")
                auth.requestHeaders(resolved.type).forEach { (key, value) -> setRequestProperty(key, value) }
                resolved.config.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            val cancelRequest = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { connection.disconnect() }
            }
            try {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(500).orEmpty()
                    error("TTS request failed (HTTP $status)${if (detail.isBlank()) "" else ": $detail"}")
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                require(bytes.isNotEmpty()) { "TTS returned empty audio." }
                if (!google) return@withContext if (service.responseFormat == "pcm") pcmWave(bytes) else bytes
                val response = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                val parts = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                val audio = parts.mapNotNull { it.jsonObject["inlineData"] as? JsonObject }
                    .firstOrNull { it["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/") == true }
                    ?: error("Gemini returned no audio.")
                val decoded = Base64.getDecoder().decode(audio["data"]!!.jsonPrimitive.content)
                val mime = audio["mimeType"]!!.jsonPrimitive.content
                if (mime.startsWith("audio/L16", true) || mime.startsWith("audio/pcm", true)) {
                    val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toInt() ?: 24_000
                    pcmWave(decoded, rate)
                } else decoded
            } finally {
                cancelRequest.cancel()
                connection.disconnect()
            }
        }
}
