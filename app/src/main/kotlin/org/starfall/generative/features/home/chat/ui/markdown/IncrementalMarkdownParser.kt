package org.starfall.generative.features.home.chat.ui.markdown

import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.starfall.generative.features.home.chat.logic.markdown.stableMarkdownPrefixLength
import org.starfall.generative.features.home.chat.model.StreamTextSnapshot
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class IncrementalMarkdownMetricsSnapshot(
    val parserAppendCount: Long,
    val parserInputChars: Long,
    val unstableTailCharsReprocessed: Long,
    val maxUnstableTailChars: Int,
    val boundedTailFallbackCount: Long
)

/** Content-free counters estimate the active parser's work without retaining source or AST data. */
internal object IncrementalMarkdownMetrics {
    private val parserAppendCount = AtomicLong()
    private val parserInputChars = AtomicLong()
    private val unstableTailCharsReprocessed = AtomicLong()
    private val maxUnstableTailChars = AtomicInteger()
    private val boundedTailFallbackCount = AtomicLong()

    fun recordAppend(inputChars: Int, estimatedParserChars: Int, tailChars: Int) {
        parserAppendCount.incrementAndGet()
        parserInputChars.addAndGet(inputChars.toLong())
        unstableTailCharsReprocessed.addAndGet(estimatedParserChars.toLong())
        maxUnstableTailChars.updateAndGet { maxOf(it, tailChars) }
    }

    fun recordFallback() { boundedTailFallbackCount.incrementAndGet() }

    fun metrics() = IncrementalMarkdownMetricsSnapshot(
        parserAppendCount.get(),
        parserInputChars.get(),
        unstableTailCharsReprocessed.get(),
        maxUnstableTailChars.get(),
        boundedTailFallbackCount.get()
    )

    internal fun resetForTests() {
        parserAppendCount.set(0L)
        parserInputChars.set(0L)
        unstableTailCharsReprocessed.set(0L)
        maxUnstableTailChars.set(0)
        boundedTailFallbackCount.set(0L)
    }
}

/** An immutable render snapshot; stable nodes share structure across appends. */
internal data class IncrementalMarkdownSnapshot(
    val stableNodes: PersistentList<ASTNode> = persistentListOf(),
    val unstableNodes: List<ASTNode> = emptyList(),
    val parsedLength: Int = 0,
    val overflowStart: Int? = null,
    val revision: Long = 0L
)

internal data class IncrementalMarkdownAppendResult(
    val snapshot: IncrementalMarkdownSnapshot,
    val consumedChars: Int,
    val linkUpdates: List<Pair<String, String?>>
)

/** Connects parser cancellation to the keyed Compose effect that owns this generation. */
internal class MarkdownParserCancellationToken : CancellationToken {
    @Volatile
    var ownerJob: Job? = null

    override fun checkCancelled() {
        ownerJob?.ensureActive()
    }
}

/**
 * Parse only the unsettled source tail with the canonical GFM parser, retaining finished blocks.
 * JetBrains StreamingMarkdownFile can silently drop list items at arbitrary fragment boundaries,
 * so it must not be the source of truth for streamed Markdown nodes.
 */
internal class IncrementalMarkdownParser(
    private val cancellationToken: CancellationToken
) {
    private val parser = MarkdownParser(GFMFlavourDescriptor(), cancellationToken = cancellationToken)
    private var parsedLength = 0
    private var stableLength = 0
    private var stableNodes: PersistentList<ASTNode> = persistentListOf()
    private var unstableNodes: List<ASTNode> = emptyList()
    private var overflowStart: Int? = null
    private var revision = 0L

    /** Stable root identity for the library's Markdown state; children reflect the current snapshot. */
    val file: ASTNode = object : ASTNode {
        override val type get() = MarkdownElementTypes.MARKDOWN_FILE
        override val startOffset get() = 0
        override val endOffset get() = parsedLength
        override val parent: ASTNode? get() = null
        override val children: List<ASTNode> get() = stableNodes + unstableNodes
    }
    val referenceLinkHandler = ReferenceLinkHandlerImpl()

    fun append(
        text: String,
        source: StreamTextSnapshot,
        maxUnstableTailChars: Int,
        maxParserAppendChars: Int
    ): IncrementalMarkdownAppendResult {
        require(maxUnstableTailChars > 0)
        require(maxParserAppendChars > 0)
        cancellationToken.checkCancelled()
        var consumed = 0
        val linkUpdates = LinkedHashMap<String, String?>()

        while (consumed < text.length && overflowStart == null) {
            cancellationToken.checkCancelled()
            val remainingTailBudget = maxUnstableTailChars - (parsedLength - stableLength)
            if (remainingTailBudget <= 0) {
                enterOverflow()
                break
            }

            val appendLimit = minOf(maxParserAppendChars, remainingTailBudget, text.length - consumed)
            val end = safeAppendEnd(text, consumed, appendLimit)
            if (end <= consumed) {
                // Do not split a surrogate pair merely to fill the parser budget.
                enterOverflow()
                break
            }

            val chunkLength = end - consumed
            val tail = source.slice(stableLength, parsedLength + chunkLength)
            val estimatedParserChars = tail.length
            val tree = parser.buildMarkdownTreeFromString(tail)
            cancellationToken.checkCancelled()

            // Freeze only entire top-level nodes at a boundary where later tokens cannot change
            // their meaning. Keep a list together across blank lines so its items are not split.
            val prefix = stableMarkdownPrefixLength(tail)
            val closedParagraph = tail.endsWith("\n\n") &&
                tree.children.lastOrNull { it.type.name != "EOL" }?.type == MarkdownElementTypes.PARAGRAPH
            val proposedCut = if (closedParagraph) tail.length else prefix
            val cut = proposedCut.takeIf { boundary ->
                boundary > 0 && tree.children.none { it.startOffset < boundary && it.endOffset > boundary }
            } ?: 0
            val finished = tree.children.takeWhile { it.endOffset <= cut }
            if (finished.isNotEmpty()) {
                val newStable = finished.map { OffsetMarkdownAstNode(it, stableLength) }
                stableNodes = stableNodes.addingAll(newStable)
                newStable.forEach { collectLinkUpdates(it, source, linkUpdates) }
            }
            unstableNodes = tree.children.drop(finished.size).map { OffsetMarkdownAstNode(it, stableLength) }
            stableLength += cut
            parsedLength += chunkLength
            consumed = end
            unstableNodes.forEach { collectLinkUpdates(it, source, linkUpdates) }

            val remainingLength = parsedLength - stableLength
            IncrementalMarkdownMetrics.recordAppend(chunkLength, estimatedParserChars, remainingLength)
            if (remainingLength >= maxUnstableTailChars) enterOverflow()
        }

        revision++
        return IncrementalMarkdownAppendResult(
            snapshot = currentSnapshot(),
            consumedChars = consumed,
            linkUpdates = linkUpdates.entries.map { it.key to it.value }
        )
    }

    private fun enterOverflow() {
        overflowStart = stableLength
        IncrementalMarkdownMetrics.recordFallback()
    }

    /** Stops parsing the unsettled tail and exposes its complete source as plain text. */
    fun preserveUnstableTailAsPlainText(): IncrementalMarkdownSnapshot {
        if (overflowStart == null) {
            enterOverflow()
            revision++
        }
        return currentSnapshot()
    }

    fun currentSnapshot(): IncrementalMarkdownSnapshot = IncrementalMarkdownSnapshot(
        stableNodes = stableNodes,
        unstableNodes = unstableNodes,
        parsedLength = parsedLength,
        overflowStart = overflowStart,
        revision = revision
    )

    private fun safeAppendEnd(text: String, start: Int, limit: Int): Int {
        var end = (start + limit).coerceAtMost(text.length)
        if (end < text.length && end > start && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
            end--
        }
        return end
    }

    private fun collectLinkUpdates(
        root: ASTNode,
        source: StreamTextSnapshot,
        updates: MutableMap<String, String?>
    ) {
        fun unescaped(node: ASTNode): String {
            val start = node.startOffset.coerceIn(0, source.length.toInt())
            val end = node.endOffset.coerceIn(start, source.length.toInt())
            val text = source.slice(start, end)
            return ShiftedMarkdownAstNode(node, start).getUnescapedTextInNode(text)
        }

        fun visit(node: ASTNode) {
            var linkOnly = false
            val label = when (node.type) {
                MarkdownElementTypes.LINK_DEFINITION -> node.findChildOfType(MarkdownElementTypes.LINK_LABEL)
                    ?.let { unescaped(it).removeSurrounding("[", "]") }
                MarkdownElementTypes.INLINE_LINK -> node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                    ?.let { unescaped(it).removeSurrounding("[", "]") }
                MarkdownElementTypes.AUTOLINK -> {
                    linkOnly = true
                    unescaped(node.children.firstOrNull { it.type.name == MarkdownElementTypes.AUTOLINK.name } ?: node)
                }
                else -> null
            }
            if (label != null) {
                val destination = if (linkOnly) label else {
                    node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.let(::unescaped)
                }
                updates[label] = destination
            }
            node.children.forEach(::visit)
        }
        visit(root)
    }
}

/** Shift a Markdown subtree's source offsets into a per-block source string. */
internal class ShiftedMarkdownAstNode(
    private val source: ASTNode,
    private val offset: Int,
    private val parentNode: ASTNode? = null
) : ASTNode {
    override val type get() = source.type
    override val startOffset: Int get() = (source.startOffset - offset).coerceAtLeast(0)
    override val endOffset: Int get() = (source.endOffset - offset).coerceAtLeast(startOffset)
    override val parent: ASTNode? get() = parentNode
    override val children: List<ASTNode> by lazy(LazyThreadSafetyMode.NONE) {
        source.children.map { ShiftedMarkdownAstNode(it, offset, this) }
    }
}

/** Translate a tail-local parsed subtree into the offsets of the append-only message source. */
private class OffsetMarkdownAstNode(
    private val node: ASTNode,
    private val offset: Int,
    private val parentNode: ASTNode? = null
) : ASTNode {
    override val type get() = node.type
    override val startOffset get() = node.startOffset + offset
    override val endOffset get() = node.endOffset + offset
    override val parent: ASTNode? get() = parentNode
    override val children: List<ASTNode> by lazy(LazyThreadSafetyMode.NONE) {
        node.children.map { OffsetMarkdownAstNode(it, offset, this) }
    }
}
