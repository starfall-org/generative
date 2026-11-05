package org.starfall.generative.integrations.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.annotation.StringRes
import org.starfall.generative.R
import java.util.Locale

/** All engine access is serialized on the main thread, including asynchronous initialization. */
class TtsHelper(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private data class Request(val text: String, val speed: Float, val pitch: Float, val voice: String, val onFinished: () -> Unit, val onStarted: () -> Unit)
    private var pending: Request? = null
    private var defaultVoice: android.speech.tts.Voice? = null
    private var requestNumber = 0L
    private var activePrefix: String? = null
    private var lastUtteranceId: String? = null
    private var onFinished: (() -> Unit)? = null
    private var onStarted: (() -> Unit)? = null

    fun speak(text: String, speed: Float = 1.0f, pitch: Float = 1.0f, voice: String = "Default", onFinished: () -> Unit = {}) =
        speak(text, speed, pitch, voice, onStarted = {}, onFinished = onFinished)

    fun speak(text: String, speed: Float, pitch: Float, voice: String, onStarted: () -> Unit, onFinished: () -> Unit) {
        handler.post {
            if (closed || text.isBlank()) { onFinished(); return@post }
            pending?.onFinished?.invoke()
            pending = Request(text, speed, pitch, voice, onFinished, onStarted)
            if (ready) playPending() else if (tts == null) {
                tts = TextToSpeech(appContext) { status ->
                    // Posting also handles engines that invoke their listener inside the constructor.
                    handler.post { initialized(status) }
                }
            }
        }
    }

    private fun initialized(status: Int) {
        if (closed) return
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            pending?.onFinished?.invoke()
            pending = null
            engine.shutdown()
            tts = null
            notifyError()
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { handler.post {
                if (activePrefix?.let { utteranceId?.startsWith(it) } == true) {
                    val callback = onStarted
                    onStarted = null
                    callback?.invoke()
                }
            } }
            override fun onDone(utteranceId: String?) { handler.post {
                if (utteranceId == lastUtteranceId && utteranceId != null) finishActive()
            } }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) { handler.post {
                if (activePrefix?.let { utteranceId?.startsWith(it) } == true) {
                    notifyError(); tts?.stop(); finishActive()
                }
            } }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStop(utteranceId: String?, interrupted: Boolean) { handler.post {
                if (activePrefix?.let { utteranceId?.startsWith(it) } == true) finishActive()
            } }
        })
        // Retain the engine's installed voice if the app/device locale has no speech data.
        if (engine.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = Locale.getDefault()
        }
        ready = true
        defaultVoice = engine.voice
        playPending()
    }

    private fun playPending() {
        val request = pending ?: return
        val engine = tts ?: return
        pending = null
        finishActive()
        onFinished = request.onFinished
        onStarted = request.onStarted
        engine.setSpeechRate(request.speed.coerceIn(0.1f, 4f))
        engine.setPitch(request.pitch.coerceIn(0.1f, 4f))
        val selectedVoice = if (request.voice.equals("Default", true)) defaultVoice
            else engine.voices?.firstOrNull { it.name == request.voice }
        if (selectedVoice == null && !request.voice.equals("Default", true)) {
            reportError("Android TTS voice '${request.voice}' is not installed.")
            finishActive()
            return
        }
        selectedVoice?.let { engine.voice = it }
        // Android rejects a single utterance larger than its input limit.
        val chunks = request.text.chunked(TextToSpeech.getMaxSpeechInputLength())
        val prefix = "GenerativeTTS-${++requestNumber}-"
        activePrefix = prefix
        lastUtteranceId = "$prefix${chunks.lastIndex}"
        chunks.forEachIndexed { index, part ->
            val result = engine.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, "$prefix$index")
            if (result == TextToSpeech.ERROR) {
                engine.stop()
                notifyError()
                finishActive()
                return
            }
        }
    }

    private fun finishActive() {
        val callback = onFinished
        onFinished = null
        onStarted = null
        activePrefix = null
        lastUtteranceId = null
        callback?.invoke()
    }

    private fun notifyError() {
        Toast.makeText(appContext, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
    }

    fun reportError(message: String) {
        handler.post { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
    }

    fun reportError(@StringRes messageRes: Int) {
        handler.post { Toast.makeText(appContext, messageRes, Toast.LENGTH_LONG).show() }
    }

    fun reportError(message: String?, @StringRes fallbackRes: Int) {
        handler.post {
            Toast.makeText(appContext, message ?: appContext.getString(fallbackRes), Toast.LENGTH_LONG).show()
        }
    }

    fun stop() {
        handler.post { pending?.onFinished?.invoke(); pending = null; tts?.stop(); finishActive() }
    }

    fun shutdown() {
        handler.post {
            closed = true
            pending?.onFinished?.invoke()
            pending = null
            ready = false
            tts?.stop()
            tts?.shutdown()
            tts = null
            finishActive()
        }
    }
}
