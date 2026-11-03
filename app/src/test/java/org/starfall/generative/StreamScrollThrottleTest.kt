package org.starfall.generative

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.features.home.chat.ui.markdown.SCROLL_STREAM_APPLY_INTERVAL_MS
import org.starfall.generative.features.home.chat.ui.markdown.awaitStreamApplySlot
import org.starfall.generative.features.home.chat.ui.markdown.awaitOverflowQuietPeriod

class StreamScrollThrottleTest {
    private fun elapsedMs(block: suspend () -> Unit): Long {
        val start = System.nanoTime()
        runBlocking { block() }
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test fun idleListAppliesStreamedTextImmediately() {
        val scrolling = mutableStateOf(false)
        val elapsed = elapsedMs { awaitStreamApplySlot(scrolling, intervalMs = 10_000L) }
        assertTrue("Not scrolling must not wait (took ${elapsed}ms)", elapsed < 5_000L)
    }

    @Test fun scrollingListSpacesOutStreamApplications() {
        val scrolling = mutableStateOf(true)
        val elapsed = elapsedMs { awaitStreamApplySlot(scrolling, intervalMs = 60L) }
        assertTrue("Scrolling must wait the interval (took ${elapsed}ms)", elapsed >= 55L)
    }

    @Test fun stoppingScrollFlushesWaitingTextWithoutTheFullThrottleDelay() {
        val scrolling = mutableStateOf(true)
        val elapsed = elapsedMs {
            coroutineScope {
                val pending = async { awaitStreamApplySlot(scrolling, intervalMs = 1_500L) }
                delay(40L)
                // Simulate a committed Compose state update, as the UI runtime does.
                Snapshot.withMutableSnapshot { scrolling.value = false }
                pending.await()
            }
        }
        assertTrue("Scroll end must release the pending update promptly (took ${elapsed}ms)", elapsed < 1_000L)
    }

    @Test fun scrollIntervalStaysShortEnoughToKeepTextMoving() {
        assertTrue(SCROLL_STREAM_APPLY_INTERVAL_MS in 50L..250L)
    }
    @Test fun pathologicalOverflowWaitsForTheFullQuietInterval() {
        val scrolling = mutableStateOf(false)
        val elapsed = elapsedMs {
            awaitOverflowQuietPeriod(scrolling, quietMillis = 90L)
        }
        assertTrue("Full-text layout must not start immediately (took " + elapsed + "ms)", elapsed >= 75L)
    }

    @Test fun repeatedShortSwipesDoNotTriggerOverflowRelayoutInBetween() {
        val scrolling = mutableStateOf(true)
        val elapsed = elapsedMs {
            coroutineScope {
                val pending = async { awaitOverflowQuietPeriod(scrolling, quietMillis = 140L) }
                delay(30)
                Snapshot.withMutableSnapshot { scrolling.value = false }
                delay(50) // A short pause; still not safe to lay out huge Text.
                Snapshot.withMutableSnapshot { scrolling.value = true }
                delay(30)
                Snapshot.withMutableSnapshot { scrolling.value = false }
                pending.await()
            }
        }
        assertTrue("Relayout must wait until after the last swipe (took " + elapsed + "ms)", elapsed >= 245L)
    }

}
