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
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.room.AppDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConversationIncrementalPersistenceTest {
    @Test fun appendingAndEditingMessagesDoNotRebuildUnchangedRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repository = ConversationRepository(db)
            val first = StoredMessage("first", ChatRole.USER, listOf(MessageVersion(content = "Question", files = listOf("question.txt"))))
            val answer = StoredMessage("answer", ChatRole.MODEL, listOf(
                MessageVersion(content = "Answer", toolActivity = listOf(
                    ToolActivity(id = "search", name = "Search", status = "success", files = listOf("result.json"))
                )),
                MessageVersion(content = "Alternative")
            ), activeVersionIndex = 0)
            val original = Conversation("conversation", "Incremental", 1, 2, messages = listOf(first, answer))
            repository.saveConversation(original)
            val rowIds = rowIds(db)

            repository.saveConversation(original.copy(messages = original.messages +
                StoredMessage("tail", ChatRole.USER, listOf(MessageVersion(content = "Follow-up")))))
            assertEquals(rowIds, rowIds(db).filterKeys { it != "new-message" })
            assertEquals("Appending must keep the last message at the end",
                listOf("first", "answer", "tail"), repository.loadById(original.id)!!.messages.map { it.id })

            val edited = original.copy(messages = listOf(
                first,
                answer.copy(versions = answer.versions.mapIndexed { index, version ->
                    if (index == 1) version.copy(content = "Edited alternative") else version
                }),
                StoredMessage("tail", ChatRole.USER, listOf(MessageVersion(content = "Follow-up")))
            ))
            repository.saveConversation(edited)
            val afterEdit = rowIds(db)
            assertEquals(rowIds.getValue("first-message"), afterEdit.getValue("first-message"))
            assertEquals(rowIds.getValue("answer-message"), afterEdit.getValue("answer-message"))
            assertEquals(rowIds.getValue("first-version"), afterEdit.getValue("first-version"))
            assertEquals(rowIds.getValue("answer-first-version"), afterEdit.getValue("answer-first-version"))
            assertEquals(rowIds.getValue("answer-activity"), afterEdit.getValue("answer-activity"))
            assertEquals("Edited alternative", repository.loadById("conversation")!!.messages[1].versions[1].content)
            assertFalse(db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
        } finally { db.close() }
    }

    @Test fun editedBlockPositionsAndGroupIdsRoundTripThroughRoom() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java).build()
        try {
            val edited = MessageVersion(
                content = "One two", reasoningContent = "Thinking",
                reasoningContentOffset = 4,
                files = listOf("attachment.png"), filesContentOffset = 2,
                mediaContentOffsets = mapOf("search" to 6),
                toolActivity = listOf(
                    ToolActivity(id = "search", name = "generate_image",
                        contentOffset = 4, actionGroup = 2,
                        files = listOf("tool-file:image.png"))
                )
            )
            val original = Conversation("layout", "Layout", 1, 2,
                messages = listOf(StoredMessage("reply", ChatRole.MODEL, listOf(edited))))
            val repo = ConversationRepository(db)
            repo.saveConversation(original)
            assertEquals(original, repo.loadById(original.id))
        } finally { db.close() }
    }

    @Test fun replacingVersionsPersistsTheActiveRetryAndRemovesStaleDescendants() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repository = ConversationRepository(db)
            val original = Conversation("retry", "Retry", 1, 1, messages = listOf(
                StoredMessage("user", ChatRole.USER, listOf(MessageVersion(content = "Question"))),
                StoredMessage("answer", ChatRole.MODEL, listOf(
                    MessageVersion(content = "Failed", files = listOf("old.txt"), toolActivity = listOf(
                        ToolActivity("old-tool", "Search", files = listOf("old-result.txt"))
                    )),
                    MessageVersion(content = "Unused alternative", files = listOf("alternative.txt"))
                ), activeVersionIndex = 0)
            ))
            repository.saveConversation(original)

            val retried = original.copy(messages = original.messages.map { message ->
                if (message.id != "answer") message else message.copy(
                    versions = listOf(MessageVersion(
                        content = "Recovered",
                        files = listOf("new.txt"),
                        toolActivity = listOf(ToolActivity("new-tool", "Search", status = "success"))
                    )),
                    activeVersionIndex = 0
                )
            })
            repository.saveConversation(retried)

            assertEquals(retried, repository.loadById(retried.id))
            assertEquals(setOf("new.txt"), repository.allFileReferences().toSet())
            assertEquals(2L, count(db, "message_versions"))
            assertEquals(1L, count(db, "tool_activities"))
            assertEquals(1L, count(db, "message_version_files"))
            assertEquals(0L, count(db, "tool_activity_files"))
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close() }
    }

    private fun rowIds(db: AppDatabase): Map<String, Long> {
        fun rowId(table: String, where: String): Long = db.openHelper.readableDatabase.query(
            "SELECT rowid FROM $table WHERE $where"
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getLong(0) }
        return mapOf(
            "first-message" to rowId("messages", "id = 'first'"),
            "answer-message" to rowId("messages", "id = 'answer'"),
            "first-version" to rowId("message_versions", "messageId = 'first' AND versionIndex = 0"),
            "answer-first-version" to rowId("message_versions", "messageId = 'answer' AND versionIndex = 0"),
            "answer-activity" to rowId("tool_activities", "messageId = 'answer' AND activityIndex = 0")
        )
    }

    private fun count(db: AppDatabase, table: String): Long = db.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM $table"
    ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getLong(0) }
}
