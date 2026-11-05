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
class ConversationDeletionIntegrityTest {
    @Test fun deletingQuarantinedConversationCascadesAllRelationalChildrenWithoutDecoding() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).build()
        try {
            val repository = ConversationRepository(db)
            val shared = conversation("shared", "tool-file:shared.bin")
            val quarantined = conversation("quarantined", "tool-file:shared.bin", "tool-file:only-here.bin")
                .copy(summary = ConversationSummary("summary", "Summary", "message"))
            repository.saveConversation(shared)
            repository.saveConversation(quarantined)
            db.conversationDao().insertMigrationIssue(
                ConversationMigrationQuarantineEntity(
                    quarantined.id, "messages_json", "{broken", "invalid_json"
                )
            )

            try {
                repository.loadById(quarantined.id)
                fail("A quarantined conversation must require recovery when opened")
            } catch (error: ConversationMigrationException) {
                assertEquals(quarantined.id, error.conversationId)
            }

            repository.deleteConversation(quarantined.id)

            assertNull(repository.loadById(quarantined.id))
            assertEquals(setOf("tool-file:shared.bin"), repository.allFileReferences().toSet())
            assertEquals(setOf("only-here.bin"), repository.unreferencedFiles(
                listOf("shared.bin", "only-here.bin")
            ))
            for (table in listOf(
                "messages", "message_versions", "tool_activities", "message_version_files",
                "tool_activity_files", "conversation_summaries", "conversation_migration_quarantine"
            )) {
                assertEquals("$table must not retain deleted conversation rows", 0L, count(db, table, quarantined.id))
            }
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse("Deleting a conversation must not leave foreign-key orphans", it.moveToFirst())
            }
        } finally {
            db.close()
        }
    }

    private fun conversation(id: String, vararg references: String): Conversation {
        val activity = ToolActivity(
            id = "activity-$id",
            name = "Search",
            status = "success",
            files = references.toList()
        )
        val message = StoredMessage(
            id = "message",
            role = ChatRole.MODEL,
            versions = listOf(MessageVersion(content = id, files = references.toList(), toolActivity = listOf(activity)))
        )
        return Conversation(id, id, 1, 1, messages = listOf(message))
    }

    private fun count(db: AppDatabase, table: String, conversationId: String): Long =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM $table WHERE conversationId = ?",
            arrayOf(conversationId)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
