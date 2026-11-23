package org.starfall.generative.integrations.speech

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.starfall.generative.shared.helpers.audio.MediaPlayers
import org.starfall.generative.shared.helpers.audio.audioMime
import java.io.File

class SpeechAudioPlayer internal constructor(
    private val context: Context,
    private val createPlayer: () -> Player
) {
    constructor(context: Context) : this(context, { MediaPlayers.create(context, speech = true) })

    private var session: PlaybackSession? = null

    internal suspend fun playSession(
        onStarted: () -> Unit = {},
        speed: Float = 1f,
        playChunks: suspend (playChunk: suspend (ByteArray) -> Unit) -> Unit
    ) = withContext(Dispatchers.Main) {
        // Resume on the main queue after Media3 returns from its listener callbacks.
        stop()
        val playback = PlaybackSession(currentCoroutineContext().job, onStarted, speed)
        session = playback
        try {
            playChunks(playback::playChunk)
        } finally {
            if (session === playback) session = null
            playback.release()
        }
    }

    private inner class PlaybackSession(
        val job: Job,
        private val onStarted: () -> Unit,
        private val speed: Float
    ) {
        private var player: Player? = null
        private var started = false

        suspend fun playChunk(audio: ByteArray) {
            var file: File? = null
            try {
                val preparedFile = withContext(Dispatchers.IO) {
                    val extension = when (audioMime(audio)) {
                        "audio/wav" -> ".wav"
                        "audio/aac" -> ".aac"
                        "audio/ogg" -> ".ogg"
                        "audio/flac" -> ".flac"
                        else -> ".mp3"
                    }
                    File.createTempFile("generative_tts_", extension, context.cacheDir).also {
                        file = it
                        it.writeBytes(audio)
                    }
                }
                val playback = player ?: createPlayer().also {
                    player = it
                    // Standalone providers apply their configured speed locally.
                    it.setPlaybackSpeed(speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f)
                }
                val completion = CompletableDeferred<Unit>()
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && !started && player === playback) {
                            started = true
                            onStarted()
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) completion.complete(Unit)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        completion.completeExceptionally(error)
                    }
                }
                playback.addListener(listener)
                try {
                    playback.setMediaItem(MediaPlayers.item(preparedFile.path))
                    playback.prepare()
                    playback.play()
                    completion.await()
                } finally {
                    if (player === playback) playback.removeListener(listener)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw SpeechPlaybackException(error)
            } finally {
                file?.delete()
            }
        }

        fun release() {
            val playback = player
            player = null
            playback?.release()
        }
    }

    fun stop() {
        val playback = session ?: return
        session = null
        playback.job.cancel()
        playback.release()
    }

    fun shutdown() = stop()
}

internal class SpeechPlaybackException(cause: Throwable) : IllegalStateException("Speech playback failed.", cause)
