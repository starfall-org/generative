package org.starfall.generative

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TemporaryConversationPersistenceTest {
    @Test fun temporaryConversationAndItsAttachmentsNeverEnterHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).build()
        try {
            val repository = ConversationRepository(db)
            val message = StoredMessage("message", ChatRole.USER,
                listOf(MessageVersion(content = "secret", files = listOf("tool-file:temporary.png"))))
            val temporary = Conversation("temporary", "Temporary Chat", 1, 1,
                messages = listOf(message), isTemporary = true)
            repository.saveConversation(temporary)
            assertNull(repository.loadById(temporary.id))
            assertTrue(repository.conversationList.first().isEmpty())
            assertTrue(repository.allFileReferences().isEmpty())

            val normal = temporary.copy(id = "normal", title = "Saved", isTemporary = false)
            repository.saveConversation(normal)
            assertEquals("Saved", repository.loadById("normal")?.title)
            assertEquals(listOf("normal"), repository.conversationList.first().map { it.id })
        } finally {
            db.close()
        }
    }
}
