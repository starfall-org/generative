package org.starfall.generative

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.home.chat.logic.SAVE_CONVERSATION_ERROR_PREFIX
import org.starfall.generative.features.home.chat.ui.AssistantMessageCard
import org.starfall.generative.features.home.chat.ui.ChatScreen
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.generation.OUTPUT_TOKEN_LIMIT_REACHED

/** Exercises the two real chat call sites of the shared error surface. */
@RunWith(AndroidJUnit4::class)
class ChatErrorContainerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clipboardText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    private fun showChat(
        conversation: Conversation,
        error: State<String?>,
        generating: Boolean = false,
        onSend: (String, List<String>) -> Boolean = { _, _ -> false },
        onStop: () -> Unit = {}
    ) {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    conversation = conversation,
                    isGenerating = generating,
                    generatingConversationId = conversation.id.takeIf { generating },
                    chatError = error.value,
                    providers = emptyList(),
                    selectedProviderId = "",
                    selectedModelName = "",
                    onSendMessage = onSend,
                    onSendMedia = { false },
                    onStopGenerating = onStop,
                    onOpenDrawer = {}, onNewChat = {}, onOpenSettings = {},
                    onRegenerate = {}, onEditMessage = { _, _, _ -> false },
                    onDeleteMessage = {}, onDeleteMessageVersion = {},
                    onSwitchVersion = { _, _ -> }, onSelectModel = { _, _ -> },
                    summaryProgress = null, onSetReasoningEffort = {},
                    onStartConversationSummary = { false }, onSummaryRoleChange = {},
                    onDeleteSummary = {}, onReadMessage = { _, _ -> }
                )
            }
        }
    }

    @Test fun assistantGenerationErrorSupportsRetryExpansionAndFullCopy() {
        val error = (1..7).joinToString("\n") { "HTTP failure detail $it" }
        var retries = 0
        val message = StoredMessage(
            id = "failed-answer",
            role = ChatRole.MODEL,
            versions = listOf(MessageVersion(content = "Partial answer", generationError = error))
        )

        compose.setContent {
            MaterialTheme {
                AssistantMessageCard(
                    message = message,
                    isStreaming = false,
                    onCopy = {},
                    onRegenerate = {},
                    onRetryError = { retries++ },
                    onEdit = {},
                    onDelete = {},
                    onRead = {},
                    onSwitchVersion = {}
                )
            }
        }

        compose.onNodeWithText("Partial answer", substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.message_generation_error)).assertIsDisplayed()
        compose.onNodeWithTag("error-container-toggle").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.message_collapse_error))
            .assertExists()
        compose.onNodeWithTag("error-container-copy").performClick()
        compose.runOnIdle { assertEquals(error, clipboardText()) }
        compose.onNodeWithText(context.getString(R.string.common_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun assistantTokenLimitShowsCompactYellowWarningWithoutLosingPartialText() {
        var retries = 0
        val message = StoredMessage(
            id = "limit-answer",
            role = ChatRole.MODEL,
            versions = listOf(MessageVersion(
                content = "Partial answer",
                reasoningContent = "Partial thinking",
                generationError = OUTPUT_TOKEN_LIMIT_REACHED
            ))
        )

        compose.setContent {
            MaterialTheme {
                AssistantMessageCard(
                    message = message,
                    isStreaming = false,
                    onCopy = {},
                    onRegenerate = {},
                    onRetryError = { retries++ },
                    onEdit = {},
                    onDelete = {},
                    onRead = {},
                    onSwitchVersion = {}
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.message_generation_warning)).assertIsDisplayed()
        compose.onNodeWithText("Partial answer", substring = true).assertExists()
        compose.onNodeWithText(OUTPUT_TOKEN_LIMIT_REACHED).assertIsDisplayed()
        compose.onNodeWithTag("error-container-toggle").assertDoesNotExist()
        compose.onNodeWithTag("error-container-copy").performClick()
        compose.runOnIdle { assertEquals(OUTPUT_TOKEN_LIMIT_REACHED, clipboardText()) }
        compose.onNodeWithText(context.getString(R.string.common_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun chatSaveFailureUsesSharedErrorContainerInMessageList() {
        val message = "disk full"
        val expected = "${context.getString(R.string.chat_save_conversation_failed_prefix)} $message"
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    conversation = Conversation(
                        id = "chat-error-test", title = "Chat", createdAt = 0L, updatedAt = 0L
                    ),
                    isGenerating = false,
                    generatingConversationId = null,
                    chatError = "$SAVE_CONVERSATION_ERROR_PREFIX $message",
                    providers = emptyList(),
                    selectedProviderId = "",
                    selectedModelName = "",
                    onSendMessage = { _, _ -> false },
                    onSendMedia = { false },
                    onStopGenerating = {},
                    onOpenDrawer = {},
                    onNewChat = {},
                    onOpenSettings = {},
                    onRegenerate = {},
                    onEditMessage = { _, _, _ -> false },
                    onDeleteMessage = {},
                    onDeleteMessageVersion = {},
                    onSwitchVersion = { _, _ -> },
                    onSelectModel = { _, _ -> },
                    summaryProgress = null,
                    onSetReasoningEffort = {},
                    onStartConversationSummary = { false },
                    onSummaryRoleChange = {},
                    onDeleteSummary = {},
                    onReadMessage = { _, _ -> }
                )
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("error-container-dismiss").fetchSemanticsNodes().isNotEmpty()
        }
        // Even a conversation without messages shows the save failure inside the chat list.
        compose.onNodeWithTag("chat-message-list").assertExists()
        compose.onNodeWithTag("chat-save-error")
            .assertExists().assert(hasAnyAncestor(hasTestTag("chat-message-list")))
        compose.onNodeWithText(expected).assertIsDisplayed()
        compose.onNodeWithTag("error-container-toggle").assertExists()
        compose.onNodeWithTag("error-container-copy").performClick()
        compose.runOnIdle { assertEquals(expected, clipboardText()) }
        compose.onNodeWithTag("error-container-dismiss").performClick()
        compose.onNodeWithText(expected).assertDoesNotExist()
    }

    @Test fun saveFailureAfterExistingMessagesStaysInsideListAndDoesNotResizeComposer() {
        val conversation = Conversation(
            id = "chat-save-with-history", title = "Chat", createdAt = 0L, updatedAt = 0L,
            messages = listOf(StoredMessage(
                id = "question", role = ChatRole.USER,
                versions = listOf(MessageVersion(content = "An earlier question"))
            ))
        )
        val failure = mutableStateOf<String?>(null)
        showChat(conversation, failure)
        compose.waitForIdle()
        val composerTop = compose.onNodeWithTag("chat-bottom-panel").fetchSemanticsNode().boundsInRoot.top
        val details = (1..20).joinToString("\n") { "Room transaction failure $it" }
        val localized = context.getString(R.string.chat_save_conversation_failed_prefix) + " " + details

        compose.runOnIdle { failure.value = "$SAVE_CONVERSATION_ERROR_PREFIX $details" }
        compose.onNodeWithTag("chat-message-list").performScrollToIndex(0)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("chat-save-error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat-save-error")
            .assertExists().assert(hasAnyAncestor(hasTestTag("chat-message-list")))
        compose.onNodeWithText("An earlier question").assertExists()
        val afterTop = compose.onNodeWithTag("chat-bottom-panel").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Save failure must not shift InputArea", kotlin.math.abs(afterTop - composerTop) <= 1f)

        compose.onNodeWithTag("error-container-toggle").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.message_collapse_error)).assertExists()
        compose.onNodeWithTag("error-container-copy").performClick()
        compose.runOnIdle { assertEquals(localized, clipboardText()) }
        compose.onNodeWithTag("error-container-dismiss").performClick()
        compose.onNodeWithTag("chat-save-error").assertDoesNotExist()
        compose.onNodeWithText("An earlier question").assertExists()
    }

    @Test fun saveFailureDoesNotChangeStopOrQueuedSendControl() {
        val error = mutableStateOf<String?>("$SAVE_CONVERSATION_ERROR_PREFIX database locked")
        val conversation = Conversation(id = "chat-save-generating", title = "Chat", createdAt = 0L, updatedAt = 0L)
        var stopped = 0
        var sent = 0
        showChat(conversation, error, generating = true,
            onSend = { _, _ -> sent++; true }, onStop = { stopped++ })

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("chat-save-error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat-save-error")
            .assert(hasAnyAncestor(hasTestTag("chat-message-list")))
        compose.onNodeWithContentDescription(context.getString(R.string.chat_stop_generation))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, stopped) }

        compose.onNode(hasSetTextAction()).performTextInput("Continue after this")
        compose.onNodeWithContentDescription(context.getString(R.string.chat_stop_generation))
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_send_message))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, sent) }
    }
}
