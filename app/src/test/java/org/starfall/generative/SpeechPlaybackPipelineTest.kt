package org.starfall.generative

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.features.home.chat.logic.synthesizeAndPlaySpeechChunks

class SpeechPlaybackPipelineTest {
    @Test fun nextRequestStartsBeforePreviousClipFinishesPlaying() = runBlocking {
        val playbackStarted = CompletableDeferred<Unit>()
        val finishFirstClip = CompletableDeferred<Unit>()
        val lastSynthesisStarted = CompletableDeferred<Unit>()
        val played = mutableListOf<String>()

        val job = launch {
            synthesizeAndPlaySpeechChunks(listOf("first", "second", "third", "fourth", "fifth"),
                synthesize = { chunk ->
                    if (chunk == "fifth") lastSynthesisStarted.complete(Unit)
                    chunk.toByteArray()
                },
                play = { audio ->
                    val chunk = audio.decodeToString()
                    if (chunk == "first") {
                        playbackStarted.complete(Unit)
                        finishFirstClip.await()
                    }
                    played.add(chunk)
                })
        }

        withTimeout(3_000) {
            playbackStarted.await()
            lastSynthesisStarted.await()
        }
        assertTrue(played.isEmpty()) // First clip is still playing.
        finishFirstClip.complete(Unit)
        withTimeout(3_000) { job.join() }
        assertEquals(listOf("first", "second", "third", "fourth", "fifth"), played)
    }

    @Test fun cancelingPlaybackAlsoCancelsTheInFlightNextRequest() = runBlocking {
        val playbackStarted = CompletableDeferred<Unit>()
        val secondSynthesisStarted = CompletableDeferred<Unit>()
        val synthesisCancelled = CompletableDeferred<Unit>()

        val job = launch {
            synthesizeAndPlaySpeechChunks(listOf("first", "second"),
                synthesize = { chunk ->
                    if (chunk == "second") {
                        secondSynthesisStarted.complete(Unit)
                        try { awaitCancellation() } finally { synthesisCancelled.complete(Unit) }
                    }
                    chunk.toByteArray()
                },
                play = {
                    playbackStarted.complete(Unit)
                    awaitCancellation()
                })
        }

        withTimeout(3_000) {
            playbackStarted.await()
            secondSynthesisStarted.await()
        }
        job.cancelAndJoin()
        withTimeout(3_000) { synthesisCancelled.await() }
    }
}
