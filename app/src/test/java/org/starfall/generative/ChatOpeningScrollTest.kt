package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.features.home.chat.ui.ChatRenderCaches
import org.starfall.generative.features.home.chat.ui.ChatScreen
import org.starfall.generative.features.home.chat.ui.ChatWelcome
import org.starfall.generative.features.home.chat.ui.OPENING_VEIL_TAG

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
class ChatOpeningScrollTest {
    @get:Rule val compose = createComposeRule()

    // The caches live for the whole process; every test starts from a cold app.
    @Before fun coldStart() = ChatRenderCaches.clearAll()

    private fun veilNodes() = compose.onAllNodesWithTag(OPENING_VEIL_TAG).fetchSemanticsNodes()

    private fun conversation(id: String) = Conversation(
        id = id, title = id, createdAt = 0, updatedAt = 0,
        messages = listOf(StoredMessage(
            id = "$id-answer", role = ChatRole.MODEL,
            versions = listOf(MessageVersion(content =
                (1..50).joinToString("\n\n") { "Paragraph $it of the saved answer." } +
                    "\n\nEnd of $id"
            ))
        ))
    )

    private fun show(
        current: androidx.compose.runtime.State<Conversation?>,
        loadingId: androidx.compose.runtime.State<String?> = mutableStateOf(null),
        error: androidx.compose.runtime.State<String?> = mutableStateOf(null)
    ) {
        if (loadingId.value != null) compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    conversation = current.value, isGenerating = false,
                    loadingConversationId = loadingId.value,
                    generatingConversationId = null, chatError = error.value,
                    providers = emptyList(), selectedProviderId = "", selectedModelName = "",
                    onNewChat = {}, onSendMessage = { _, _ -> false }, onSendMedia = { false },
                    onStopGenerating = {}, onOpenDrawer = {}, onOpenSettings = {},
                    onRegenerate = {}, onEditMessage = { _, _, _ -> false },
                    onDeleteMessage = {}, onDeleteMessageVersion = {},
                    onSwitchVersion = { _, _ -> }, onSelectModel = { _, _ -> },
                    summaryProgress = null, onSetReasoningEffort = {},
                    onStartConversationSummary = { false }, onSummaryRoleChange = {},
                    onDeleteSummary = {}, onReadMessage = { _, _ -> }, autoScroll = false
                )
            }
        }
    }

    private fun assertEndVisible(id: String) {
        // Only a pending history read can veil the chat.
        compose.waitUntil(10_000) { veilNodes().isEmpty() }
        // Parsing runs off-main; allow it to finish before checking the settled layout.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("End of $id", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val end = compose.onNodeWithText("End of $id", useUnmergedTree = true)
        end.assertIsDisplayed()
        val inputTop = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.top
        assertTrue("The end of the answer must be above the input", end.fetchSemanticsNode().boundsInRoot.bottom <= inputTop)
    }

    @Test fun reopeningAndSwitchingChatsReachesTheEndAfterMarkdownLoads() {
        val current = mutableStateOf<Conversation?>(conversation("first"))
        show(current)
        assertEndVisible("first")
        compose.runOnIdle { current.value = conversation("second") }
        assertEndVisible("second")
        compose.runOnIdle { current.value = conversation("first") }
        assertEndVisible("first")
    }

    @Test fun savedHistoryOpensWithoutAnAssetReadinessVeilEvenAfterCacheEviction() {
        val current = mutableStateOf<Conversation?>(conversation("first"))
        show(current)
        assertTrue("Loaded history never waits behind a renderer readiness veil", veilNodes().isEmpty())
        assertEndVisible("first")
        compose.runOnIdle {
            ChatRenderCaches.clearAll()
            current.value = conversation("second")
        }
        assertTrue(veilNodes().isEmpty())
        assertEndVisible("second")
    }

    @Test fun delayedMessagesOpenAtTheEndAndManualScrollingKeepsControl() {
        val saved = conversation("delayed")
        val current = mutableStateOf<Conversation?>(saved.copy(messages = emptyList()))
        show(current)
        compose.runOnIdle { current.value = saved }
        assertEndVisible("delayed")
        val list = compose.onNodeWithTag("chat-message-list")
        fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val bottom = position()
        list.performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.35f),
                androidx.compose.ui.geometry.Offset(width / 2f, height * 0.45f), 1_000)
        }
        compose.waitForIdle()
        assertTrue("Opening must not pull the user back to the bottom", position() < bottom)
        compose.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()
        // Larger travel still exposes the actual screen action.
        repeat(4) { list.performTouchInput { swipeDown() } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()
    }

    @Test fun saveFailureAppearsInMessageListWithoutResizingComposer() {
        val current = mutableStateOf<Conversation?>(conversation("with-error"))
        val error = mutableStateOf<String?>(null)
        show(current, error = error)
        assertEndVisible("with-error")
        val before = compose.onNodeWithTag("chat-bottom-panel").fetchSemanticsNode().boundsInRoot

        compose.runOnIdle {
            error.value = org.starfall.generative.features.home.chat.logic.SAVE_CONVERSATION_ERROR_PREFIX +
                " Storage unavailable"
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Storage unavailable", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Storage unavailable", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("error-container-copy").assertExists()
        compose.onNodeWithTag("error-container-toggle").assertExists()
        val after = compose.onNodeWithTag("chat-bottom-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("A save error in chat history must not change the composer height",
            kotlin.math.abs(before.top - after.top) <= 1f)
    }

    @Test fun emptyChatShowsTheAppLogoAndTaglineWithoutAHistoryVeil() {
        show(mutableStateOf(null))
        compose.onNodeWithTag("chat-welcome-logo").assertExists()
        compose.onNodeWithText("Generative").assertExists()
        compose.onNodeWithText("Your AI. Your way.").assertExists()
        assertTrue("A blank chat is not a disk read", veilNodes().isEmpty())
    }

    @Test fun welcomeFadesInAgainWhenReturningToTheChat() {
        val foreground = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                ChatWelcome(isTemporary = false, foreground = foreground.value, modifier = Modifier.fillMaxSize())
            }
        }
        compose.mainClock.advanceTimeBy(550)
        compose.onNodeWithTag("chat-welcome-logo").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { foreground.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("chat-welcome").assertDoesNotExist()
        compose.runOnIdle { foreground.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("chat-welcome-logo").assertIsDisplayed()
    }

    @Test fun pendingHistoryReadWithoutVisibleMessagesUsesTheSameVeil() {
        val current = mutableStateOf<Conversation?>(null)
        val loadingId = mutableStateOf<String?>("disk-history")
        show(current, loadingId)
        assertTrue("The pending disk read must use the history veil even with no messages", veilNodes().isNotEmpty())
        // Welcome fades in behind the veil; allow its entry animation to mount the artwork.
        compose.mainClock.advanceTimeBy(550)
        compose.onNodeWithTag("chat-welcome-logo").assertExists()
        // The veil covers chat content, not the input area.
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        compose.onNode(hasSetTextAction()).performTouchInput { click() }
        compose.onNode(hasSetTextAction()).performTextInput("input remains usable")
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasSetTextAction()).assertTextContains("input remains usable")

        compose.runOnIdle {
            current.value = conversation("disk-history")
            loadingId.value = null
        }
        compose.mainClock.autoAdvance = true
        assertEndVisible("disk-history")
    }

    @Test fun pendingReadVeilsAnExistingChatWithoutUnmountingItsViewport() {
        val current = mutableStateOf<Conversation?>(conversation("previous"))
        val loadingId = mutableStateOf<String?>("next-history")
        show(current, loadingId)
        compose.onNodeWithTag("chat-message-list").assertExists()
        assertTrue("A real pending history read must veil existing messages", veilNodes().isNotEmpty())
        compose.runOnIdle {
            current.value = conversation("next")
            loadingId.value = null
        }
        compose.mainClock.autoAdvance = true
        assertEndVisible("next")
    }

    @Test fun historyVeilHasNoDeadlineWhileSelectionIsStillLoading() {
        val current = mutableStateOf<Conversation?>(conversation("slow-history"))
        val loadingId = mutableStateOf<String?>("still-reading")
        compose.mainClock.autoAdvance = false
        show(current, loadingId)
        assertTrue(veilNodes().isNotEmpty())
        // Go well beyond the removed four-second deadline while a history load remains pending.
        repeat(350) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("The history veil must not time out", veilNodes().isNotEmpty())
        compose.runOnIdle { loadingId.value = null }
        compose.mainClock.autoAdvance = true
        assertEndVisible("slow-history")
    }

}
