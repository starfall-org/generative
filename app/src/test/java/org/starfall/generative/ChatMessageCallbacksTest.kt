package org.starfall.generative

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.starfall.generative.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState
import org.starfall.generative.features.home.chat.model.ChatStreamStatus
import org.starfall.generative.features.home.chat.model.StreamFreezeSignal
import org.starfall.generative.features.home.chat.model.StreamMetricsSnapshot
import org.starfall.generative.features.home.chat.model.StreamTextSnapshot
import org.starfall.generative.features.home.chat.ui.AssistantMessageCard
import org.starfall.generative.features.home.chat.ui.assistantCopyText
import org.starfall.generative.features.home.chat.ui.ChatRenderMetrics
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.features.home.chat.ui.rememberChatAction
import org.starfall.generative.features.home.chat.ui.rememberChatValueAction

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageCallbacksTest {
    @get:Rule val compose = createComposeRule()

    @Test fun callbackIdentitySurvivesRecompositionAndInvokesLatestAction() {
        var generation by mutableStateOf(0)
        var invoked = -1
        var callback: (() -> Unit)? = null
        compose.setContent {
            val current = generation
            callback = rememberChatAction { invoked = current }
        }
        compose.waitForIdle()
        val original = callback!!
        compose.runOnIdle { generation = 1 }
        compose.waitForIdle()
        assertSame(original, callback)
        compose.runOnIdle { original() }
        assertEquals(1, invoked)
    }

    @Test fun unchangedAssistantCardSkipsParentGenerationTransition() {
        ChatRenderMetrics.resetForTests()
        val oldReply = StoredMessage("earlier", ChatRole.MODEL, listOf(MessageVersion()))
        var generating by mutableStateOf(true)
        var latestCopy: (() -> Unit)? = null
        var observedGeneration: Boolean? = null
        compose.setContent {
            val isGenerating = generating
            val onCopy = rememberChatAction { observedGeneration = isGenerating }
            latestCopy = onCopy
            MaterialTheme {
                AssistantMessageCard(
                    message = oldReply,
                    isStreaming = false,
                    onCopy = onCopy,
                    onRegenerate = rememberChatAction {},
                    onEdit = rememberChatAction {},
                    onDelete = rememberChatAction {},
                    onRead = rememberChatAction {},
                    onSwitchVersion = rememberChatValueAction { _: Int -> }
                )
            }
        }
        compose.waitForIdle()
        val renderedBefore = ChatRenderMetrics.messageRecompositionCountForTests()
        compose.runOnIdle { generating = false }
        compose.waitForIdle()
        assertEquals("An unchanged historical card should be skipped", renderedBefore,
            ChatRenderMetrics.messageRecompositionCountForTests())
        compose.runOnIdle { latestCopy!!() }
        assertEquals(false, observedGeneration)
    }

    @Test fun visibleToolsAndFilesShowToolbarButInternalOnlyAndEmptyRepliesDoNot() {
        val versions = listOf(
            MessageVersion(toolActivity = listOf(ToolActivity(id = "search", name = "web_search"))),
            MessageVersion(toolActivity = listOf(ToolActivity(id = "file", name = "send_file",
                files = listOf("tool-file:document.pdf")))),
            MessageVersion(files = listOf("image.png")),
            MessageVersion(toolActivity = listOf(ToolActivity(id = "internal", name = "server: connect"))),
            MessageVersion(toolActivity = listOf(ToolActivity(id = "pending", name = "send_file"))),
            MessageVersion(),
            MessageVersion(generationInterrupted = true)
        )
        var selected by mutableStateOf(0)
        var copied = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                message = StoredMessage("reply", ChatRole.MODEL, listOf(versions[selected])),
                isStreaming = false, onCopy = { copied++ }, onRegenerate = {},
                onEdit = {}, onDelete = {}, onRead = {}, onSwitchVersion = {}
            )
        } }
        repeat(3) { index ->
            compose.onNodeWithTag("assistant-empty-content").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.common_copy))
                .assertExists().performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.common_share)).assertExists()
            compose.runOnIdle {
                assertEquals(index + 1, copied)
                selected++
            }
        }
        // An internal handshake, a file call without media, and a completely
        // empty generation must not create a bubble, toolbar, or edit control.
        repeat(4) { index ->
            compose.onNodeWithTag("assistant-empty-content").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.common_copy)).assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.common_more)).assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.message_regenerate)).assertDoesNotExist()
            if (index < 3) compose.runOnIdle { selected++ }
        }
    }

    @Test fun interruptedThinkingOnlyMessageHasFunctionalToolbar() {
        val reasoning = "**Raw thinking**\n# Not formatted"
        val message = StoredMessage("thinking-only", ChatRole.MODEL,
            listOf(MessageVersion(reasoningContent = reasoning, generationInterrupted = true)))
        val context = ApplicationProvider.getApplicationContext<Context>()
        var copiedText = ""
        var copyCalls = 0
        var editCalls = 0
        var regenerationCalls = 0
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                message = message, isStreaming = false,
                onCopy = { copiedText = assistantCopyText(message); copyCalls++ },
                onRegenerate = { regenerationCalls++ },
                onEdit = { editCalls++ }, onDelete = {}, onRead = {}, onSwitchVersion = {}
            )
        } }
        compose.onNodeWithTag("assistant-empty-content").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.common_copy))
            .assertExists().performClick()
        compose.runOnIdle {
            assertEquals("[ActionsBlock1]", copiedText)
            assertEquals(1, copyCalls)
            assertEquals(0, editCalls)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.common_share)).assertExists()
        // Even without a Content block, the existing toolbar's More > Edit works.
        compose.onNodeWithContentDescription(context.getString(R.string.common_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_edit)).performClick()
        compose.runOnIdle { assertEquals(1, editCalls) }
        compose.onNodeWithContentDescription(context.getString(R.string.message_regenerate)).performClick()
        compose.runOnIdle { assertEquals(1, regenerationCalls) }
    }

    @Test fun answeredMessageStillUsesNormalCopyInsteadOfThinkingFallback() {
        val message = StoredMessage("answered", ChatRole.MODEL,
            listOf(MessageVersion(content = "Final answer", reasoningContent = "Private reasoning")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        var copies = 0
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                message = message, isStreaming = false,
                onCopy = { copies++ }, onRegenerate = {},
                onEdit = {}, onDelete = {}, onRead = {}, onSwitchVersion = {}
            )
        } }
        compose.onNodeWithTag("assistant-empty-content").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.common_copy)).performClick()
        compose.runOnIdle { assertEquals(1, copies) }
    }

    @Test fun streamingAnswerChangesOnlyTheContentSubtreeNotTheAssistantCardShell() {
        val reply = StoredMessage("reply", ChatRole.MODEL, listOf(MessageVersion(content = "")))
        var text = StreamTextSnapshot.of("Started ")
        var revision = 0L
        fun state() = ActiveChatStreamState(
            conversationId = "chat", messageId = "reply", generationId = 1L,
            revision = revision, status = ChatStreamStatus.STREAMING,
            content = text, reasoning = StreamTextSnapshot.of(),
            stopSignal = StreamFreezeSignal(), lastCommitMetrics = null,
            cumulativeMetrics = StreamMetricsSnapshot()
        )
        val stream = MutableStateFlow<ActiveChatStreamState?>(state())
        ChatRenderMetrics.resetForTests()
        // The assertion concerns recomposition, not animation timing. A live response
        // may contain perpetual progress animations, so drive the clock explicitly.
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                message = reply, isStreaming = true, conversationId = "chat",
                streamStateFlow = stream, onCopy = {}, onRegenerate = {}, onEdit = {},
                onDelete = {}, onRead = {}, onSwitchVersion = {}
            )
        } }
        compose.waitForIdle()
        val before = ChatRenderMetrics.messageRecompositionCountForTests()
        repeat(5) { index ->
            compose.runOnIdle {
                revision++
                text = text.append("Line $index\n", revision)
                stream.value = state()
            }
            compose.waitForIdle()
        }
        assertEquals("Content stream must not invalidate the assistant-card shell", before,
            ChatRenderMetrics.messageRecompositionCountForTests())
        compose.mainClock.autoAdvance = true
    }

    @Test fun parameterizedCallbackAlsoUsesLatestMessageState() {
        var content by mutableStateOf("before")
        var result = ""
        var callback: ((Int) -> Unit)? = null
        compose.setContent {
            val latest = content
            callback = rememberChatValueAction { index: Int -> result = "$latest:$index" }
        }
        compose.waitForIdle()
        val original = callback!!
        compose.runOnIdle { content = "after" }
        compose.waitForIdle()
        assertSame(original, callback)
        compose.runOnIdle { original(2) }
        assertEquals("after:2", result)
    }
}
