package org.starfall.generative.features.home.chat.logic.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage

class ConversationTitleGeneratorTest {
    private fun message(id: String, role: ChatRole, text: String) =
        StoredMessage(id, role, listOf(MessageVersion(content = text)))

    private fun conversation(vararg messages: StoredMessage) = Conversation(
        id = "chat", title = "Original", createdAt = 1L, updatedAt = 1L,
        messages = messages.toList()
    )

    @Test fun unfinishedAssistantPlaceholderIsNotSentAndUserEndsWithTitleInstruction() {
        val original = conversation(
            message("first", ChatRole.USER, "Explain coroutines"),
            message("placeholder", ChatRole.MODEL, "")
        )
        val request = conversationTitleRequestMessages(original)
        assertEquals(1, request.size)
        assertEquals(ChatRole.USER, request.last().role)
        assertTrue(request.last().content.startsWith("Explain coroutines"))
        assertTrue(request.last().content.contains("Generate a concise title for this conversation"))
        assertEquals("Explain coroutines", original.messages.first().content)
    }

    @Test fun finishedAssistantTurnReceivesFinalUserTitleRequest() {
        val original = conversation(
            message("first", ChatRole.USER, "Explain coroutines"),
            message("answer", ChatRole.MODEL, "They are lightweight tasks")
        )
        val request = conversationTitleRequestMessages(original)
        assertEquals(3, request.size)
        assertEquals(listOf(ChatRole.USER, ChatRole.MODEL, ChatRole.USER), request.map { it.role })
        assertEquals("They are lightweight tasks", request[1].content)
        assertEquals("Generate a concise title for this conversation. Return only the title, without quotation marks or extra commentary. Keep it under 8 words.", request.last().content)
    }

    @Test fun customInstructionsAreLastUserMessage() {
        val request = conversationTitleRequestMessages(
            conversation(message("answer", ChatRole.MODEL, "Something useful")),
            "Return a concise French title."
        )
        assertEquals(ChatRole.USER, request.last().role)
        assertEquals("Return a concise French title.", request.last().content)
    }

    @Test fun metadataOnlyConversationHasNoTitleRequest() {
        assertTrue(conversationTitleRequestMessages(conversation(
            message("system", ChatRole.SYSTEM, "internal"),
            message("empty", ChatRole.MODEL, "")
        )).isEmpty())
    }

    @Test fun parsesTitleButNeverPersistsTransportErrorsOrEmptyResponses() {
        assertEquals("Kotlin coroutines", parseConversationTitle("  \"Kotlin coroutines\"\nExtra commentary "))
        assertThrows(IllegalStateException::class.java) { parseConversationTitle(" ") }
        for (output in listOf("[Error: HTTP 400]", "partial response [Error: HTTP 500]")) {
            val error = assertThrows(IllegalStateException::class.java) {
                parseConversationTitle(output)
            }
            assertFalse(error.message.isNullOrBlank())
        }
    }
}
