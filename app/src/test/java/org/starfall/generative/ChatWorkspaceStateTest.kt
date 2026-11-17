package org.starfall.generative

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.ui.RetainedChatConversations
import org.starfall.generative.features.home.chat.ui.RetainedChatPages
import org.starfall.generative.navigation.ChatWorkspaceState
import org.starfall.generative.shared.model.Conversation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ChatWorkspaceStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sameChatIdDoesNotShareDraftBetweenServers() {
        var server by mutableStateOf("a")
        val pages = mutableMapOf<String, RetainedChatPages>()
        val chat = Conversation(id = "same", title = "", createdAt = 1, updatedAt = 1)
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            val retained = remember(server) { pages.getOrPut(server) { RetainedChatPages() } }
            ChatWorkspaceState(holder, true, server) {
                RetainedChatConversations(retained, chat, setOf("same"), true) { _, _ ->
                    var draft by rememberSaveable { mutableStateOf("") }
                    BasicTextField(draft, { draft = it }, Modifier.testTag("draft"))
                }
            }
        }
        compose.onNodeWithTag("draft").performTextInput("server A")
        compose.runOnIdle { server = "b" }
        compose.onNodeWithTag("draft").assertTextEquals("")
        compose.onNodeWithTag("draft").performTextInput("server B")
        compose.runOnIdle { server = "a" }
        compose.onNodeWithTag("draft").assertTextEquals("server A")
        compose.runOnIdle { server = "b" }
        compose.onNodeWithTag("draft").assertTextEquals("server B")
    }


    @Test fun replacingThePairedIdentityDoesNotRestoreThePreviousIdentityDraft() {
        var identity by mutableStateOf("client-old")
        val chat = Conversation(id = "same", title = "", createdAt = 1, updatedAt = 1)
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            ChatWorkspaceState(holder, true, "profile:$identity") {
                RetainedChatConversations(remember(identity) { RetainedChatPages() }, chat, setOf("same"), true) { _, _ ->
                    var draft by rememberSaveable { mutableStateOf("") }
                    BasicTextField(draft, { draft = it }, Modifier.testTag("draft"))
                }
            }
        }
        compose.onNodeWithTag("draft").performTextInput("previous owner's message")
        compose.runOnIdle { identity = "client-new" }
        compose.onNodeWithTag("draft").assertTextEquals("")
        compose.onNodeWithTag("draft").performTextInput("new owner's message")
        compose.runOnIdle { identity = "client-old" }
        compose.onNodeWithTag("draft").assertTextEquals("previous owner's message")
    }
}
