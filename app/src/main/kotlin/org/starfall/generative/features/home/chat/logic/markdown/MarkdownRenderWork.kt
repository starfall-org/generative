package org.starfall.generative.features.home.chat.logic.markdown

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Shared budget for visible and speculative Markdown/TeX work, including suspending library parsers. */
internal object MarkdownRenderWork {
    private val permit = Semaphore(1)

    suspend fun <T> run(work: suspend () -> T): T = permit.withPermit {
        withContext(Dispatchers.Default) { work() }
    }

    /**
     * Speculative preparation must never enqueue ahead of visible Markdown or TeX.
     * A cache miss is harmless here: the visible renderer can prepare on demand.
     */
    suspend fun <T : Any> trySpeculative(work: suspend () -> T): T? {
        if (!permit.tryAcquire()) return null
        return try {
            withContext(Dispatchers.Default) { work() }
        } finally {
            permit.release()
        }
    }
}
