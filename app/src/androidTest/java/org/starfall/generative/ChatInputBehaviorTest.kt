package org.starfall.generative

import org.starfall.generative.features.home.BottomSheetsLinkState


import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.runBlocking
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.appwiring.*
import org.starfall.generative.database.repositories.*
import org.starfall.generative.features.mcp.shared.ui.*
import org.starfall.generative.integrations.mcp.*
import org.starfall.generative.features.machines.shared.ui.*
import org.starfall.generative.integrations.remote.*
import org.starfall.generative.features.home.chat.*
import org.starfall.generative.integrations.modelapis.*
import org.starfall.generative.integrations.files.*
import org.starfall.generative.shared.subfeatures.crashreports.logic.*
import org.starfall.generative.shared.subfeatures.explorer.logic.*
import org.starfall.generative.shared.subfeatures.media.logic.*
import org.starfall.generative.shared.subfeatures.icons.logic.*
import org.starfall.generative.integrations.speech.*
import org.starfall.generative.shared.helpers.audio.*
import org.starfall.generative.shared.helpers.speech.*
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.database.preferences.*
import org.starfall.generative.shared.model.ChatRole
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.features.home.chat.ui.input.UserInputArea

class ChatInputBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typingDuringGenerationAllowsSendAndRestoresStopAfterSubmission() {
        var sent: String? = null
        var stops = 0
        compose.setContent {
            MaterialTheme {
                UserInputArea(
                    isGenerating = true,
                    onSendMessage = { text, _ -> sent = text; true },
                    onSendMedia = { false },
                    bottomSheets = BottomSheetsLinkState(),
                    onEditMessage = { _, _, _ -> false },
                    editDraft = null,
                    onCancelEdit = {},
                    onStopGenerating = { stops++ },
                    selectedModelName = "",
                    providers = emptyList(),
                    selectedProviderId = "",
                    onSelectModel = { _, _ -> },
                    conversationReasoningEffort = null,
                    onSetReasoningEffort = {}
                )
            }
        }
        compose.onNodeWithContentDescription("Stop generation").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("Next question")
        compose.onNodeWithContentDescription("Stop generation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("Next question", sent); assertEquals(0, stops) }
        compose.onNodeWithContentDescription("Stop generation").assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("Draft")
        compose.onNodeWithContentDescription("Send message").assertExists()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithContentDescription("Stop generation").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun wrappingFirstLineMovesControlsDownAndWidensTheEditor() {
        compose.setContent {
            MaterialTheme {
                UserInputArea(
                    isGenerating = false,
                    onSendMessage = { _, _ -> true },
                    onSendMedia = { false },
                    bottomSheets = BottomSheetsLinkState(),
                    onEditMessage = { _, _, _ -> false },
                    editDraft = null,
                    onCancelEdit = {},
                    onStopGenerating = {},
                    selectedModelName = "",
                    providers = emptyList(),
                    selectedProviderId = "",
                    onSelectModel = { _, _ -> },
                    conversationReasoningEffort = null,
                    onSetReasoningEffort = {}
                )
            }
        }

        val editor = compose.onNode(hasSetTextAction())
        editor.performTextInput("Hi")
        val initialWidth = editor.fetchSemanticsNode().boundsInRoot.width
        val controls = listOf("Attachments and tools", "Select model", "Send message")
        val initialBounds = controls.associateWith {
            compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot
        }
        val initialComposer = compose.onNodeWithTag("chat-input").fetchSemanticsNode().boundsInRoot
        val initialModel = initialBounds.getValue("Select model")
        val initialRowHeight = initialComposer.bottom - initialModel.top
        for (text in listOf("A long message that wraps naturally. ".repeat(20), "First line\nSecond line")) {
            editor.performTextReplacement(text)
            val expanded = editor.fetchSemanticsNode().boundsInRoot
            assertTrue("Editor should widen after wrapping", expanded.width > initialWidth)
            for (description in controls) {
                val bounds = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
                val initial = initialBounds.getValue(description)
                assertTrue("Controls should move below the editor", bounds.top >= expanded.bottom)
                assertEquals("$description width should halve", initial.width / 2f, bounds.width, 1f)
                assertEquals("$description height should halve", initial.height / 2f, bounds.height, 1f)
            }
            val composer = compose.onNodeWithTag("chat-input").fetchSemanticsNode().boundsInRoot
            val model = compose.onNodeWithContentDescription("Select model").fetchSemanticsNode().boundsInRoot
            assertEquals("The control row and bottom inset should halve",
                initialRowHeight / 2f, composer.bottom - model.top, 1f)

            editor.performTextReplacement("Hi")
            for (description in controls) {
                val restored = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
                assertEquals(initialBounds.getValue(description).height, restored.height, 1f)
            }
            assertTrue(editor.fetchSemanticsNode().boundsInRoot.width < expanded.width)
        }
    }

    @Test fun sendingWithoutAModelPersistsRapidMessagesAndAttachments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = ConversationRepository(database)
        val ai = AiService(context)
        val http = ToolHttp(ToolFiles(context))
        val mcp = McpService(http)
        val tts = TtsHelper(context)
        val store = ViewModelStore()
        try {
            lateinit var model: ChatViewModel
            compose.setContent {}
            compose.runOnIdle {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return ChatViewModel(
                            repository, ModelRepository(database, ai),
                            McpRepository(database, mcp), AppPreferencesRepository(context),
                            ToolChatViewModel(http, mcp, ai), ToolSettingsStore(context),
                            SpeechRepository(database), tts, SpeechSynthesisService(),
                            SpeechAudioPlayer(context), ToolFiles(context)
                        ) as T
                    }
                })[ChatViewModel::class.java]
                assertTrue(model.sendMessage("First without a model"))
                assertTrue(model.sendMessage("", listOf("content://test/attachment")))
                assertFalse(model.isGenerating.value)
            }
            val id = model.currentConversation.value!!.id
            compose.waitUntil(10_000) {
                runBlocking { repository.getById(id)?.messages?.size == 2 }
            }
            val saved = runBlocking { repository.getById(id) }!!
            assertEquals(listOf(ChatRole.USER, ChatRole.USER), saved.messages.map { it.role })
            assertEquals("First without a model", saved.messages.first().content)
            assertEquals(listOf("content://test/attachment"), saved.messages.last().files)
            compose.runOnIdle { assertTrue(model.queuedMessages.value.isEmpty()) }
        } finally {
            compose.runOnIdle { store.clear() }
            tts.shutdown()
            database.close()
        }
    }
}
