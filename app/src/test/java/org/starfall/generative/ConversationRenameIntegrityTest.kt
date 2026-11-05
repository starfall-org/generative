package org.starfall.generative

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.*
import org.starfall.generative.database.repositories.ConversationMigrationException
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ConversationMigrationQuarantineEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConversationRenameIntegrityTest {
    @Test fun renamePreservesHistoryVersionsFilesSummaryAndRecoveryData() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repository = ConversationRepository(db)
            val message = StoredMessage("message", ChatRole.MODEL, listOf(
                MessageVersion(content = "Earlier", files = listOf("tool-file:shared.txt")),
                MessageVersion(content = "Current", reasoningContent = "Reasoning", toolActivity = listOf(
                    ToolActivity(id = "tool", name = "search", status = "success", files = listOf("tool-file:result.txt"))
                ))
            ), activeVersionIndex = 1)
            val original = Conversation("rename", "Before", 1, 2, messages = listOf(message),
                summary = ConversationSummary("summary", "Keep summary", message.id, createdAt = 1))
            val unrelated = original.copy(id = "other", title = "Other")
            repository.saveConversation(original)
            repository.saveConversation(unrelated)

            repository.renameConversation(original.id, "After")
            val restored = requireNotNull(repository.loadById(original.id))
            assertEquals(original.copy(title = "After", updatedAt = restored.updatedAt), restored)
            assertTrue(restored.updatedAt >= original.updatedAt)
            assertEquals(unrelated, repository.loadById(unrelated.id))
            assertTrue(db.conversationDao().mentionsFile("shared.txt"))
            assertTrue(db.conversationDao().mentionsFile("result.txt"))

            val recovery = ConversationMigrationQuarantineEntity(original.id, "messages_json", "invalid", "invalid_json")
            db.conversationDao().insertMigrationIssue(recovery)
            repository.renameConversation(original.id, "Needs recovery")
            assertEquals(listOf(recovery), db.conversationDao().getMigrationIssues(original.id))
            assertEquals(message.versions.size, db.conversationDao().getMessageVersions(original.id, message.id).size)
            try {
                repository.loadById(original.id)
                fail("Rename must not clear migration recovery issues")
            } catch (_: ConversationMigrationException) { }

            repository.renameConversation("missing", "No insertion")
            assertNull(repository.loadById("missing"))
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close() }
    }
}
