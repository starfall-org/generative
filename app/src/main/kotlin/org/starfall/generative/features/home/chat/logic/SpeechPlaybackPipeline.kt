package org.starfall.generative.features.home.chat.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/** Synthesize ahead of playback; preserve chunk order regardless of request completion order. */
internal suspend fun synthesizeAndPlaySpeechChunks(
    chunks: List<String>,
    parallelRequests: Boolean = false,
    requestSpacingMillis: Long = 1_000,
    maxConcurrentRequests: Int = 5,
    synthesize: suspend (String) -> ByteArray,
    play: suspend (ByteArray) -> Unit
) = coroutineScope {
    require(requestSpacingMillis >= 0)
    require(maxConcurrentRequests > 0)

    val audioQueue = Channel<Deferred<Result<ByteArray>>>(Channel.UNLIMITED)
    val producer = launch {
        val slots = Semaphore(if (parallelRequests) maxConcurrentRequests else 1)
        try {
            coroutineScope {
                chunks.forEachIndexed { index, chunk ->
                    slots.acquire()
                    audioQueue.send(async {
                        try {
                            Result.success(synthesize(chunk))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        } finally {
                            slots.release()
                        }
                    })
                    if (parallelRequests && index < chunks.lastIndex) delay(requestSpacingMillis)
                }
            }
        } finally {
            audioQueue.close()
        }
    }

    try {
        for (result in audioQueue) play(result.await().getOrThrow())
    } finally {
        producer.cancel()
    }
}
