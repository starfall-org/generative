package org.starfall.generative

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.repositories.ConversationSaveJournal
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
        "save-journal-${java.util.UUID.randomUUID()}")

    @Test fun failedRoomCommitLeavesAnAtomicRecordThatANewRepositoryCanReplay() = runBlocking {
        val dir = directory()
        val db = database()
        try {
            val repository = ConversationRepository(db, dir)
            repository.saveConversation(answer().copy(messages = answer().messages.take(1)))
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_final_answer BEFORE INSERT ON message_versions
                WHEN NEW.content = 'The last answer'
                BEGIN SELECT RAISE(ABORT, 'simulated commit failure'); END
            """.trimIndent())
            assertTrue(runCatching { repository.saveConversation(answer()) }.isFailure)
            assertEquals(answer(), ConversationSaveJournal(dir).read(answer().id))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_final_answer")
            val recovered = ConversationRepository(db, dir)
            recovered.recoverPendingSaves()
            assertEquals(answer(), recovered.loadById(answer().id))
            assertTrue(ConversationSaveJournal(dir).pending().isEmpty())
            recovered.recoverPendingSaves()
            assertEquals(2, recovered.loadById(answer().id)!!.messages.size)
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun loadingRestoresThePendingLastAnswerBeforeReturningOldHistory() = runBlocking {
        val db = database()
        val dir = directory()
        try {
            val repository = ConversationRepository(db, dir)
            repository.saveConversation(answer().copy(messages = answer().messages.take(1)))
            ConversationSaveJournal(dir).write(answer()) // Process stopped after durable staging, before Room commit.
            assertEquals(answer(), ConversationRepository(db, dir).loadById(answer().id))
            assertTrue(ConversationSaveJournal(dir).pending().isEmpty())
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun explicitDeletionDiscardsPendingSavesInsteadOfResurrectingHistory() = runBlocking {
        val db = database()
        val dir = directory()
        try {
            val repository = ConversationRepository(db, dir)
            repository.saveConversation(answer())
            ConversationSaveJournal(dir).write(answer())
            repository.deleteConversation(answer().id)
            repository.recoverPendingSaves()
            assertNull(repository.loadById(answer().id))
            ConversationSaveJournal(dir).write(answer())
            repository.deleteAll()
            repository.recoverPendingSaves()
            assertTrue(dir.listFiles().isNullOrEmpty())
            assertNull(repository.loadById(answer().id))
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun temporaryChatNeverCreatesAJournalOrRoomRows() = runBlocking {
        val db = database()
        val dir = directory()
        try {
            val temporary = answer().copy(isTemporary = true)
            ConversationRepository(db, dir).saveConversation(temporary)
            ConversationSaveJournal(dir).write(temporary)
            assertFalse(dir.exists())
            assertNull(ConversationRepository(db, dir).loadById(temporary.id))
        } finally { db.close(); dir.deleteRecursively() }
    }
}
