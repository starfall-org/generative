package org.starfall.generative.features.home.chat.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.starfall.generative.features.home.chat.model.StreamTextSnapshot

class StreamResponseTextSplitterTest {
    @Test
    fun ordinaryTextIsReturnedExactly() {
        val result = parse("Xin chào, thế giới!\n")
        assertEquals("Xin chào, thế giới!\n", result.content)
        assertEquals("", result.reasoning)
    }

    @Test
    fun thinkWrapperSeparatesReasoningAndTrimsOnlyTheVisiblePrefix() {
        val result = parse("<think>Planning the answer</think>\n  The answer")
        assertEquals("Planning the answer", result.reasoning)
        assertEquals("The answer", result.content)
    }

    @Test
    fun markerSplitsAcrossEveryPossiblePairOfFragments() {
        val input = "<think>reasoning\nline</think>\r\nvisible\ntext"
        val expected = StreamResponseText("visible\ntext", "reasoning\nline")

        for (split in 0..input.length) {
            assertEquals("split at $split", expected, parseWithSplit(input, split))
        }
    }

    @Test
    fun incompleteOpeningTagRemainsLiteralVisibleText() {
        assertEquals(StreamResponseText("<thi", ""), parse("<thi"))
    }

    @Test
    fun unfinishedReasoningAndPartialClosingTagAreRetainedAtFinalization() {
        assertEquals(StreamResponseText("", "secret</thi"), parse("<think>secret</thi"))
    }

    @Test
    fun resumedContentDoesNotTreatAContinuationAsANewThinkWrapper() {
        val splitter = StreamResponseTextSplitter(
            initialContentLength = "Existing".length,
            alreadyInsideVisibleContent = true
        )
        val appended = splitter.append(" <think>literal")
        val final = splitter.finish()

        assertEquals(" <think>literal", appended.content + final.content)
        assertEquals("", appended.reasoning + final.reasoning)
        assertEquals("Existing".length + " <think>literal".length, splitter.visibleContentLength)
    }

    @Test
    fun noExistingVisibleContentStillAllowsTheThinkPrefixProbeOnResume() {
        val splitter = StreamResponseTextSplitter(alreadyInsideVisibleContent = false)
        val appended = splitter.append("<think>reason</think>Answer")
        val final = splitter.finish()

        assertEquals("Answer", appended.content + final.content)
        assertEquals("reason", appended.reasoning + final.reasoning)
    }

    @Test
    fun inlineReasoningOffsetMatchesTrimmedPersistedTextAcrossFragments() {
        val splitter = StreamResponseTextSplitter()

        splitter.append("<think>  reason \n")
        assertEquals("reason".length, splitter.visibleReasoningLength)

        splitter.append(" continued  ")
        assertEquals("reason \n continued".length, splitter.visibleReasoningLength)
    }

    @Test
    fun streamTextSnapshotExposesNewSegmentsWithoutMaterializingTheWholeValue() {
        val initial = StreamTextSnapshot.of("base")
        val first = initial.append("a", sequence = 1L)
        val second = first.append("b", sequence = 2L)

        assertEquals(listOf("a", "b"), second.segmentsAfter(0L).map { it.text })
        assertEquals(listOf("b"), second.segmentsAfter(1L).map { it.text })
        assertEquals("baseab", second.materialize())
        assertEquals("basea", second.prefix(5))
        assertEquals("baseab", second.prefix(50))
    }

    @Test
    fun streamTextSnapshotSuffixViewKeepsQueuedSegmentsInOrderWithoutCopyingTheWholeSource() {
        val initial = StreamTextSnapshot.of("base-")
        val first = initial.append("abcdef", sequence = 1L)
        val second = first.append("ghij", sequence = 2L)

        val tail = second.suffixFrom(8)
        assertEquals("defghij", tail.materialize())
        assertEquals(7L, tail.length)
        assertEquals(listOf("def", "ghij"), tail.segmentsAfter(0L).map { it.text })
        assertEquals("defgh", tail.prefix(5))
        assertEquals("", tail.slice(tail.length.toInt(), tail.length.toInt() + 10))

        val baseTail = second.suffixFrom(2)
        assertEquals("se-abcdefghij", baseTail.materialize())
        val middleSegmentTail = second.suffixFrom(6)
        assertEquals("bcdefghij", middleSegmentTail.materialize())
        assertEquals(listOf("ghij"), middleSegmentTail.segmentsAfter(1L).map { it.text })
    }

    @Test
    fun streamTextSnapshotSlicesAcrossBaseAndManyImmutableSegments() {
        var snapshot = StreamTextSnapshot.of("base-")
        val expected = StringBuilder("base-")
        repeat(160) { index ->
            val segment = "[$index]"
            snapshot = snapshot.append(segment, sequence = index + 1L)
            expected.append(segment)
        }
        val source = expected.toString()

        for (start in 0..source.length step 13) {
            val end = (start + 37).coerceAtMost(source.length)
            assertEquals(source.substring(start, end), snapshot.slice(start, end))
        }
        assertEquals("", snapshot.slice(source.length, source.length + 20))
        assertEquals(source, snapshot.materialize())
    }

    @Test
    fun completedTextExtendsSharedStreamSegmentsWithoutChangingExistingSnapshot() {
        val original = StreamTextSnapshot.of("base-").append("chunk", 3L)
        assertSame(original, original.extendedTo("base-chunk"))
        val completed = original.extendedTo("base-chunk and final suffix")!!
        assertEquals("base-chunk and final suffix", completed.materialize())
        assertEquals("base-chunk", original.materialize())
        assertEquals(listOf(" and final suffix"), completed.segmentsAfter(3L).map { it.text })
        assertEquals(" and final suffix", completed.suffixFrom(original.length.toInt()).materialize())
    }

    @Test
    fun completedTextRejectsChangedOrShortenedSourceRegardlessOfMatchingBoundaries() {
        val original = StreamTextSnapshot.of("A".repeat(90) + "before" + "Z".repeat(90))
        assertNull(original.extendedTo("A".repeat(90) + "after" + "Z".repeat(90) + " extra"))
        assertNull(original.extendedTo("short"))
        assertNull(original.suffixFrom(1).extendedTo(original.materialize()))
    }

    private fun parse(input: String): StreamResponseText {
        val splitter = StreamResponseTextSplitter()
        val appended = splitter.append(input)
        val final = splitter.finish()
        return StreamResponseText(appended.content + final.content, appended.reasoning + final.reasoning)
    }

    private fun parseWithSplit(input: String, split: Int): StreamResponseText {
        val splitter = StreamResponseTextSplitter()
        val first = splitter.append(input.substring(0, split))
        val second = splitter.append(input.substring(split))
        val final = splitter.finish()
        return StreamResponseText(
            first.content + second.content + final.content,
            first.reasoning + second.reasoning + final.reasoning
        )
    }
}
