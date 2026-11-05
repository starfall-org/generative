package org.starfall.generative

import androidx.media3.common.C
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.subfeatures.media.ui.MediaPlaybackState

class MediaPlaybackStateTest {
    private class Playback {
        var status = Player.STATE_IDLE
        var duration = C.TIME_UNSET
        var position = 0L
        var wantsPlay = false
        var playing = false
        val actions = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> status
                "getDuration" -> duration
                "getCurrentPosition" -> position
                "getPlayWhenReady" -> wantsPlay
                "isPlaying" -> playing
                "seekTo" -> { position = args!![0] as Long; status = Player.STATE_READY; actions += "seek:$position"; null }
                "play" -> { wantsPlay = true; actions += "play"; null }
                "pause" -> { wantsPlay = false; actions += "pause"; null }
                else -> error("Unexpected player operation: ${method.name}")
            }
        } as Player
    }

    @Test fun unknownDurationAndBufferingStaySafeForComposeSeekControls() {
        val engine = Playback()
        val state = MediaPlaybackState(engine.player)
        engine.status = Player.STATE_BUFFERING
        state.sync()
        assertFalse(state.ready)
        assertEquals(0, state.duration)
        engine.status = Player.STATE_READY
        engine.duration = 42_000
        engine.position = 12_000
        engine.playing = true
        state.sync()
        assertTrue(state.ready)
        assertTrue(state.playing)
        assertEquals(42_000, state.duration)
        assertEquals(12_000, state.position)
    }

    @Test fun replaySeeksToStartAndErrorBlocksPlayback() {
        val engine = Playback()
        val state = MediaPlaybackState(engine.player)
        engine.status = Player.STATE_ENDED
        engine.wantsPlay = true
        state.sync()
        state.toggle()
        assertEquals(listOf("seek:0", "play"), engine.actions)
        state.toggle()
        assertEquals("pause", engine.actions.last())
        state.error = true
        val before = engine.actions.toList()
        state.toggle()
        assertEquals(before, engine.actions)
    }
}
