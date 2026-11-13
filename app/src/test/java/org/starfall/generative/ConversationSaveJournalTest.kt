package org.starfall.generative

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.shared.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ConversationSaveJournalTest {
    private fun answer() = Conversation("durable", "Title", 1, 2, messages = listOf(
        StoredMessage("user", ChatRole.USER, listOf(MessageVersion(content = "Question"))),
        StoredMessage("last", ChatRole.MODEL, listOf(MessageVersion(content = "The last answer",
            reasoningContent = "Reasoning", processingFinishedAt = 2,
            toolActivity = listOf(ToolActivity("tool", "Search", status = "success")))))))
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    private fun directory() = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
        "save-journal-${UUID.randomUUID()}")

    /** Write only a legacy artifact as an upgrade fixture, never through production save code. */
    private fun legacyRecord(dir: File, conversation: Conversation): File {
        dir.mkdirs()
        return File(dir, UUID.nameUUIDFromBytes(conversation.id.toByteArray()).toString() + ".json").also {
            it.writeText(Json.encodeToString(conversation))
        }
    }

    @Test fun failedRoomCommitKeepsKotlinSnapshotReadableAndAllowsRetry() = runBlocking {
        val dir = directory()
        val db = database()
        try {
            val repository = ConversationRepository(db, dir)
            val prior = answer().copy(messages = answer().messages.take(1))
            repository.saveConversation(prior)
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_final_answer BEFORE INSERT ON message_versions
                WHEN NEW.content = 'The last answer'
                BEGIN SELECT RAISE(ABORT, 'simulated commit failure'); END
            """.trimIndent())
            assertTrue(runCatching { repository.saveConversation(answer()) }.isFailure)
            assertEquals("The in-process data class must survive a failed transaction", answer(),
                repository.loadById(answer().id))
            assertEquals("No partial commit may leak into a fresh repository", prior,
                ConversationRepository(db, dir).loadById(answer().id))
            assertTrue("New saves must not produce JSON files", dir.listFiles().isNullOrEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_final_answer")
            repository.saveConversation(answer())
            assertEquals(answer(), ConversationRepository(db, dir).loadById(answer().id))
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun olderJsonJournalIsRecoveredOnceAndNoNewRecordIsWritten() = runBlocking {
        val dir = directory()
        val db = database()
        try {
            val repository = ConversationRepository(db, dir)
            repository.saveConversation(answer().copy(messages = answer().messages.take(1)))
            legacyRecord(dir, answer())
            val restarted = ConversationRepository(db, dir)
            restarted.recoverPendingSaves()
            assertEquals(answer(), restarted.loadById(answer().id))
            assertTrue(dir.listFiles().isNullOrEmpty())
            restarted.saveConversation(answer().copy(title = "Changed"))
            assertTrue(dir.listFiles().isNullOrEmpty())
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun invalidLegacyRecordCannotBlockValidHistoryOrRename() = runBlocking {
        val db = database()
        val dir = directory()
        try {
            val repository = ConversationRepository(db, dir)
            repository.saveConversation(answer())
            val damaged = legacyRecord(dir, answer())
            damaged.writeText("{invalid")
            repository.recoverPendingSaves()
            assertTrue(damaged.exists())
            assertEquals(answer(), ConversationRepository(db, dir).loadById(answer().id))
            repository.renameConversation(answer().id, "Recovered")
            assertEquals("Recovered", repository.loadById(answer().id)!!.title)
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun deleteDiscardsOldLegacyArtifactsInsteadOfResurrectingHistory() = runBlocking {
        val db = database()
        val dir = directory()
        try {
            val repository = ConversationRepository(db, dir)
            repository.saveConversation(answer())
            legacyRecord(dir, answer())
            repository.deleteConversation(answer().id)
            repository.recoverPendingSaves()
            assertNull(repository.loadById(answer().id))
            legacyRecord(dir, answer())
            repository.deleteAll()
            repository.recoverPendingSaves()
            assertTrue(dir.listFiles().isNullOrEmpty())
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun failedCommitRetainsUnpersistedLargePayloadAfterCachePressure() = runBlocking {
        val db = database()
        try {
            val repository = ConversationRepository(db)
            val response = "unsaved response ".repeat(4_000)
            val conversation = answer().copy(messages = listOf(
                StoredMessage("last", ChatRole.MODEL, listOf(MessageVersion(content = "The last answer",
                    toolActivity = listOf(ToolActivity("pending", "search", status = "success",
                        response = response.toToolPayload())))))
            ))
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_tool_save BEFORE INSERT ON message_versions
                BEGIN SELECT RAISE(ABORT, 'simulated commit failure'); END
            """.trimIndent())
            assertTrue(runCatching { repository.saveConversation(conversation) }.isFailure)
            ToolPayloadCache.clear()
            val stillAvailable = repository.loadById(conversation.id)!!.messages.single()
                .versions.single().toolActivity.single().response
            assertTrue("An unsaved payload must never be discarded", stillAvailable.isResident)
            assertEquals(response, stillAvailable.text())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_tool_save")
            repository.saveConversation(conversation)
            ToolPayloadCache.clear()
            val persisted = ConversationRepository(db).loadById(conversation.id)!!.messages.single()
                .versions.single().toolActivity.single().response
            assertEquals(response, persisted.text())
        } finally { ToolPayloadCache.clear(); db.close() }
    }

    @Test fun temporaryChatNeverCreatesRoomRowsOrJournalFiles() = runBlocking {
        val db = database()
        val dir = directory()
        try {
            val temporary = answer().copy(isTemporary = true)
            ConversationRepository(db, dir).saveConversation(temporary)
            assertFalse(dir.exists())
            assertNull(ConversationRepository(db, dir).loadById(temporary.id))
        } finally { db.close(); dir.deleteRecursively() }
    }
}
