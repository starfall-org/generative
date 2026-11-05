package org.starfall.generative.features.home.chat.logic

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Synthesize sequentially, immediately starting the next network request when the
 * previous synthesis completes. Playback consumes completed clips in order, without
 * making synthesis wait for playback. Requests are never gated on queued playback.
 */
internal suspend fun synthesizeAndPlaySpeechChunks(
    chunks: List<String>,
    synthesize: suspend (String) -> ByteArray,
    play: suspend (ByteArray) -> Unit
) = coroutineScope {
    val audioQueue = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    val producer = launch {
        try {
            for (chunk in chunks) audioQueue.send(synthesize(chunk))
        } finally {
            audioQueue.close()
        }
    }
    try {
        for (clip in audioQueue) play(clip)
    } finally {
        producer.cancel()
    }
}
