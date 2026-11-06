package org.starfall.generative

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
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

    @Test fun synthesisFailureIsReportedAfterAlreadyFetchedAudioFinishes() = runBlocking {
        val firstPlaybackStarted = CompletableDeferred<Unit>()
        val releaseFirstClip = CompletableDeferred<Unit>()
        val failedSynthesisStarted = CompletableDeferred<Unit>()
        val played = mutableListOf<String>()
        val failure = IllegalStateException("HTTP 429 from TTS provider")
        var reported: Throwable? = null

        val job = launch {
            reported = runCatching {
                synthesizeAndPlaySpeechChunks(listOf("first", "second", "third"),
                    synthesize = { chunk ->
                        if (chunk == "third") {
                            failedSynthesisStarted.complete(Unit)
                            throw failure
                        }
                        chunk.toByteArray()
                    },
                    play = { audio ->
                        if (audio.decodeToString() == "first") {
                            firstPlaybackStarted.complete(Unit)
                            releaseFirstClip.await()
                        }
                        played.add(audio.decodeToString())
                    })
            }.exceptionOrNull()
        }

        withTimeout(3_000) {
            firstPlaybackStarted.await()
            failedSynthesisStarted.await()
        }
        assertTrue(played.isEmpty()) // An API failure must not interrupt the current clip.
        releaseFirstClip.complete(Unit)
        withTimeout(3_000) { job.join() }
        assertEquals(listOf("first", "second"), played)
        assertSame(failure, reported)
    }

    @Test fun parallelRequestsAreBoundedAndPlayInOrder() = runBlocking {
        val started = Channel<String>(Channel.UNLIMITED)
        val clips = (1..4).map { "chunk$it" }
        val gates = clips.associateWith { CompletableDeferred<Unit>() }
        val played = mutableListOf<String>()
        var active = 0
        var peak = 0
        val job = launch {
            synthesizeAndPlaySpeechChunks(clips,
                parallelRequests = true, requestSpacingMillis = 0, maxConcurrentRequests = 2,
                synthesize = {
                    active++
                    peak = maxOf(peak, active)
                    started.send(it)
                    try {
                        gates.getValue(it).await()
                        it.toByteArray()
                    } finally {
                        active--
                    }
                },
                play = { played.add(it.decodeToString()) })
        }

        withTimeout(3_000) {
            assertEquals("chunk1", started.receive())
            assertEquals("chunk2", started.receive())
        }
        assertNull(withTimeoutOrNull(100) { started.receive() })
        gates.getValue("chunk2").complete(Unit)
        withTimeout(3_000) { assertEquals("chunk3", started.receive()) }
        gates.getValue("chunk3").complete(Unit)
        withTimeout(3_000) { assertEquals("chunk4", started.receive()) }
        gates.getValue("chunk4").complete(Unit)
        assertTrue(played.isEmpty()) // All later results are buffered behind chunk1.
        gates.getValue("chunk1").complete(Unit)
        withTimeout(3_000) { job.join() }
        assertEquals(2, peak)
        assertEquals(clips, played)
    }

    @Test fun parallelRequestsRespectStartSpacing() = runBlocking {
        val started = mutableListOf<Long>()
        withTimeout(3_000) {
            synthesizeAndPlaySpeechChunks(listOf("one", "two", "three"),
                parallelRequests = true, requestSpacingMillis = 150, maxConcurrentRequests = 5,
                synthesize = {
                    started.add(System.nanoTime())
                    it.toByteArray()
                },
                play = {})
        }
        assertEquals(3, started.size)
        assertTrue(started.zipWithNext().all { (a, b) -> b - a >= 120_000_000L })
    }

    @Test fun disabledParallelRequestsIgnoreSpacingAndRemainSequential() = runBlocking {
        var active = 0
        var peak = 0
        withTimeout(3_000) {
            synthesizeAndPlaySpeechChunks(listOf("one", "two", "three"),
                parallelRequests = false, requestSpacingMillis = 60_000, maxConcurrentRequests = 5,
                synthesize = {
                    active++
                    peak = maxOf(peak, active)
                    kotlinx.coroutines.delay(15)
                    active--
                    it.toByteArray()
                },
                play = {})
        }
        assertEquals(1, peak)
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
