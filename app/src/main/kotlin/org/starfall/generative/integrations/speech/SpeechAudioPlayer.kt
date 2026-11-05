package org.starfall.generative.integrations.speech

import org.starfall.generative.shared.helpers.audio.audioMime
import org.starfall.generative.shared.helpers.audio.MediaPlayers

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SpeechAudioPlayer(private val context: Context) {
    private var player: ExoPlayer? = null
    private var currentFile: File? = null

    suspend fun play(
        audio: ByteArray,
        onFinished: () -> Unit = {},
        onError: () -> Unit = {},
        onStarted: () -> Unit = {},
        speed: Float = 1f
    ) =
        withContext(Dispatchers.Main.immediate) {
            stop()
            var file: File? = null
            var ownedPlayer: ExoPlayer? = null
            try {
                withContext(Dispatchers.IO) {
                    val extension = when (audioMime(audio)) {
                        "audio/wav" -> ".wav"; "audio/aac" -> ".aac"; "audio/ogg" -> ".ogg"; "audio/flac" -> ".flac"; else -> ".mp3"
                    }
                    file = File.createTempFile("generative_tts_", extension, context.cacheDir)
                    file!!.writeBytes(audio)
                }
                val preparedFile = file!!
                val next = MediaPlayers.create(context, speech = true)
                ownedPlayer = next
                currentFile = preparedFile
                player = next
                fun finish(error: Boolean) {
                    if (player !== next) return
                    stop()
                    if (error) onError()
                    onFinished()
                }
                var started = false
                next.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && !started && player === next) { started = true; onStarted() }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) finish(false)
                    }
                    override fun onPlayerError(error: PlaybackException) = finish(true)
                })
                next.setMediaItem(MediaPlayers.item(preparedFile.path))
                // Standalone speech providers do not share a common API speed parameter.
                // Apply their configured speed at playback without affecting pitch.
                next.setPlaybackSpeed(speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f)
                next.prepare()
                next.play()
            } catch (error: Throwable) {
                // A cancelled file write must not stop a newer speech request.
                if (ownedPlayer != null && player === ownedPlayer) stop()
                file?.delete()
                throw error
            }
        }

    /** Wait for this clip before synthesizing the next one; cancellation only stops its own player. */
    suspend fun playAndAwait(audio: ByteArray, onStarted: () -> Unit = {}, speed: Float = 1f) = withContext(Dispatchers.Main.immediate) {
        val completion = CompletableDeferred<Unit>()
        play(audio,
            onFinished = { completion.complete(Unit) },
            onError = { completion.completeExceptionally(SpeechPlaybackException()) },
            onStarted = onStarted,
            speed = speed)
        val ownedPlayer = player
        try { completion.await() }
        finally { if (ownedPlayer != null && player === ownedPlayer) stop() }
    }

    fun stop() {
        val previous = player
        player = null
        previous?.release()
        currentFile?.delete()
        currentFile = null
    }

    fun shutdown() = stop()
}

internal class SpeechPlaybackException : IllegalStateException("Speech playback failed.")
