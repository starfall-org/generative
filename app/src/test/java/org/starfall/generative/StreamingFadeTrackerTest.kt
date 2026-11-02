package org.starfall.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.shared.ui.text.StreamingFadeRange
import org.starfall.generative.shared.ui.text.STREAM_FADE_DURATION_MS
import org.starfall.generative.shared.ui.text.StreamingFadeTracker
import org.starfall.generative.shared.ui.text.firstFadingRange
import org.starfall.generative.shared.ui.text.shouldRevealStreamingText

class StreamingFadeTrackerTest {
    @Test
    fun appendedChunkDoesNotRestartPreviousFade() {
        val tracker = StreamingFadeTracker()
        tracker.update("Hello", 0)
        assertEquals(
            listOf(StreamingFadeRange(0, 5, 0), StreamingFadeRange(5, 11, 64)),
            tracker.update("Hello world", 64)
        )
        assertEquals(listOf(StreamingFadeRange(11, 12, 400)), tracker.update("Hello world!", 400))
    }

    @Test
    fun markdownFormattingRewritePreservesUnchangedPrefix() {
        val tracker = StreamingFadeTracker()
        tracker.update("Hello **wor", 0)
        assertEquals(
            listOf(StreamingFadeRange(0, 6, 0), StreamingFadeRange(6, 11, 64)),
            tracker.update("Hello world", 64)
        )
    }

    @Test
    fun shortenedAndEmptyTextNeverLeaveOutOfBoundsRanges() {
        val tracker = StreamingFadeTracker()
        tracker.update("Hello world", 0)
        assertEquals(listOf(StreamingFadeRange(0, 5, 0)), tracker.update("Hello", 64))
        assertTrue(tracker.update("", 128).isEmpty())
    }

    @Test
    fun reducedMotionSettingDisablesOnlyTheVisualReveal() {
        assertTrue(shouldRevealStreamingText(localEnabled = true, systemAnimationsEnabled = true))
        assertTrue(!shouldRevealStreamingText(localEnabled = false, systemAnimationsEnabled = true))
        assertTrue(!shouldRevealStreamingText(localEnabled = true, systemAnimationsEnabled = false))
    }

    @Test
    fun eachChunkUnveilsQuicklyAndDoesNotKeepOldFadesRunning() {
        val tracker = StreamingFadeTracker()
        assertTrue("The reveal must finish before another slow token batch", STREAM_FADE_DURATION_MS <= 160L)
        tracker.update("First", 0)
        tracker.update("First second", 65)
        assertEquals(
            listOf(StreamingFadeRange(5, 12, 65), StreamingFadeRange(12, 18, 140)),
            tracker.update("First second third", 140)
        )
    }

    @Test
    fun finishedChunksAreDiscardedWithoutNewAnimations() {
        val tracker = StreamingFadeTracker()
        tracker.update("Hello", 0)
        assertTrue(tracker.update("Hello", 300).isEmpty())
    }

    @Test
    fun fadingRangesAreAlwaysASuffixOfTheTimeOrderedList() {
        val d = STREAM_FADE_DURATION_MS
        val ranges = listOf(
            StreamingFadeRange(0, 5, 0),
            StreamingFadeRange(5, 9, d / 2),
            StreamingFadeRange(9, 12, d)
        )
        assertEquals(0, firstFadingRange(ranges, ranges.size, d - 1))
        assertEquals(1, firstFadingRange(ranges, ranges.size, d + 1))
        assertEquals(2, firstFadingRange(ranges, ranges.size, d / 2 + d))
        assertEquals(-1, firstFadingRange(ranges, ranges.size, 2 * d))
        assertEquals(-1, firstFadingRange(emptyList(), 0, 0))
        // Ranges without a measured path yet are never reported.
        assertEquals(-1, firstFadingRange(ranges, 0, 0))
    }
}
