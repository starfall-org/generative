package org.starfall.generative

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.starfall.generative.features.home.chat.model.*
import org.starfall.generative.features.home.chat.ui.ChatMessageList
import org.starfall.generative.features.home.chat.ui.AssistantMessageCard
import org.starfall.generative.features.home.chat.ui.ChatMessageListState
import org.starfall.generative.features.home.chat.ui.markdown.StreamingMarkdownRenderer
import org.starfall.generative.shared.model.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.home.chat.ui.markdown.StreamGrowthViewport

/** Exercise the real Android compositor and layout; the boundary is not a painted overlay. */
@RunWith(AndroidJUnit4::class)
class StreamGrowthViewportDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun viewportHeight(): Float =
        compose.onNodeWithTag("stream-growth-viewport").fetchSemanticsNode().boundsInRoot.height

    @Test fun longIncrementalParagraphsKeepTheBottomBoundaryInTheReadableViewport() {
        val messages = listOf(
            StoredMessage("question", ChatRole.USER, listOf(MessageVersion(content = "Explain this clearly."))),
            StoredMessage("answer", ChatRole.MODEL, listOf(MessageVersion()))
        )
        val list = ChatMessageListState(initialMessages = messages)
        val freeze = StreamFreezeSignal()
        var source by mutableStateOf(StreamTextSnapshot.of())
        val live = MutableStateFlow<ActiveChatStreamState?>(null)
        fun publish(revision: Long) {
            live.value = ActiveChatStreamState(
                conversationId = "long-stream", messageId = "answer", generationId = 47L,
                revision = revision, status = ChatStreamStatus.STREAMING,
                content = source, reasoning = StreamTextSnapshot.of(), stopSignal = freeze,
                lastCommitMetrics = null, cumulativeMetrics = StreamMetricsSnapshot()
            )
        }
        fun paragraph(index: Int): String =
            "Paragraph $index: A continuously generated response should keep flowing over several " +
                "lines as it explains the relationship between layout measurement, visible content " +
                "and natural native scrolling. The renderer must preserve previously composed " +
                "paragraphs while accepting another complete passage.\n\n"

        compose.setContent { MaterialTheme {
            ChatMessageList(
                state = list, messages = messages, streaming = true, autoScroll = true,
                historyLoading = false, activeStream = live,
                appBarHeight = 64.dp, bottomClearance = 108.dp
            ) { message ->
                if (message.id == "answer") {
                    StreamingMarkdownRenderer(
                        content = "", isStreaming = true, streamSnapshot = source,
                        streamGenerationId = 47L, streamMessageId = "answer",
                        modifier = Modifier.testTag("actual-stream-boundary")
                    )
                } else {
                    Text(message.content)
                }
            }
        } }
        compose.waitForIdle()
        // A real long answer: 40 paragraphs, several screens, and 10 separate growth waves.
        // No synthetic element heights, opaque placeholder blocks or artificial scroll offsets.
        var revision = 0L
        val anchoredEdges = mutableListOf<Float>()
        for (batch in 0 until 10) {
            compose.runOnIdle {
                repeat(4) { within ->
                    source = source.append(paragraph(batch * 4 + within), ++revision)
                }
                publish(revision)
            }
            val lastParagraphIndex = batch * 4 + 3
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Paragraph $lastParagraphIndex:",
                    substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle() // Both the growth spring and the native follow may finish.
            if (batch >= 3) {
                val edge = compose.onNodeWithTag("actual-stream-boundary")
                    .fetchSemanticsNode().boundsInRoot.bottom
                anchoredEdges += edge
                assertTrue("Auto-scroll must stay attached on a long stream", !list.detached)
                assertTrue("The actual answer must extend far beyond the screen after several chunks",
                    source.length > 3000L || batch < 5)
            }
        }
        val variation = anchoredEdges.maxOrNull()!! - anchoredEdges.minOrNull()!!
        assertTrue("The growing boundary must stay in approximately one on-screen position " +
            "after the chat reaches the bottom, rather than bouncing with each chunk. " +
            "Measured: $anchoredEdges", variation < 56f)
        assertTrue(source.length > 9000L)
    }

    @Test fun autoScrollKeepsMultilineGrowthEdgeStableBetweenAnimationFrames() {
        val messages = listOf(
            StoredMessage("user", ChatRole.USER, listOf(MessageVersion(content = "Question"))),
            StoredMessage("answer", ChatRole.MODEL, listOf(MessageVersion()))
        )
        val list = ChatMessageListState(initialMessages = messages)
        val passage = "The response continues across the width of the device with complete " +
            "sentences, wrapping naturally in the current font. Each paragraph contributes " +
            "its actual measured height, not a synthetic block size.\n\n"
        var content by mutableStateOf(passage.repeat(14))
        compose.setContent { MaterialTheme {
            ChatMessageList(
                state = list, messages = messages, streaming = true, autoScroll = true,
                historyLoading = false, activeStream = null, appBarHeight = 64.dp,
                bottomClearance = 108.dp
            ) { message ->
                if (message.id == "answer") {
                    StreamGrowthViewport(Modifier.testTag("following-growth-edge")) {
                        Text(content)
                    }
                } else Text(message.content)
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle { list.jump() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val initialScrollOffset = list.listState.firstVisibleItemScrollOffset
        val initialContentLength = content.length
        val requestCountBeforeGrowth = list.requestCount
        val start = compose.onNodeWithTag("following-growth-edge")
            .fetchSemanticsNode().boundsInRoot.bottom
        compose.runOnIdle { content += passage.repeat(7) }
        val positions = mutableListOf<Float>()
        repeat(55) {
            compose.mainClock.advanceTimeBy(16L)
            positions += compose.onNodeWithTag("following-growth-edge")
                .fetchSemanticsNode().boundsInRoot.bottom
        }
        val delta = positions.maxOrNull()!! - positions.minOrNull()!!
        println("LONG_STREAM_AUTOFOLLOW_EDGE_START=$start FRAMES=${positions.joinToString(",")}")
        assertTrue("Auto-scroll should pin the actual clipping edge on screen, not let it jump " +
            "with multiline stream batches. Frame drift: $delta pixels", delta <= 32f)
        assertTrue("The content must actually grow by seven multiline paragraphs",
            content.length > initialContentLength)
        assertTrue("The native LazyColumn must scroll to compensate the real measured growth",
            list.listState.firstVisibleItemScrollOffset > initialScrollOffset)
        assertTrue("Do not generate a new animated scroll intent for every layout frame",
            list.requestCount - requestCountBeforeGrowth < 5L)
        assertTrue("Auto-scroll should not detach during native growth", !list.detached)
        compose.mainClock.autoAdvance = true
    }

    @Test fun latestPinsTheEdgeButReadingHistoryKeepsTextFixedWhileTheEdgeGrows() {
        val messages = listOf(
            StoredMessage("user", ChatRole.USER, listOf(MessageVersion(content = "Question"))),
            StoredMessage("answer", ChatRole.MODEL, listOf(MessageVersion()))
        )
        val list = ChatMessageListState(initialMessages = messages)
        val paragraph = "A real, wrapped streaming paragraph that is long enough to occupy " +
            "several lines on the physical device. Reading an earlier section must not " +
            "be disturbed as more text arrives at the end of the response.\n\n"
        var content by mutableStateOf(paragraph.repeat(16))
        compose.setContent { MaterialTheme {
            ChatMessageList(
                state = list, messages = messages, streaming = true, autoScroll = true,
                historyLoading = false, activeStream = null,
                appBarHeight = 64.dp, bottomClearance = 108.dp
            ) { message ->
                if (message.id == "answer") {
                    StreamGrowthViewport(Modifier.testTag("two-mode-stream-edge")) {
                        Text(content, Modifier.testTag("actual-streaming-text"))
                    }
                } else Text(message.content)
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle { list.jump() }
        compose.waitForIdle()
        assertTrue("The latest marker must be confirmed before using the fixed-edge mode",
            list.isLatestPinned("answer"))

        // Swipe up to read existing content while auto-scroll remains enabled in settings.
        compose.onNodeWithTag("chat-message-list").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertTrue("Manual reading must detach native follow", list.detached)
            assertTrue(!list.isLatestPinned("answer"))
        }
        compose.waitForIdle()
        val firstPosition = list.listState.firstVisibleItemIndex to
            list.listState.firstVisibleItemScrollOffset
        val textTop = compose.onNodeWithTag("actual-streaming-text")
            .getUnclippedBoundsInRoot().top
        val edgeBefore = compose.onNodeWithTag("two-mode-stream-edge")
            .getUnclippedBoundsInRoot().bottom
        val requestsBefore = list.requestCount

        compose.runOnIdle { content += paragraph.repeat(8) }
        compose.waitForIdle()
        val edgeAfter = compose.onNodeWithTag("two-mode-stream-edge")
            .getUnclippedBoundsInRoot().bottom
        val textTopAfter = compose.onNodeWithTag("actual-streaming-text")
            .getUnclippedBoundsInRoot().top
        println("OFF_LATEST_BEFORE=$edgeBefore AFTER=$edgeAfter TOP_BEFORE=$textTop TOP_AFTER=$textTopAfter " +
            "INDEX=$firstPosition NEW_INDEX=${list.listState.firstVisibleItemIndex to list.listState.firstVisibleItemScrollOffset}")
        assertTrue("While reading history, the invisible boundary keeps moving down with " +
            "the newly rendered text", edgeAfter > edgeBefore + 100.dp)
        assertEquals("Already visible text must not move because new content arrived",
            textTop.value, textTopAfter.value, 1f)
        assertEquals("Growth while away from latest must not alter the list position",
            firstPosition, list.listState.firstVisibleItemIndex to
                list.listState.firstVisibleItemScrollOffset)
        assertEquals("No additional native follow requests while reading history",
            requestsBefore, list.requestCount)

        // Returning to latest resumes fixed-edge mode on subsequent content growth.
        compose.runOnIdle { list.jump() }
        compose.waitForIdle()
        assertTrue("The user has reached latest again", list.isLatestPinned("answer"))
        val pinnedEdge = compose.onNodeWithTag("two-mode-stream-edge")
            .getUnclippedBoundsInRoot().bottom
        val offsetBeforeFollowing = list.listState.firstVisibleItemScrollOffset
        compose.runOnIdle { content += paragraph.repeat(6) }
        compose.waitForIdle()
        val pinnedAfter = compose.onNodeWithTag("two-mode-stream-edge")
            .getUnclippedBoundsInRoot().bottom
        assertEquals("At latest, keep the clipping edge stationary and scroll the text instead",
            pinnedEdge.value, pinnedAfter.value, 32f)
        assertTrue("The LazyColumn must actually scroll when latest is pinned",
            list.listState.firstVisibleItemScrollOffset > offsetBeforeFollowing)
    }

    @Test fun completedAssistantReleasesClippingAndKeepsToolbarBelowTheFinalText() {
        val prefix = "An answer begins here and is rendered incrementally.\n\n"
        val finalText = prefix + "The completed answer ends with a final, fully visible paragraph."
        val stop = StreamFreezeSignal()
        val live = MutableStateFlow<ActiveChatStreamState?>(ActiveChatStreamState(
            conversationId = "completed-chat", messageId = "reply", generationId = 91L,
            revision = 1L, status = ChatStreamStatus.STREAMING,
            content = StreamTextSnapshot.of(prefix), reasoning = StreamTextSnapshot.of(),
            stopSignal = stop, lastCommitMetrics = null,
            cumulativeMetrics = StreamMetricsSnapshot()
        ))
        var streaming by mutableStateOf(true)
        var message by mutableStateOf(StoredMessage("reply", ChatRole.MODEL, listOf(MessageVersion())))
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                message = message, isStreaming = streaming, onCopy = {}, onRegenerate = {},
                onEdit = {}, onDelete = {}, onRead = {}, onSwitchVersion = {},
                conversationId = "completed-chat", streamStateFlow = live
            )
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("An answer begins here", substring = true,
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            message = message.copy(versions = listOf(MessageVersion(content = finalText)))
            streaming = false // The stale stream is intentionally still published.
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("The completed answer ends", substring = true,
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val lastParagraph = compose.onAllNodesWithText("The completed answer ends", substring = true,
            useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val copyLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.common_copy)
        val toolbar = compose.onNodeWithContentDescription(copyLabel).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Completion must release the clip and leave the message toolbar below " +
            "the actual final text", lastParagraph.bottom <= toolbar.top + 1f)
    }

    @Test fun contentGrowsIntoClippedViewportWithoutJumpingOrReversing() {
        var contentHeight by mutableStateOf(40.dp)
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxWidth().height(450.dp).background(Color.Black).testTag("test-canvas")) {
                StreamGrowthViewport(Modifier.testTag("stream-growth-viewport")) {
                    Box(Modifier.fillMaxWidth().height(contentHeight).background(Color.White))
                }
            }
        } }
        // Let the Activity attach the first composition before pausing the animation clock.
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val initial = viewportHeight()
        assertTrue(initial > 0f)

        compose.runOnIdle { contentHeight = 240.dp }
        compose.mainClock.advanceTimeBy(48L)
        val first = viewportHeight()
        assertTrue("The boundary must grow gradually, not jump to full height", first > initial)

        // A real scissor/clip must hide the already laid out white child below the edge.
        val canvas = compose.onNodeWithTag("test-canvas").captureToImage().toPixelMap()
        val beyondEdge = (first + 12f).toInt()
        assertTrue("The test must sample inside the unrevealed child", beyondEdge < canvas.height)
        val pixel = canvas[canvas.width / 2, beyondEdge]
        assertEquals("Pixels below the moving edge must be genuinely clipped", Color.Black, pixel)

        var previous = first
        repeat(16) { index ->
            if (index == 3) compose.runOnIdle { contentHeight = 300.dp }
            compose.mainClock.advanceTimeBy(32L)
            val current = viewportHeight()
            assertTrue("The bottom edge moved up at frame $index", current >= previous - 1f)
            previous = current
        }
        compose.mainClock.advanceTimeBy(2_000L)
        val expanded = viewportHeight()
        assertTrue("The complete content must eventually become visible", expanded > first)
        compose.runOnIdle { contentHeight = 65.dp }
        compose.mainClock.advanceTimeBy(1_000L)
        assertEquals("Markdown reflow must not drag the boundary backward", expanded, viewportHeight(), 1f)
    }
}
