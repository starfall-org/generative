package org.starfall.generative.features.home.chat.logic

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.starfall.generative.R
import org.starfall.generative.integrations.speech.SpeechAudioPlayer
import org.starfall.generative.integrations.speech.SpeechPlaybackNotification
import org.starfall.generative.integrations.speech.SpeechSynthesisService
import org.starfall.generative.integrations.speech.TtsHelper
import org.starfall.generative.shared.helpers.speech.speechText
import org.starfall.generative.shared.helpers.speech.speechChunks
import org.starfall.generative.integrations.speech.SpeechPlaybackException
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.integrations.speech.StandaloneSpeechClient
import org.starfall.generative.shared.model.effectiveTtsProviderId
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.database.preferences.AppPreferences

internal class ChatSpeechCoordinator(
    private val scope: CoroutineScope,
    private val preferences: StateFlow<AppPreferences>,
    private val providers: StateFlow<List<ModelProvider>>,
    private val services: StateFlow<List<SpeechService>>,
    private val tts: TtsHelper,
    private val synthesis: SpeechSynthesisService,
    private val player: SpeechAudioPlayer,
    private val notification: SpeechPlaybackNotification? = null
) {
    private var job: Job? = null
    private var requestNumber = 0L
    private val activeMessage = MutableStateFlow<String?>(null)
    val speakingMessageId = activeMessage.asStateFlow()
    private val activeTestService = MutableStateFlow<String?>(null)
    val activeTestServiceId = activeTestService.asStateFlow()

    fun speakWithService(service: SpeechService, text: String, messageId: String? = null, isTest: Boolean = false) {
        val plainText = speechText(text, includeCodeBlocks = preferences.value.ttsReadCodeBlocks)
        if (plainText.isBlank()) return
        stop()
        val request = requestNumber
        activeMessage.value = messageId
        if (isTest) activeTestService.value = service.id
        val onFinished = {
            if (request == requestNumber) {
                activeMessage.value = null
                activeTestService.value = null
                notification?.hide()
            }
        }
        val onStarted = {
            if (request == requestNumber) notification?.show(service.name) { stop() }
            Unit
        }
        if (service.engine == SpeechEngine.SYSTEM) {
            tts.speak(plainText, service.speed, service.pitch, service.voice, onStarted = onStarted, onFinished = onFinished)
            return
        }
        job = scope.launch {
            try {
                val provider = if (service.engine.standalone) null else providers.value.find { it.id == service.provider }
                if (!service.engine.standalone) {
                    if (provider == null) {
                        tts.reportError(R.string.speech_provider_missing)
                        return@launch
                    }
                    val modelId = service.modelId
                    if (modelId == null) {
                        tts.reportError(R.string.speech_model_missing)
                        return@launch
                    }
                    if (provider.config.modelConfigs[modelId]?.modelType != ModelType.TEXT_TO_SPEECH ||
                        provider.config.modelIds?.contains(modelId) == false) {
                        tts.reportError(R.string.speech_model_unavailable)
                        return@launch
                    }
                }
                val standalone = if (service.engine.standalone) StandaloneSpeechClient() else null
                val chunks = speechChunks(plainText, maxWordsPerChunk = service.maxWordsPerChunk.coerceAtLeast(1),
                    maxCharacters = if (service.engine == SpeechEngine.SPEECHIFY) 1_900 else 4_000)
                synthesizeAndPlaySpeechChunks(
                    chunks = chunks,
                    synthesize = { chunk ->
                        if (standalone != null) standalone.synthesize(service, chunk)
                        else synthesis.synthesize(requireNotNull(provider), service, chunk)
                    },
                    play = { audio ->
                        ensureActive()
                        if (request != requestNumber) throw CancellationException("Speech request was replaced")
                        player.playAndAwait(audio, onStarted,
                            speed = if (standalone != null) service.speed else 1f)
                    }
                )
            } catch (e: CancellationException) { throw e
            } catch (e: SpeechPlaybackException) {
                tts.reportError(R.string.speech_playback_failed)
            } catch (e: Exception) {
                tts.reportError(e.localizedMessage, R.string.speech_synthesis_failed)
            } finally { onFinished() }
        }
    }

    fun speak(text: String, messageId: String? = null) {
        if (messageId != null && activeMessage.value == messageId) { stop(); return }
        val available = services.value
        val selectedId = available.effectiveTtsProviderId(preferences.value.selectedSpeechServiceId)
        val service = selectedId?.let { id -> available.find { it.id == id } }
            ?: return
        speakWithService(service, text, messageId)
    }

    fun stop() {
        requestNumber++
        notification?.hide()
        activeMessage.value = null
        activeTestService.value = null
        job?.cancel()
        job = null
        tts.stop()
        player.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
        player.shutdown()
    }
}
