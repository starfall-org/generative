package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.BottomSheetsLinkState
import org.starfall.generative.features.home.chat.ui.input.UserInputArea
import org.starfall.generative.features.home.chat.ui.ChatAttachmentSelection
import org.starfall.generative.features.home.chat.ui.LocalChatAttachmentSelection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ExternalShareComposerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun externalTextIsAppendedToCurrentDraftWithoutSending() {
        val selection = ChatAttachmentSelection()
        var sends = 0
        compose.setContent {
            val attachments by selection.attachments
            MaterialTheme {
                CompositionLocalProvider(LocalChatAttachmentSelection provides selection) {
                    UserInputArea(
                        isGenerating = false,
                        onSendMessage = { _, _ -> sends++; true },
                        onSendMedia = { false },
                        onEditMessage = { _, _, _ -> false },
                        editDraft = null, onCancelEdit = {}, onStopGenerating = {},
                        bottomSheets = androidx.compose.runtime.remember { BottomSheetsLinkState() },
                        selectedModelName = "", providers = emptyList(),
                        selectedProviderId = "", onSelectModel = { _, _ -> },
                        conversationReasoningEffort = null, onSetReasoningEffort = {},
                        attachments = attachments, onAttachmentsChange = { selection.attachments.value = it }
                    )
                }
            }
        }
        compose.runOnIdle { selection.queueExternalText("Shared text") }
        compose.onNode(hasSetTextAction()).assertTextEquals("Shared text")
        compose.runOnIdle { selection.queueExternalText("Another selection") }
        compose.onNode(hasSetTextAction()).assertTextEquals("Shared text\n\nAnother selection")
        compose.runOnIdle { assertEquals(0, sends) }
    }
}
