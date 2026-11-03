package org.starfall.generative.features.home.chat.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of a message currently being generated. */
enum class ChatStreamStatus {
    STREAMING,
    COMPLETED,
    CANCELLED,
    FAILED
}

/** Metrics for the most recent visible commit; these contain no response text. */
data class StreamCommitMetrics(
    val characterCount: Int,
    val queuedCharacters: Int,
    val chunkWaitMillis: Long,
    /** Time to the state-publication call; actual on-screen latency still needs frame instrumentation. */
    val inputToVisibleLatencyMillis: Long,
    val terminal: Boolean
)

data class StreamMetricsSnapshot(
    val batchCount: Long = 0L,
    val committedCharacters: Long = 0L,
    val maxQueuedCharacters: Int = 0,
    val totalChunkWaitMillis: Long = 0L,
    val totalInputToVisibleLatencyMillis: Long = 0L
)

/** A persistent, append-only link to one committed display segment. Equality is referential on purpose. */
internal class StreamTextSegment(
    val sequence: Long,
    val ordinal: Long,
    val startOffset: Long,
    val endOffset: Long,
    val text: String,
    val previous: StreamTextSegment?,
    val skipPrevious: StreamTextSegment?
)

/**
 * Immutable handle to the display text for one message. Appending adds one small linked segment instead
 * of copying the full accumulated response; [materialize] is reserved for the active UI renderer.
 */
internal class StreamTextSnapshot private constructor(
    private val sourceBaseText: String,
    val latestSegment: StreamTextSegment?,
    private val sourceLength: Long,
    private val segmentCount: Long,
    private val viewStartOffset: Long = 0L
) {
    /** Source prefix visible through this snapshot view, without materializing streamed segments. */
    val baseText: String
        get() {
            val baseEnd = minOf(sourceLength, sourceBaseText.length.toLong())
            val visibleStart = viewStartOffset.coerceAtMost(baseEnd).toInt()
            return sourceBaseText.substring(visibleStart, baseEnd.toInt())
        }

    /** Length relative to this view; the underlying append-only source remains shared. */
    val length: Long get() = (sourceLength - viewStartOffset).coerceAtLeast(0L)

    fun append(text: String, sequence: Long): StreamTextSnapshot {
        require(viewStartOffset == 0L) { "Cannot append to a suffix view of a stream snapshot" }
        if (text.isEmpty()) return this
        val ordinal = segmentCount + 1L
        val skipPrevious = if ((ordinal - 1L) % SEGMENT_SKIP_DISTANCE == 0L) {
            var ancestor = latestSegment
            repeat((SEGMENT_SKIP_DISTANCE - 1L).toInt()) { ancestor = ancestor?.previous }
            ancestor
        } else null
        val segment = StreamTextSegment(
            sequence = sequence,
            ordinal = ordinal,
            startOffset = sourceLength,
            endOffset = sourceLength + text.length,
            text = text,
            previous = latestSegment,
            skipPrevious = skipPrevious
        )
        return StreamTextSnapshot(
            sourceBaseText = sourceBaseText,
            latestSegment = segment,
            sourceLength = sourceLength + text.length.toLong(),
            segmentCount = ordinal
        )
    }

    /**
     * Reconcile a streamed prefix with the authoritative completed message. Reuse the exact
     * append-only segments only when every previously observed character still matches.
     * A changed/truncated response must be rendered from the completed source instead.
     * This is called at handoff, never for each network chunk.
     */
    fun extendedTo(completedText: String): StreamTextSnapshot? {
        if (viewStartOffset != 0L || completedText.length.toLong() < length) return null
        val streamedText = materialize()
        if (!completedText.startsWith(streamedText)) return null
        if (completedText.length == streamedText.length) return this
        return append(completedText.substring(streamedText.length), (latestSegment?.sequence ?: 0L) + 1L)
    }

    /** A zero-copy view used when rendering only the response tail after a tool/action block. */
    fun suffixFrom(startOffset: Int): StreamTextSnapshot {
        val relativeStart = startOffset.coerceAtLeast(0).toLong().coerceAtMost(length)
        return StreamTextSnapshot(
            sourceBaseText = sourceBaseText,
            latestSegment = latestSegment,
            sourceLength = sourceLength,
            segmentCount = segmentCount,
            viewStartOffset = viewStartOffset + relativeStart
        )
    }

    /** Extracts a bounded source range without materializing the whole accumulated response. */
    fun slice(startOffset: Int, endOffset: Int): String {
        val start = (startOffset.coerceAtLeast(0).toLong() + viewStartOffset).coerceAtMost(sourceLength)
        val relativeEnd = endOffset.toLong().coerceAtMost(length)
        val end = (relativeEnd + viewStartOffset).coerceAtMost(sourceLength)
        if (end <= start) return ""
        val output = StringBuilder((end - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val baseEnd = minOf(end, sourceBaseText.length.toLong())
        if (start < baseEnd) {
            output.append(sourceBaseText, start.toInt(), baseEnd.toInt())
        }

        var segment = latestSegment
        while (segment != null && segment.startOffset >= end) {
            val skip = segment.skipPrevious
            segment = if (skip != null && skip.startOffset >= end) skip else segment.previous
        }
        val intersecting = ArrayList<StreamTextSegment>()
        while (segment != null && segment.endOffset > start) {
            if (segment.startOffset < end) intersecting += segment
            segment = segment.previous
        }
        for (index in intersecting.lastIndex downTo 0) {
            val part = intersecting[index]
            val from = maxOf(start, part.startOffset) - part.startOffset
            val until = minOf(end, part.endOffset) - part.startOffset
            if (until > from) output.append(part.text, from.toInt(), until.toInt())
        }
        return output.toString()
    }

    /** Returns only append-only display segments newer than [sequence], in source order. */
    fun segmentsAfter(sequence: Long): List<StreamTextSegment> {
        val reversed = ArrayList<StreamTextSegment>()
        var segment = latestSegment
        while (segment != null && segment.sequence > sequence) {
            reversed += segment
            segment = segment.previous
        }
        return reversed.asReversed().mapNotNull { part ->
            if (part.endOffset <= viewStartOffset) return@mapNotNull null
            val visibleStart = maxOf(part.startOffset, viewStartOffset)
            val textStart = (visibleStart - part.startOffset).toInt()
            StreamTextSegment(
                sequence = part.sequence,
                ordinal = part.ordinal,
                startOffset = visibleStart - viewStartOffset,
                endOffset = part.endOffset - viewStartOffset,
                text = part.text.substring(textStart),
                previous = null,
                skipPrevious = null
            )
        }
    }

    fun prefix(maxLength: Int): String {
        if (maxLength <= 0) return ""
        val visibleBase = baseText
        if (visibleBase.length >= maxLength) return visibleBase.substring(0, maxLength)
        val remaining = maxLength - visibleBase.length
        return buildString(maxLength) {
            append(visibleBase)
            var appended = 0
            for (segment in segmentsAfter(Long.MIN_VALUE)) {
                if (appended >= remaining) break
                val count = minOf(segment.text.length, remaining - appended)
                append(segment.text, 0, count)
                appended += count
            }
        }
    }

    fun materialize(): String {
        val capacity = length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return buildString(capacity) {
            append(baseText)
            segmentsAfter(Long.MIN_VALUE).forEach { append(it.text) }
        }
    }

    companion object {
        fun of(baseText: String = ""): StreamTextSnapshot =
            StreamTextSnapshot(baseText, latestSegment = null, sourceLength = baseText.length.toLong(), segmentCount = 0L)

        private const val SEGMENT_SKIP_DISTANCE = 32L
    }
}

/** A generation-scoped, non-Compose signal used to cancel in-flight Markdown parsing on Stop. */
internal class StreamFreezeSignal {
    private val _frozen = MutableStateFlow(false)
    val frozen: StateFlow<Boolean> = _frozen.asStateFlow()
    val isFrozen: Boolean get() = _frozen.value
    fun freeze() { _frozen.value = true }
}

/**
 * Narrow, message-scoped UI state for an active generation. It deliberately contains no Conversation
 * or history list; stream revisions therefore cannot invalidate navigation or stable history rows.
 */
class ActiveChatStreamState internal constructor(
    val conversationId: String,
    val messageId: String,
    val generationId: Long,
    val revision: Long,
    val status: ChatStreamStatus,
    internal val content: StreamTextSnapshot,
    internal val reasoning: StreamTextSnapshot,
    internal val stopSignal: StreamFreezeSignal,
    val lastCommitMetrics: StreamCommitMetrics?,
    val cumulativeMetrics: StreamMetricsSnapshot
)