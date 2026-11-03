package org.starfall.generative

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderWork

class MarkdownRenderWorkSpeculationTest {
    @Test fun offscreenParsingDoesNotWaitForVisibleWorkAndCanRetryLater() = runBlocking {
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val visible = launch(Dispatchers.Default) {
            MarkdownRenderWork.run {
                acquired.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(3_000) { acquired.await() }
            val skipped = withTimeout(750) { MarkdownRenderWork.trySpeculative { 10 } }
            assertNull("Speculation must not wait in the visible rendering queue", skipped)
        } finally {
            release.complete(Unit)
            withTimeout(3_000) { visible.join() }
        }
        assertEquals(11, withTimeout(3_000) { MarkdownRenderWork.trySpeculative { 11 } })
    }
}
