package org.starfall.generative

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.starfall.generative.features.home.chat.model.*
import org.starfall.generative.features.home.chat.ui.*
import org.starfall.generative.shared.model.*

/** Measure native scroll intents and shell recompositions, not unreliable wall-clock time. */
@RunWith(AndroidJUnit4::class)
class ChatStreamingScrollPerformanceDeviceRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Before fun reset() {
        ChatRenderCaches.clearAll()
        ChatRenderMetrics.resetForTests()
    }

    private fun messages() = listOf(
        StoredMessage("user", ChatRole.USER, listOf(MessageVersion(content = "Question"))),
        StoredMessage("reply", ChatRole.MODEL, listOf(MessageVersion()))
    )

    private val freeze = StreamFreezeSignal()

    private fun stream(text: String, revision: Long, status: ChatStreamStatus = ChatStreamStatus.STREAMING) =
        ActiveChatStreamState(
            conversationId = "chat", messageId = "reply", generationId = 1L,
            revision = revision, status = status,
            content = StreamTextSnapshot.of(text), reasoning = StreamTextSnapshot.of(),
            stopSignal = freeze, lastCommitMetrics = null,
            cumulativeMetrics = StreamMetricsSnapshot()
        )

    @Test fun autoScrollOffNeverFollowsGrowthButOnFollowsOnlyMeasuredNewContent() {
        val messages = messages()
        val state = ChatMessageListState(initialMessages = messages)
        var autoScroll by mutableStateOf(false)
        var streaming by mutableStateOf(true)
        var replyHeight by mutableStateOf(80.dp)
        val live = MutableStateFlow<ActiveChatStreamState?>(stream("first", 1L))
        compose.setContent { MaterialTheme {
            ChatMessageList(state, messages, streaming, autoScroll, false, live, 80.dp, 100.dp) { msg ->
                Column(Modifier.fillMaxWidth().height(if (msg.id == "reply") replyHeight else 40.dp)) {
                    Text(msg.id)
                }
            }
        } }
        compose.waitForIdle()
        val baseline = state.requestCount

        compose.runOnIdle { replyHeight = 420.dp; live.value = stream("growing", 2L) }
        compose.waitForIdle()
        assertEquals("Disabled auto-scroll cannot emit a follow request", baseline, state.requestCount)

        val positionBeforeFollowing = state.listState.firstVisibleItemIndex to
            state.listState.firstVisibleItemScrollOffset
        compose.runOnIdle { autoScroll = true; replyHeight = 560.dp; live.value = stream("more text", 3L) }
        compose.waitUntil(10_000) {
            val position = state.listState.firstVisibleItemIndex to
                state.listState.firstVisibleItemScrollOffset
            position != positionBeforeFollowing
        }
        compose.waitForIdle()
        val followedRequests = state.requestCount
        val followedPosition = state.listState.firstVisibleItemIndex to
            state.listState.firstVisibleItemScrollOffset
        assertEquals("Native anchoring should not enqueue one animation per growth frame",
            baseline, followedRequests)

        compose.runOnIdle { autoScroll = false; replyHeight = 640.dp; live.value = stream("off again", 4L) }
        compose.waitForIdle()
        assertEquals("Switching auto-scroll off stops further native scroll movement",
            followedPosition, state.listState.firstVisibleItemIndex to
                state.listState.firstVisibleItemScrollOffset)

        compose.runOnIdle {
            streaming = false
            live.value = stream("finished", 5L, ChatStreamStatus.COMPLETED)
            replyHeight = 700.dp
        }
        compose.waitForIdle()
        assertEquals("Completion must not move the list after auto-scroll is disabled",
            followedPosition, state.listState.firstVisibleItemIndex to
                state.listState.firstVisibleItemScrollOffset)
    }

    @Test fun aManualDragDisablesFollowUntilTheUserReturnsToLatest() {
        val messages = messages()
        val state = ChatMessageListState(initialMessages = messages)
        var height by mutableStateOf(1600.dp)
        val live = MutableStateFlow<ActiveChatStreamState?>(stream("start", 1L))
        compose.setContent { MaterialTheme {
            ChatMessageList(state, messages, true, true, false, live, 80.dp, 100.dp) { msg ->
                Box(Modifier.fillMaxWidth().height(if (msg.id == "reply") height else 40.dp)) { Text(msg.id) }
            }
        } }
        compose.onNodeWithTag("chat-message-list").performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue("User dragging the LazyColumn detaches follow", state.detached) }
        val afterDrag = state.requestCount
        compose.runOnIdle { height = 1800.dp; live.value = stream("new chunk", 2L) }
        compose.waitForIdle()
        assertEquals(afterDrag, state.requestCount)
        compose.runOnIdle { state.jump() }
        compose.waitUntil(10_000) { !state.detached }
        assertTrue(state.requestCount > afterDrag)
    }

    @Test fun dozensOfStreamPublicationsDoNotRecomposeTheUnchangedAssistantCardShell() {
        val stored = messages().last()
        val live = MutableStateFlow<ActiveChatStreamState?>(stream("Initial plain text.", 1L))
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                message = stored, isStreaming = true, onCopy = {},
                onRegenerate = {}, onEdit = {}, onDelete = {}, onRead = {},
                onSwitchVersion = {}, conversationId = "chat", streamStateFlow = live
            )
        } }
        compose.waitForIdle()
        val initialShellCompositions = ChatRenderMetrics.messageRecompositionCountForTests()
        assertTrue(initialShellCompositions >= 1)

        repeat(24) { index ->
            compose.runOnIdle {
                live.value = stream("Initial plain text. Added chunk ${index + 1}.", index + 2L)
            }
            compose.waitForIdle()
        }
        assertEquals(
            "Text collector should update the renderer, not the static assistant shell on every chunk",
            initialShellCompositions, ChatRenderMetrics.messageRecompositionCountForTests()
        )
    }
}
