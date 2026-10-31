package org.starfall.generative

import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.generative.features.home.chat.logic.service.conversationFallbackTitle

class ConversationFallbackTitleTest {
    @Test
    fun longPromptIsPreservedWithoutHardCodedCharacterLimit() {
        val prompt = "Conversation title continues beyond the old thirty and eighty character limits " +
            "while the drawer decides how much text fits on screen."

        assertEquals(prompt, conversationFallbackTitle(prompt))
    }

    @Test
    fun attachmentOnlyConversationStillReceivesFallbackTitle() {
        assertEquals("Attachment", conversationFallbackTitle(""))
        assertEquals("Attachment", conversationFallbackTitle("   "))
    }
}
