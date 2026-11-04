package org.starfall.generative.features.home.chat.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity

class ConversationTitleTriggerTest {
    @Test
    fun titleWaitsForTenWordsOrThreeUserAndAssistantMessages() {
        assertFalse(shouldGenerateConversationTitle("one two three four five six seven eight nine", 2))
        assertTrue(shouldGenerateConversationTitle("one two three four five six seven eight nine ten", 2))
        assertFalse(shouldGenerateConversationTitle("short message", 2))
        assertTrue(shouldGenerateConversationTitle("short message", 3))
    }

    @Test
    fun titleRulesCanTriggerAfterTwoMessagesWithoutWordTrigger() {
        assertFalse(
            shouldGenerateConversationTitle(
                userText = "short",
                messageCount = 1,
                minimumWords = 0,
                minimumMessages = 2
            )
        )
        assertTrue(
            shouldGenerateConversationTitle(
                userText = "short",
                messageCount = 2,
                minimumWords = 0,
                minimumMessages = 2
            )
        )
    }

    @Test
    fun directMediaConversationIsRecognizedAndKeepsPromptBasedTitle() {
        val conversation = Conversation(
            id = "media",
            title = "A prompt for image generation",
            createdAt = 1,
            updatedAt = 1,
            messages = listOf(
                StoredMessage(
                    id = "user",
                    role = ChatRole.USER,
                    versions = listOf(MessageVersion(content = "A prompt for image generation"))
                ),
                StoredMessage(
                    id = "assistant",
                    role = ChatRole.MODEL,
                    versions = listOf(
                        MessageVersion(
                            toolActivity = listOf(ToolActivity(id = "tool", name = "generate_image"))
                        )
                    )
                )
            )
        )

        assertTrue(conversation.startedWithDirectMedia())
        assertFalse(conversation.copy(messages = emptyList()).startedWithDirectMedia())
    }
}
