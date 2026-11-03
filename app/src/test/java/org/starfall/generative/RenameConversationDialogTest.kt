package org.starfall.generative

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.drawer.ui.RenameConversationDialog
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w420dp-h900dp-xhdpi", application = LibraryTestApplication::class)
class RenameConversationDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun sampleChat() = ConversationListItem(
        id = "conversation-1", title = "Original title", createdAt = 1L, updatedAt = 1L,
        tokenCount = null, providerId = "", modelId = "", profileId = null,
        reasoningEffort = null, hasMessages = true, hasSummary = false
    )

    @Test fun aiSuggestsTitleButOnlySavePersistsIt() {
        var savedTitle: String? = null
        val requested = mutableListOf<String>()
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                RenameConversationDialog(
                    chat = sampleChat(),
                    onSuggestTitle = { id -> requested += id; Result.success("AI suggested title") },
                    onSave = { savedTitle = it },
                    onDismiss = {}
                )
            }
        }
        compose.onNodeWithTag("rename-chat-generate-title").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("rename-chat-title").assertTextContains("AI suggested title")
        assertEquals(listOf("conversation-1"), requested)
        assertNull("Generating a suggestion must not save it", savedTitle)
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("AI suggested title", savedTitle) }
    }

    @Test fun manualEditsWhileAiIsRunningAreNotOverwritten() {
        val response = CompletableDeferred<Result<String>>()
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                RenameConversationDialog(
                    chat = sampleChat(),
                    onSuggestTitle = { response.await() },
                    onSave = {},
                    onDismiss = {}
                )
            }
        }
        compose.onNodeWithTag("rename-chat-generate-title").performClick()
        compose.onNodeWithTag("rename-chat-title").performTextClearance()
        compose.onNodeWithTag("rename-chat-title").performTextInput("My manual title")
        response.complete(Result.success("Old AI suggestion"))
        compose.waitForIdle()
        compose.onNodeWithTag("rename-chat-title").assertTextContains("My manual title")
    }

    @Test fun failedSuggestionKeepsOriginalTitleEditable() {
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                RenameConversationDialog(
                    chat = sampleChat(),
                    onSuggestTitle = { Result.failure(IllegalStateException("Service unavailable")) },
                    onSave = {},
                    onDismiss = {}
                )
            }
        }
        compose.onNodeWithTag("rename-chat-generate-title").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("rename-chat-title").assertTextContains("Original title")
        compose.onNodeWithText(
            "Could not generate a title. Check the Title Generation model and try again.\nService unavailable"
        ).assertIsDisplayed()
    }
}
