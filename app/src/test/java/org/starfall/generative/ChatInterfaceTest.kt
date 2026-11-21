package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState
import org.starfall.generative.features.home.chat.ui.ChatInterface
import org.starfall.generative.features.home.chat.ui.ChatBackend
import org.starfall.generative.features.home.chat.ui.ChatScreen
import org.starfall.generative.features.home.shared.ui.LocalRemoteQuickActions
import org.starfall.generative.features.home.shared.ui.RemoteQuickActions
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ChatInterfaceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun displaysHostMessagesAndStreamWithoutChatScreen() {
        fun message(id: String, role: ChatRole, content: String) =
            StoredMessage(id, role, listOf(MessageVersion(content = content)))
        var messages by mutableStateOf(listOf(
            message("question", ChatRole.USER, "Host question"),
            message("reply", ChatRole.MODEL, "")
        ))
        val stream = MutableStateFlow<ActiveChatStreamState?>(null)
        var generating by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                ChatInterface(
                    conversationId = "secondary",
                    conversationMessages = messages,
                    isGenerating = generating,
                    streamingHere = generating,
                    activeStream = stream,
                )
            }
        }
        compose.runOnIdle {
            stream.value = ActiveChatStreamState.snapshot(
                conversationId = "secondary", messageId = "reply", generationId = 1,
                revision = 1, content = "Host streamed response"
            )
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Host streamed response", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            stream.value = null
            generating = false
            messages = listOf(message("question", ChatRole.USER, "Host question"),
                message("reply", ChatRole.MODEL, "Host completed response"))
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Host completed response", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
    @Test fun remoteReplyUsesSeparateAnswerThinkingAndToolViews() {
        val messages = listOf(
            StoredMessage("question", ChatRole.USER, listOf(MessageVersion(content = "Host question"))),
            StoredMessage("reply", ChatRole.MODEL, listOf(MessageVersion(
                content = "Visible answer",
                reasoningContent = "Check the input",
                toolActivity = listOf(ToolActivity(
                    id = "call", name = "lookup", status = "success", reasoningOffset = "Check the input".length
                ))
            )))
        )
        compose.setContent {
            MaterialTheme {
                ChatInterface(conversationId = "remote", conversationMessages = messages)
            }
        }
        compose.onNodeWithText("Visible answer", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("lookup", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("processing-dropdown-toggle", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Check the input", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("lookup", useUnmergedTree = true).assertExists()
    }

    @Test fun remotePreparationFailureKeepsTheComposerDraft() {
        val accepted = CompletableDeferred<Boolean>()
        val draft = "Draft for the remote model"
        val failure = "Check provider authentication"
        var workspaceNotice by mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRemoteQuickActions provides RemoteQuickActions()) {
                    ChatInterface(
                        conversationId = "remote",
                        conversationMessages = emptyList(),
                        workspaceNotice = workspaceNotice,
                        onSendMessageWithSkill = { _, _, _, _ ->
                            accepted.await().also { if (!it) workspaceNotice = failure }
                        },
                    )
                }
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput(draft)
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithText(draft).assertExists()
        compose.runOnIdle { accepted.complete(false) }
        compose.onNodeWithText(failure).assertExists()
        compose.onNodeWithText(draft).assertExists()
    }

    @Test fun screenSendsThroughTheSelectedBackend() {
        val sent = mutableListOf<String>()
        var backend by mutableStateOf(ChatBackend(onSendMessage = { text, _ -> sent += "local:$text"; true }))
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    backend = backend, conversation = null,
                    onOpenDrawer = {}, onNewChat = {}, onOpenSettings = {},
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("First")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle {
            backend = ChatBackend(onSendMessage = { text, _ -> sent += "remote:$text"; true })
        }
        compose.onNode(hasSetTextAction()).performTextInput("Second")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle { assertEquals(listOf("local:First", "remote:Second"), sent) }
    }

}
