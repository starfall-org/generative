package org.starfall.generative

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.logic.synthesizeAndPlaySpeechChunks
import org.starfall.generative.integrations.speech.SpeechAudioPlayer
import org.starfall.generative.integrations.speech.SpeechPlaybackException
import org.starfall.generative.integrations.speech.pcmWave

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SpeechAudioPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun cancelPlayback() {
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun externalChunksPlayInOrderOnOnePlayerAndReleaseAfterTheLastClip() {
        val engine = TestPlayer()
        var playersCreated = 0
        var timesStarted = 0
        val player = SpeechAudioPlayer(ApplicationProvider.getApplicationContext()) {
            playersCreated++
            engine.player
        }
        val request = scope.async {
            player.playSession(onStarted = { timesStarted++ }, speed = 1.25f) { playClip ->
                synthesizeAndPlaySpeechChunks(
                    chunks = listOf("1", "2"),
                    synthesize = { pcmWave(byteArrayOf(it.toByte(), 0)) },
                    play = playClip
                )
            }
        }

        awaitMain { engine.samples.size == 1 || request.isCompleted }
        assertEquals(listOf(1), engine.samples)
        engine.listener!!.onIsPlayingChanged(true)
        engine.listener!!.onPlaybackStateChanged(Player.STATE_ENDED)

        awaitMain { engine.samples.size == 2 || request.isCompleted }
        assertEquals(listOf(1, 2), engine.samples)
        assertEquals(1, playersCreated)
        assertEquals(0, engine.releases)
        engine.listener!!.onIsPlayingChanged(true)
        engine.listener!!.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(0, engine.releases)

        awaitMain { request.isCompleted }
        runBlocking { request.await() }
        assertEquals(1, timesStarted)
        assertEquals(listOf(1.25f), engine.speeds)
        assertEquals(1, engine.releases)
        assertTrue(engine.files.none(File::exists))
    }

    @Test fun stopCancelsTheOldPlaybackAndItsCallbackCannotAdvanceTheNextSession() {
        val old = TestPlayer()
        val next = TestPlayer()
        val engines = listOf(old, next).iterator()
        val player = SpeechAudioPlayer(ApplicationProvider.getApplicationContext()) { engines.next().player }
        val oldRequest = scope.async { player.playSession { it(pcmWave(byteArrayOf(1, 0))) } }

        awaitMain { old.samples.isNotEmpty() || oldRequest.isCompleted }
        val oldListener = checkNotNull(old.listener)
        player.stop()

        val nextRequest = scope.async { player.playSession { it(pcmWave(byteArrayOf(2, 0))) } }
        awaitMain { oldRequest.isCompleted && (next.samples.isNotEmpty() || nextRequest.isCompleted) }
        assertTrue(oldRequest.isCancelled)
        assertEquals(1, old.releases)
        assertTrue(old.files.none(File::exists))
        assertEquals(listOf(2), next.samples)

        oldListener.onPlaybackStateChanged(Player.STATE_ENDED)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(nextRequest.isCompleted)
        next.listener!!.onPlaybackStateChanged(Player.STATE_ENDED)
        awaitMain { nextRequest.isCompleted }
        runBlocking { nextRequest.await() }
        assertEquals(1, next.releases)
    }

    @Test fun mediaErrorEndsTheSessionAsAPlaybackFailure() {
        val engine = TestPlayer()
        val player = SpeechAudioPlayer(ApplicationProvider.getApplicationContext()) { engine.player }
        val request = scope.async {
            runCatching { player.playSession { it(pcmWave(byteArrayOf(1, 0))) } }.exceptionOrNull()
        }

        awaitMain { engine.samples.isNotEmpty() || request.isCompleted }
        engine.listener!!.onPlayerError(PlaybackException("Audio error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))

        awaitMain { request.isCompleted }
        val failure = runBlocking { request.await() }
        assertTrue(failure is SpeechPlaybackException)
        val mediaError = generateSequence(failure) { it.cause }.filterIsInstance<PlaybackException>().firstOrNull()
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, mediaError?.errorCode)
        assertEquals(1, engine.releases)
        assertTrue(engine.files.none(File::exists))
    }

    private fun awaitMain(condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            looper.idle()
            Thread.sleep(5)
        }
        assertTrue("Playback did not advance", condition())
    }

    private class TestPlayer {
        var listener: Player.Listener? = null
        var releases = 0
        val files = mutableListOf<File>()
        val samples = mutableListOf<Int>()
        val speeds = mutableListOf<Float>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> listener = args!![0] as Player.Listener
                "removeListener" -> if (listener === args!![0]) listener = null
                "setMediaItem" -> {
                    val item = args!![0] as MediaItem
                    val file = File(checkNotNull(item.localConfiguration?.uri?.path))
                    files += file
                    samples += file.readBytes()[44].toInt()
                }
                "setPlaybackSpeed" -> speeds += args!![0] as Float
                "prepare", "play" -> Unit
                "release" -> {
                    releases++
                    listener = null
                }
                else -> error("Unexpected player operation: ${method.name}")
            }
            null
        } as Player
    }
}
