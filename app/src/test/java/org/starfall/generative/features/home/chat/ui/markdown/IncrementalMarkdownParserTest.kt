package org.starfall.generative.features.home.chat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.mikepenz.markdown.model.State
import org.intellij.markdown.ast.ASTNode
import org.starfall.generative.features.home.chat.logic.markdown.parseLibraryMarkdown
import org.starfall.generative.features.home.chat.model.StreamTextSnapshot
import kotlin.random.Random

class IncrementalMarkdownParserTest {

    @Test
    fun growingStreamingBlockKeepsItsCompositionSlot() {
        val parser = IncrementalMarkdownParser(MarkdownParserCancellationToken())
        val firstText = "A response"
        val firstSource = StreamTextSnapshot.of(firstText)
        val first = parser.append(firstText, firstSource, maxUnstableTailChars = 128, maxParserAppendChars = 16)
        val secondText = " that keeps growing"
        val secondSource = firstSource.append(secondText, sequence = 1)
        val second = parser.append(secondText, secondSource, maxUnstableTailChars = 128, maxParserAppendChars = 16)
        val firstNode = first.snapshot.unstableNodes.first()
        val secondNode = second.snapshot.unstableNodes.first()

        assertEquals(
            incrementalBlockSlot("tail", 0, firstNode),
            incrementalBlockSlot("tail", 0, secondNode)
        )
        assertTrue(firstNode.endOffset < secondNode.endOffset)
    }

    @Test
    fun stableAstNodesAreSharedAcrossLaterAppends() {
        val parser = IncrementalMarkdownParser(MarkdownParserCancellationToken())
        val firstText = "First block.\n\n"
        val firstSource = StreamTextSnapshot.of(firstText)
        val first = parser.append(firstText, firstSource, maxUnstableTailChars = 128, maxParserAppendChars = 16)
        assertTrue(first.snapshot.stableNodes.isNotEmpty())
        val firstStableNode = first.snapshot.stableNodes.first()

        val secondText = "Second block."
        val fullSource = firstSource.append(secondText, sequence = 1)
        val second = parser.append(secondText, fullSource, maxUnstableTailChars = 128, maxParserAppendChars = 16)

        assertSame(firstStableNode, second.snapshot.stableNodes.first())
        assertEquals(fullSource.length.toInt(), second.snapshot.parsedLength)
        assertEquals(secondText.length, second.consumedChars)
    }

    @Test
    fun longUnstableBlockStopsAtTheConfiguredBoundAndReportsItsSourceStart() {
        IncrementalMarkdownMetrics.resetForTests()
        val parser = IncrementalMarkdownParser(MarkdownParserCancellationToken())
        val text = "x".repeat(400)
        val source = StreamTextSnapshot.of(text)

        val result = parser.append(
            text = text,
            source = source,
            maxUnstableTailChars = 64,
            maxParserAppendChars = 16
        )

        assertEquals(64, result.consumedChars)
        assertEquals(64, result.snapshot.parsedLength)
        assertEquals(0, result.snapshot.overflowStart)
        assertTrue(result.snapshot.unstableNodes.isNotEmpty())
        val metrics = IncrementalMarkdownMetrics.metrics()
        assertEquals(64, metrics.maxUnstableTailChars)
        assertEquals(1L, metrics.boundedTailFallbackCount)
        assertTrue(metrics.unstableTailCharsReprocessed > metrics.parserInputChars)
    }

    @Test
    fun overflowFallbackStartsAtTheFirstUnstableNodeWithoutDuplicatingIt() {
        val parser = IncrementalMarkdownParser(MarkdownParserCancellationToken())
        val text = "Stable paragraph.\n\n" + "x".repeat(256) + " tail that must remain visible"
        val source = StreamTextSnapshot.of(text)
        val result = parser.append(
            text = text,
            source = source,
            maxUnstableTailChars = 32,
            maxParserAppendChars = 16
        )

        val fallback = parser.preserveUnstableTailAsPlainText()
        val start = requireNotNull(fallback.overflowStart)
        val tail = source.slice(start, source.length.toInt())

        assertTrue(fallback.stableNodes.isNotEmpty())
        assertEquals(result.snapshot.overflowStart, start)
        assertEquals(text.substring(start), tail)
        assertTrue(fallback.unstableNodes.isNotEmpty())
    }

    @Test
    fun inlineLinkTargetsArePublishedForTheMarkdownReferenceHandler() {
        val parser = IncrementalMarkdownParser(MarkdownParserCancellationToken())
        val text = "[guide](https://example.test/guide)"
        val source = StreamTextSnapshot.of(text)

        val result = parser.append(
            text = text,
            source = source,
            maxUnstableTailChars = 128,
            maxParserAppendChars = 32
        )
        result.linkUpdates.forEach { (label, destination) ->
            parser.referenceLinkHandler.store(label, destination)
        }

        assertEquals("https://example.test/guide", parser.referenceLinkHandler.find("guide"))
    }

    @Test
    fun parserCancellationIsCheckedBeforeItConsumesAnotherChunk() {
        val token = MarkdownParserCancellationToken()
        val parser = IncrementalMarkdownParser(token)
        val text = "A paragraph."
        val source = StreamTextSnapshot.of(text)
        val cancelledJob = kotlinx.coroutines.Job().apply { cancel() }
        token.ownerJob = cancelledJob

        try {
            parser.append(text, source, maxUnstableTailChars = 128, maxParserAppendChars = 8)
            throw AssertionError("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(0, parser.file.endOffset)
        }
    }

    @Test
    fun allSplitPointsOfRepresentativeMarkdownMatchTheFinalFullParse() = runBlocking {
        val fixtures = listOf(
            "Hello \uD83D\uDE00.\r\n\r\nSecond paragraph.\r\n",
            "# Heading\n\nA **bold** and _italic_ paragraph with `code`.\n\n> quote\n",
            "- outer\n  - nested\n  - sibling\n\n1. ordered\n2. next\n",
            "```kotlin\nval answer = 42\n\nprintln(answer)\n```\n\nAfter fence.\n",
            "| Name | Value |\n| --- | ---: |\n| first | 1 |\n| second | 2 |\n",
            "<section>\n<div>html</div>\n</section>\n\n\$inline math\$ and [guide](https://example.test).\n",
            "[manual][guide]\n\n[guide]: https://example.test/reference\n"
        )

        fixtures.forEach { text ->
            for (split in 0..text.length) {
                assertMatchesFullParse(text, listOf(split))
            }
        }
    }

    @Test
    fun randomizedFragmentBoundariesMatchTheFinalFullParse() = runBlocking {
        val fixtures = listOf(
            "A paragraph with **emphasis** and a [link](https://example.test).\n\nNext.\n",
            "~~~text\nline one\n\nline two\n~~~\n\n- item one\n- item two\n",
            "| A | B |\n| --- | --- |\n| α | 😀 |\n\n<div>ok</div>\n"
        )
        val random = Random(0x51A7)
        repeat(30) {
            val text = fixtures[it % fixtures.size]
            val boundaries = (1 until text.length).filter { random.nextBoolean() }
            assertMatchesFullParse(text, boundaries)
        }
    }

    @Test
    fun orderedListWithBlankSeparatedItemsSurvivesEverySevenCharacterFragment() = runBlocking {
        val text = "Before\n\n1. One  \n   one text\n\n2. Two  \n   two text\n\n3. Three  \n   three text\n\nAfter"
        val parser = IncrementalMarkdownParser(MarkdownParserCancellationToken())
        var source = StreamTextSnapshot.of()
        text.chunked(7).forEachIndexed { index, fragment ->
            source = source.append(fragment, (index + 1).toLong())
            parser.append(fragment, source, maxUnstableTailChars = 512, maxParserAppendChars = 7)
            val partial = parseLibraryMarkdown(source.materialize()) as State.Success
            assertEquals("AST must never lose content after fragment $index", astShape(partial.node), astShape(parser.file))
        }
        val list = parser.file.children.single { it.type.name == "ORDERED_LIST" }
        assertEquals(3, list.children.count { it.type.name == "LIST_ITEM" })
        assertEquals(3, parser.currentSnapshot().stableNodes
            .flatMap { node -> if (node.type.name == "ORDERED_LIST") node.children else emptyList() }
            .count { it.type.name == "LIST_ITEM" })
    }

    @Test
    fun multiLineVietnameseOrderedListPreservesAllItemsAcrossRandomStreamBoundaries() = runBlocking {
        val text = """Hiện tại, dòng **Gemini 4 có 3 biến thể chính**:

1. **Gemini 4 Hydrogen**${"  "}
   Tối ưu tốc độ, chi phí và các tác vụ hằng ngày như coding, agent workflow, xử lý tài liệu.

2. **Gemini 4 Argon**${"  "}
   Model frontier cho suy luận phức tạp, coding nâng cao, phân tích tài chính/pháp lý và các tác vụ dài hơn.

3. **Gemini 4 Carbon**${"  "}
   Biến thể cao cấp nhất, tập trung vào suy luận dài hạn, kỹ thuật phức tạp và an ninh mạng.

Vì vậy, nếu chỉ tính **các model cốt lõi được Google công bố**, câu trả lời là **3 biến thể: Hydrogen, Argon và Carbon**. Các mức `low`, `medium`, `high`, `xhigh` của Hydrogen là **mức độ suy luận**, không phải biến thể model riêng. Google cũng có thể triển khai chúng dưới các sản phẩm chuyên biệt, chẳng hạn phiên bản dành cho robotics, nhưng không nhất thiết là một model cốt lõi thứ tư."""
        val random = Random(0x51A7)
        repeat(25) { run ->
            val parser = IncrementalMarkdownParser(MarkdownParserCancellationToken())
            var source = StreamTextSnapshot.of()
            var cursor = 0
            var sequence = 0L
            while (cursor < text.length) {
                val end = (cursor + random.nextInt(1, 25)).coerceAtMost(text.length)
                val fragment = text.substring(cursor, end)
                source = source.append(fragment, ++sequence)
                parser.append(fragment, source, maxUnstableTailChars = 4_096, maxParserAppendChars = 32)
                cursor = end
            }
            val list = parser.file.children.single { it.type.name == "ORDERED_LIST" }
            assertEquals("Run $run lost a list item", 3, list.children.count { it.type.name == "LIST_ITEM" })
            val expected = parseLibraryMarkdown(text) as State.Success
            assertEquals("Run $run changed Markdown structure", astShape(expected.node), astShape(parser.file))
        }
    }

    private suspend fun assertMatchesFullParse(text: String, boundaries: List<Int>) {
        val parser = IncrementalMarkdownParser(MarkdownParserCancellationToken())
        var source = StreamTextSnapshot.of()
        var cursor = 0
        var sequence = 0L
        (boundaries + text.length).distinct().sorted().forEach { end ->
            val fragment = text.substring(cursor, end)
            if (fragment.isNotEmpty()) {
                sequence++
                source = source.append(fragment, sequence)
                val result = parser.append(
                    text = fragment,
                    source = source,
                    maxUnstableTailChars = text.length + 1,
                    maxParserAppendChars = 32
                )
                assertEquals(fragment.length, result.consumedChars)
            }
            cursor = end
        }
        val expected = parseLibraryMarkdown(text) as State.Success
        assertEquals(astShape(expected.node), astShape(parser.file))
    }

    private fun astShape(root: ASTNode): String = buildString {
        fun appendNode(node: ASTNode) {
            append('(').append(node.type.name).append(':').append(node.startOffset).append('-').append(node.endOffset)
            node.children.forEach(::appendNode)
            append(')')
        }
        appendNode(root)
    }
}
