package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderCacheIdentity
import org.starfall.generative.features.home.chat.logic.markdown.stableMarkdownPrefixLength
import org.starfall.generative.features.home.chat.model.*
import org.starfall.generative.features.home.chat.ui.ChatRenderCaches
import org.starfall.generative.features.home.chat.ui.MarkdownParseCache
import org.starfall.generative.features.home.chat.ui.markdown.*
import org.starfall.generative.shared.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class StreamingMarkdownPreparationTest {
    @get:Rule val compose = createComposeRule()
    @Before fun clearCaches() {
        // Android runs the library's manifest provider before Application; Robolectric does not.
        ru.noties.jlatexmath.JLatexMathAndroid.init(ApplicationProvider.getApplicationContext())
        ChatRenderCaches.clearAll()
        IncrementalMarkdownMetrics.resetForTests()
    }
    private val text = "A paragraph with **bold**.\n\n\$\$x^2\$\$\n\nLatest paragraph."
    private fun state() = ActiveChatStreamState(
        conversationId = "chat", messageId = "reply", generationId = 1L, revision = 1L,
        status = ChatStreamStatus.STREAMING, content = StreamTextSnapshot.of(text),
        reasoning = StreamTextSnapshot.of(), stopSignal = StreamFreezeSignal(),
        lastCommitMetrics = null, cumulativeMetrics = StreamMetricsSnapshot())

    @Test fun offscreenStreamPreparesCachesAndFinalRowReusesTheAst() {
        val stream = MutableStateFlow<ActiveChatStreamState?>(state())
        var saved by mutableStateOf<StoredMessage?>(null)
        var showReply by mutableStateOf(false)
        var key: LatexCacheKey? = null
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current
            key = latexCacheKey("x^2", with(density) { MaterialTheme.typography.bodyLarge.fontSize.toPx() },
                MaterialTheme.colorScheme.onSurface.toArgb(), false)
            PrepareStreamingMarkdown("chat", saved, stream)
            if (showReply) MarkdownRenderer(text, cacheIdentity = MarkdownRenderCacheIdentity("chat", "reply"))
            else Text("Earlier history is still visible")
        } }
        val prefix = text.take(stableMarkdownPrefixLength(text)).trimEnd()
        compose.waitUntil(10_000) { key?.let { LatexDrawables.peek(it) } != null && MarkdownParseCache.contains(prefix) }
        compose.onNodeWithText("Earlier history is still visible").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Rendered formula").assertCountEquals(0)
        compose.runOnIdle {
            saved = StoredMessage("reply", ChatRole.MODEL,
                listOf(MessageVersion(content = text, processingFinishedAt = 1L)))
            stream.value = null // Terminal StateFlow publications may be conflated away.
        }
        compose.waitUntil(10_000) { MarkdownParseCache.contains(text) }
        val before = MarkdownParseCache.metrics().fullParseCount
        compose.runOnIdle { showReply = true }
        compose.onNodeWithText("Latest paragraph.", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Mount must reuse the prepared AST rather than parse it again",
                before, MarkdownParseCache.metrics().fullParseCount)
        }
    }

    @Test fun mountedRowsSkipSpeculationAndUnmountingStartsPreparationWithoutANewChunk() {
        val stream = MutableStateFlow<ActiveChatStreamState?>(state())
        var mounted by mutableStateOf(setOf("reply"))
        compose.setContent { MaterialTheme {
            PrepareStreamingMarkdown("chat", null, stream, composedMessageIds = { mounted })
            Text("History")
        } }
        val prefix = text.take(stableMarkdownPrefixLength(text)).trimEnd()
        compose.runOnIdle { assertFalse(MarkdownParseCache.contains(prefix))
            // This state is observed only by snapshotFlow, with no recomposition to apply it.
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { mounted = emptySet() } }
        compose.waitUntil(10_000) { MarkdownParseCache.contains(prefix) }
        assertNotNull(MarkdownParseCache.get(prefix, MarkdownRenderCacheIdentity("chat", "reply", "another-block")))
    }

    @Test fun closedFormulaIsPreparedBeforeTheNextParagraphAndReused() = runBlocking {
        val preparer = StreamingMarkdownPreparer()
        val source = StreamTextSnapshot.of("\$\$x^2\$\$")
        preparer.prepare(source, false, "AUTO", 32f, 32f, 0xff000000.toInt(), false)
        val key = latexCacheKey("x^2", 32f, 0xff000000.toInt(), false)
        val first = LatexDrawables.peek(key)
        assertNotNull("Closing a formula is enough; no next paragraph or scroll is needed", first)
        preparer.prepare(source.append("\n\nNext paragraph", 1L), false, "AUTO", 32f, 32f, 0xff000000.toInt(), false)
        assertSame(first, LatexDrawables.peek(key))
    }
    @Test fun offscreenUnfinishedTailIsNotRescannedPerTinyFragment() = runBlocking {
        val preparer = StreamingMarkdownPreparer()
        var source = StreamTextSnapshot.of("An unfinished text paragraph")
        preparer.prepare(source, false, "OFF", 32f, 32f, 0xff000000.toInt(), false)
        val afterInitialScan = preparer.speculativeScanCount
        repeat(45) { i ->
            source = source.append("x", (i + 1).toLong())
            preparer.prepare(source, false, "OFF", 32f, 32f, 0xff000000.toInt(), false)
        }
        assertEquals("An unclosed tail must not be rescanned for every token",
            afterInitialScan, preparer.speculativeScanCount)
        source = source.append("\n\nNext paragraph", 46L)
        preparer.prepare(source, false, "OFF", 32f, 32f, 0xff000000.toInt(), false)
        assertTrue("Closing a block should trigger a new speculative scan",
            preparer.speculativeScanCount > afterInitialScan)
    }

    @Test fun oversizedOffscreenSourceSkipsSpeculativeParsingWithoutDroppingSource() = runBlocking {
        val preparer = StreamingMarkdownPreparer()
        val oversized = "Long block without a closing boundary. ".repeat(
            MAX_SPECULATIVE_MARKDOWN_CHARS / 16
        )
        val source = StreamTextSnapshot.of(oversized)
        assertTrue(source.length > MAX_SPECULATIVE_MARKDOWN_CHARS)
        preparer.prepare(source, false, "AUTO", 32f, 32f, 0xff000000.toInt(), false)
        assertEquals("Optional preparation should not scan huge offscreen tails", 0,
            preparer.speculativeScanCount)
        // A skipped speculative parse must not change the source or discard its suffix.
        assertEquals(oversized, source.materialize())
    }

    @Test fun oversizedFinalDocumentDoesNotOccupyTheSharedSpeculativeParser() = runBlocking {
        val preparer = StreamingMarkdownPreparer()
        val oversized = ("Big document with **many words**.\n\n").repeat(
            MAX_SPECULATIVE_MARKDOWN_CHARS / 16
        )
        val source = StreamTextSnapshot.of(oversized)
        assertTrue(source.length > MAX_SPECULATIVE_MARKDOWN_CHARS)
        preparer.prepare(source, true, "OFF", 32f, 32f, 0xff000000.toInt(), false)
        assertEquals(0, preparer.speculativeScanCount)
        // Parsing the original when it becomes visible remains the renderer's job.
        assertEquals(oversized, source.materialize())
    }

    @Test fun unsupportedFormulaDoesNotRepeatExpensiveConstruction() = runBlocking {
        val key = latexCacheKey("\\notARealTexCommandHere{1}", 32f, 0xff000000.toInt(), false)
        val before = LatexDrawables.constructionAttempts.get()
        val first = runCatching { prepareLatexDrawable(key) }.exceptionOrNull()
        assertNotNull(first)
        assertTrue(LatexDrawables.isPrepared(key))
        assertNull(LatexDrawables.peek(key))
        val repeated = runCatching { prepareLatexDrawable(key) }.exceptionOrNull()
        // Coroutine stack-trace recovery may clone a thrown ParseException across
        // withContext, so Throwable reference identity is not a reliable cache test.
        assertEquals(first?.javaClass, repeated?.javaClass)
        assertEquals(first?.message, repeated?.message)
        assertEquals("Unsupported TeX must not be constructed a second time",
            before + 1, LatexDrawables.constructionAttempts.get())
    }

    @Test fun rowFirstOpenedAfterStopUsesPreparedMarkdownInsteadOfPermanentPlainText() = runBlocking {
        val content = "**Prepared**\n\nFinal paragraph."
        val source = StreamTextSnapshot.of(content)
        StreamingMarkdownPreparer().prepare(source, true, "OFF", 32f, 32f, 0xff000000.toInt(), false)
        val before = MarkdownParseCache.metrics().fullParseCount
        val frozen = StreamFreezeSignal().also { it.freeze() }
        compose.setContent { MaterialTheme {
            StreamingMarkdownRenderer("", streamSnapshot = source, streamGenerationId = 1,
                streamMessageId = "reply", streamFreezeSignal = frozen)
        } }
        compose.onNodeWithText("Prepared", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("**Prepared**", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(before, MarkdownParseCache.metrics().fullParseCount)
    }

    @Test fun incrementalRendererPresentsAcceptedNewTextWithoutReplayingStaticBlocks() {
        var source by mutableStateOf(StreamTextSnapshot.of("Opening paragraph.\n\n"))
        compose.setContent { MaterialTheme {
            StreamingMarkdownRenderer(
                content = "",
                streamSnapshot = source,
                streamGenerationId = 5L,
                streamMessageId = "reply"
            )
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Opening paragraph.", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            source = source.append("Another paragraph.\n\n", 2L)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Another paragraph.", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Opening paragraph.", useUnmergedTree = true).assertExists()
    }

    @Test fun finalizedMarkdownBlocksKeepTheirTextAfterManyLaterStreamRevisions() {
        var source by mutableStateOf(StreamTextSnapshot.of("# First heading\n\nFirst paragraph.\n\n"))
        compose.setContent { MaterialTheme {
            StreamingMarkdownRenderer(
                content = "",
                streamSnapshot = source,
                streamGenerationId = 7L,
                streamMessageId = "reply"
            )
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("First paragraph.", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        var appended = source
        repeat(70) { i -> appended = appended.append("Block $i.\n\n", (i + 2).toLong()) }
        compose.runOnIdle { source = appended }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Block 69.", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("First paragraph.", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("First heading", useUnmergedTree = true).assertExists()
    }

    @Test fun blankSeparatedOrderedListStaysVisibleWhileStreamingAndAfterFreeze() {
        val prefix = "Before\n\n1. One  \n   one text\n\n2. Two  \n   two text\n\n"
        val suffix = "3. Three  \n   three text\n\nAfter"
        var source by mutableStateOf(StreamTextSnapshot.of())
        var finished by mutableStateOf(false)
        val freeze = StreamFreezeSignal()
        compose.setContent { MaterialTheme {
            StreamingMarkdownRenderer(
                content = "",
                isStreaming = true,
                streamSnapshot = source,
                streamGenerationId = 11L,
                streamMessageId = "ordered-list-reply",
                streamFreezeSignal = freeze,
                streamGrowthActive = !finished
            )
        } }
        compose.runOnIdle { source = source.append(prefix, 1L) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("One", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Two", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { source = source.append(suffix, 2L) }
        compose.waitUntil(10_000) {
            listOf("One", "Two", "Three", "After").all { label ->
                compose.onAllNodesWithText(label, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.runOnIdle { finished = true; freeze.freeze() }
        listOf("One", "Two", "Three", "After").forEach { label ->
            compose.onNodeWithText(label, substring = true, useUnmergedTree = true).assertExists()
        }
    }

    @Test fun completeOrderedListIsPresentOnColdReopen() {
        val content = "Before\n\n1. One  \n   one text\n\n2. Two  \n   two text\n\n3. Three  \n   three text\n\nAfter"
        compose.setContent { MaterialTheme {
            MarkdownRenderer(content, cacheIdentity = MarkdownRenderCacheIdentity("chat", "ordered-reopen"))
        } }
        compose.waitUntil(10_000) {
            listOf("One", "Two", "Three", "After").all { label ->
                compose.onAllNodesWithText(label, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test fun coldStreamingRowCoalescesUnseenPublicationsInsteadOfReplayingEveryChunk() {
        var source = StreamTextSnapshot.of("")
        repeat(200) { index -> source = source.append("Block ${index + 1}\n\n", index + 1L) }
        compose.setContent { MaterialTheme {
            StreamingMarkdownRenderer("", streamSnapshot = source, streamGenerationId = 1,
                streamMessageId = "reply")
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Block 200", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Catching up must not issue one parser append per old publication",
            IncrementalMarkdownMetrics.metrics().parserAppendCount < 20)
    }

}
