package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState
import org.starfall.generative.features.home.chat.model.ChatStreamStatus
import org.starfall.generative.features.home.chat.model.StreamFreezeSignal
import org.starfall.generative.features.home.chat.model.StreamMetricsSnapshot
import org.starfall.generative.features.home.chat.model.StreamTextSnapshot
import org.starfall.generative.shared.model.ToolActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.ui.ChatRenderCaches
import org.starfall.generative.features.home.chat.ui.ChatScreen
import org.starfall.generative.features.home.chat.ui.OPENING_VEIL_TAG
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage

/** Integration coverage for sent-message placement and opaque assistant rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
class ChatSentMessageScrollTest {
    @get:Rule val compose = createComposeRule()

    @Before fun coldStart() = ChatRenderCaches.clearAll()

    private class Env {
        val conversation = mutableStateOf<Conversation?>(null)
        val queued = mutableStateOf<List<StoredMessage>>(emptyList())
        val generating = mutableStateOf(false)
        val stream = MutableStateFlow<ActiveChatStreamState?>(null)
    }

    private fun message(id: String, role: ChatRole, text: String) =
        StoredMessage(id, role, listOf(MessageVersion(content = text)))

    private fun history(turns: Int) = (1..turns).flatMap { i ->
        listOf(
            message("u$i", ChatRole.USER, "Question $i"),
            message("m$i", ChatRole.MODEL, (1..6).joinToString("\n\n") { "Answer $i paragraph $it." })
        )
    }

    private fun conversation(messages: List<StoredMessage>) =
        Conversation(id = "chat", title = "chat", createdAt = 0, updatedAt = 0,
            messages = messages, structuralRevision = messages.size.toLong())

    private fun show(
        env: Env,
        autoScroll: Boolean = true,
        onSend: (String, List<String>) -> Boolean = { _, _ -> false }
    ) {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    conversation = env.conversation.value, isGenerating = env.generating.value,
                    generatingConversationId = env.conversation.value?.id, chatError = null,
                    providers = emptyList(), selectedProviderId = "", selectedModelName = "",
                    onNewChat = {}, onSendMessage = onSend, onSendMedia = { false },
                    onStopGenerating = {}, onOpenDrawer = {}, onOpenSettings = {},
                    onRegenerate = {}, onEditMessage = { _, _, _ -> false },
                    onDeleteMessage = {}, onDeleteMessageVersion = {},
                    onSwitchVersion = { _, _ -> }, onSelectModel = { _, _ -> },
                    summaryProgress = null, onSetReasoningEffort = {},
                    onStartConversationSummary = { false }, onSummaryRoleChange = {},
                    onDeleteSummary = {}, onReadMessage = { _, _ -> },
                    queuedMessages = env.queued.value, autoScroll = autoScroll,
                    activeStream = env.stream
                )
            }
        }
    }

    private fun veilNodes() = compose.onAllNodesWithTag(OPENING_VEIL_TAG).fetchSemanticsNodes()

    /** Step native animations and allow existing asynchronous renderers to publish. */
    private fun frames(count: Int = 40) = repeat(count) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        Thread.sleep(4) // markdown parsing runs on real background threads
    }

    private fun openChat(env: Env, messages: List<StoredMessage>) {
        env.conversation.value = conversation(messages)
        compose.mainClock.autoAdvance = false
        show(env)
        var guard = 0
        while (veilNodes().isNotEmpty() && guard++ < 800) frames(1)
        assertTrue("the chat never finished opening", veilNodes().isEmpty())
        frames()
    }

    private fun update(change: () -> Unit) {
        compose.runOnIdle { change() }
        frames()
    }

    private fun top(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.top

    private fun rootHeight() = compose.onRoot().fetchSemanticsNode().boundsInRoot.height

    private fun inputTop() = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.top

    /** The message sits just under the app bar, with the whole area below it for the reply. */
    private fun assertUnderAppBar(text: String) {
        val textTop = top(text)
        val appBarBottom = compose.onNodeWithTag("chat-app-bar").fetchSemanticsNode().boundsInRoot.bottom
        val maxGapPx = with(compose.density) { 36.dp.toPx() }
        // Includes the user's normal 6dp outer and 12dp bubble padding, with only a small gap.
        assertTrue("User message should sit just under AppBar: gap=${textTop - appBarBottom}px",
            textTop in appBarBottom..(appBarBottom + maxGapPx))
        assertTrue("room below for the reply", textTop < inputTop() - 0.4f * rootHeight())
    }

    private fun sendWhilePending(env: Env, sentId: String, queuedId: String = sentId) {
        // The previous answer is still streaming and the message waits in the queue.
        update {
            env.generating.value = true
            env.queued.value = listOf(message(queuedId, ChatRole.USER, "Pending question").copy(isQueued = true))
        }
        // The answer ends, the queue drains, the message is sent and its reply starts.
        update {
            env.queued.value = emptyList()
            env.conversation.value = conversation(
                env.conversation.value!!.messages +
                    message(sentId, ChatRole.USER, "Pending question") + message("reply", ChatRole.MODEL, "")
            )
        }
        frames()
    }

    @Test fun firstSendInBrandNewChatAnchorsUserUnderAppBar() {
        val env = Env()
        compose.mainClock.autoAdvance = false
        show(env, onSend = { text, _ ->
            env.conversation.value = conversation(listOf(
                message("first-user", ChatRole.USER, text),
                message("first-reply", ChatRole.MODEL, "")
            ))
            true
        })
        compose.onNode(hasSetTextAction()).performTextInput("First message")
        frames(20)
        compose.onNodeWithContentDescription("Send message").performClick()
        frames(110)
        compose.runOnIdle { assertTrue("Submission must reach the conversation", env.conversation.value?.messages?.any { it.role == ChatRole.USER && it.content == "First message" } == true) }
        assertUnderAppBar("First message")
    }

    @Test fun firstSendInExistingEmptyChatAnchorsUserUnderAppBar() {
        val env = Env().apply { conversation.value = conversation(emptyList()) }
        compose.mainClock.autoAdvance = false
        show(env, onSend = { text, _ ->
            env.conversation.value = conversation(listOf(
                message("first-user", ChatRole.USER, text),
                message("first-reply", ChatRole.MODEL, "")
            ))
            true
        })
        compose.onNode(hasSetTextAction()).performTextInput("First message")
        frames(20)
        compose.onNodeWithContentDescription("Send message").performClick()
        frames(110)
        compose.runOnIdle { assertTrue("Submission must reach the conversation", env.conversation.value?.messages?.any { it.role == ChatRole.USER && it.content == "First message" } == true) }
        assertUnderAppBar("First message")
    }

    @Test fun directSendIntoExistingChatAnchorsUserUnderAppBar() {
        val env = Env().apply { conversation.value = conversation(history(8)) }
        compose.mainClock.autoAdvance = false
        show(env, onSend = { text, _ ->
            env.conversation.value = conversation(env.conversation.value!!.messages + listOf(
                message("new-user", ChatRole.USER, text),
                message("new-reply", ChatRole.MODEL, "")
            ))
            true
        })
        var guard = 0
        while (veilNodes().isNotEmpty() && guard++ < 800) frames(1)
        assertTrue("The history should settle before sending", veilNodes().isEmpty())
        compose.onNode(hasSetTextAction()).performTextInput("Fresh question")
        frames(20)
        compose.onNodeWithContentDescription("Send message").performClick()
        frames(110)
        assertUnderAppBar("Fresh question")
    }

    @Test fun pendingMessageSentWithANewIdIsPlacedUnderTheAppBar() {
        val env = Env()
        openChat(env, history(10))
        sendWhilePending(env, sentId = "sent", queuedId = "queued-before-drain")
        assertUnderAppBar("Pending question")
    }

    @Test fun pendingMessageHandedOverWithItsQueuedIdIsPlacedUnderTheAppBar() {
        val env = Env()
        openChat(env, history(10))
        sendWhilePending(env, sentId = "same-id")
        assertUnderAppBar("Pending question")
    }

    @Test fun openingSavedChatDoesNotScrollPastTheAnswerIntoTheBlankTail() {
        val env = Env()
        val final = history(8) + listOf(
            message("last-user", ChatRole.USER, "Final question"),
            message("last-answer", ChatRole.MODEL, "Final visible answer.")
        )
        openChat(env, final)

        compose.onNodeWithText("Final visible answer.").assertIsDisplayed()
        val answerTop = top("Final visible answer.")
        val appBarBottom = compose.onNodeWithTag("chat-app-bar").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Answer must stay below the AppBar, not behind it: $answerTop",
            answerTop >= appBarBottom)
        assertTrue("Answer must be above the composer", answerTop < inputTop())
    }

    @Test fun aShortReplyStartingAndFinishingNeverMovesTheMessage() {
        val env = Env()
        openChat(env, history(10))
        sendWhilePending(env, sentId = "sent")
        val placed = top("Pending question")

        val sent = env.conversation.value!!.messages
        update { env.conversation.value = conversation(sent.dropLast(1) + message("reply", ChatRole.MODEL, "Short reply.")) }
        assertEquals("reply text arriving must not scroll", placed, top("Pending question"), 2f)

        update { env.generating.value = false }
        assertEquals("the end of the response must not scroll", placed, top("Pending question"), 2f)
        assertUnderAppBar("Pending question")
    }

    @Test fun theUserCanDragTheMessageAwayAndBackToTheAppBar() {
        val env = Env()
        openChat(env, history(10))
        sendWhilePending(env, sentId = "sent")
        val placed = top("Pending question")

        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.6f, durationMillis = 200) }
        frames()
        val moved = top("Pending question")
        assertTrue("dragging down moves it away: $placed -> $moved", moved > placed + 50f)

        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.05f, durationMillis = 200) }
        frames()
        assertTrue("it can be dragged back to the app bar", top("Pending question") < 0.25f * rootHeight())
    }

    @Test fun runningActionsBecomeVisibleBeforeCompletionWithoutChangingUpdatedAt() {
        val env = Env().apply {
            conversation.value = conversation(listOf(
                message("user", ChatRole.USER, "Run the search"),
                message("reply", ChatRole.MODEL, "")
            ))
            generating.value = true
        }
        compose.mainClock.autoAdvance = false
        show(env)
        frames(60)
        update {
            val chat = env.conversation.value!!
            val reply = chat.messages.last()
            env.conversation.value = chat.copy(messages = chat.messages.dropLast(1) + reply.copy(
                versions = listOf(reply.activeVersion.copy(toolActivity = listOf(
                    ToolActivity("live-action", "search_documents", status = "running")
                )))
            ))
        }
        // Tool events update the version without advancing updatedAt or structuralRevision.
        compose.onNodeWithText("search documents").assertIsDisplayed()
        compose.onNodeWithText("Generating...").assertIsDisplayed()
    }

    @Test fun completingAnActualStreamKeepsRenderedTextAndScrollPosition() {
        val env = Env().apply {
            conversation.value = conversation(history(10) + listOf(
                message("new-user", ChatRole.USER, "Latest question"),
                message("reply", ChatRole.MODEL, "")
            ))
            generating.value = true
        }
        val long = (1..60).joinToString("\n\n") { "Live paragraph $it." }
        val frozen = StreamFreezeSignal()
        env.stream.value = ActiveChatStreamState(
            conversationId = "chat", messageId = "reply", generationId = 3L,
            revision = 1L, status = ChatStreamStatus.STREAMING,
            content = StreamTextSnapshot.of(long), reasoning = StreamTextSnapshot.of(),
            stopSignal = frozen, lastCommitMetrics = null, cumulativeMetrics = StreamMetricsSnapshot()
        )
        compose.mainClock.autoAdvance = false
        show(env)
        frames(240)
        val list = compose.onNodeWithTag("chat-message-list")
        fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = position()
        update {
            env.conversation.value = env.conversation.value!!.copy(messages =
                env.conversation.value!!.messages.dropLast(1) + message("reply", ChatRole.MODEL, long)
            )
            env.generating.value = false
            env.stream.value = null
        }
        frames(60)
        assertTrue("Completion must not rewind the live scroll position", position() >= before - 60f)
        compose.onNodeWithText("Live paragraph 60.", useUnmergedTree = true).assertExists()
    }

    @Test fun finishingAStreamDoesNotSnapTheConversationToTheBottom() {
        val env = Env()
        openChat(env, history(10))
        sendWhilePending(env, sentId = "sent")
        val sent = env.conversation.value!!.messages
        val long = (1..100).joinToString("\n\n") { "Streaming passage $it." }
        update {
            env.conversation.value = conversation(sent.dropLast(1) + message("reply", ChatRole.MODEL, long))
        }
        val list = compose.onNodeWithTag("chat-message-list")
        fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val beforeFinish = position()

        // The old non-streaming LaunchedEffect called scrollToBottom() as soon as generation
        // changed to false, jumping to the end before the final Markdown layout moved it back.
        update { env.generating.value = false }
        assertTrue("Finishing must not rewind the position reached while streaming", position() >= beforeFinish - 60f)
    }

    @Test fun aLongReplyIsFollowedWithoutLosingTheEndOfIt() {
        val env = Env()
        openChat(env, history(10))
        sendWhilePending(env, sentId = "sent")

        val long = (1..80).joinToString("\n\n") { "Streamed paragraph $it." }
        val sent = env.conversation.value!!.messages
        update { env.conversation.value = conversation(sent.dropLast(1) + message("reply", ChatRole.MODEL, long)) }
        frames(240)
        update { env.generating.value = false }
        frames(60)

        compose.onNodeWithText("Streamed paragraph 80.", useUnmergedTree = true).assertIsDisplayed()
    }
}
