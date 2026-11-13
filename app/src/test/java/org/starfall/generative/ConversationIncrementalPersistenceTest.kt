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
import org.starfall.generative.features.appdata.logic.buildJsonExport
import org.starfall.generative.features.appdata.logic.parseFullAppDataExport

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

    @Test fun metadataEditDoesNotRewriteUnchangedLargeToolResult() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java).build()
        try {
            val repository = ConversationRepository(db)
            val huge = "tool response.".repeat(180_000)
            val source = Conversation("large-tool", "Original", 1, 2, messages = listOf(
                StoredMessage("reply", ChatRole.MODEL, listOf(MessageVersion(
                    content = "Answer", toolActivity = listOf(
                        ToolActivity("tool", "search", status = "success", response = huge.toToolPayload())
                    )
                )))
            ))
            repository.saveConversation(source)
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER prevent_rewriting_tool BEFORE UPDATE ON tool_activities
                BEGIN SELECT RAISE(ABORT, 'unchanged tool activity must not be rewritten'); END
            """.trimIndent())
            val changed = source.copy(title = "Renamed", updatedAt = 3)
            repository.saveConversation(changed)
            assertEquals(changed, repository.loadById(changed.id))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER prevent_rewriting_tool")
            // Cold writes use key-only reads and remain valid even without an RAM snapshot.
            ConversationRepository(db).saveConversation(changed.copy(updatedAt = 4))
        } finally { db.close() }
    }

    @Test fun oversizedToolResponseAndUnicodeSurviveCursorWindowChunkedReads() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java).build()
        try {
            val original = Conversation("oversized", "Oversized", 1, 2, messages = listOf(
                StoredMessage("model", ChatRole.MODEL, listOf(MessageVersion(
                    content = "Result", toolActivity = listOf(
                        ToolActivity("tool", "Search", status = "success",
                            arguments = "😀".repeat(18_000).toToolPayload(),
                            response = ("😀".repeat(17_000) + "x".repeat(2_200_000)).toToolPayload())
                    )
                )))
            ))
            val repo = ConversationRepository(db)
            repo.saveConversation(original)
            assertEquals(original, ConversationRepository(db).loadById(original.id))
        } finally { db.close() }
    }

    @Test fun oversizedSummaryAndAssistantTextAreReadWithoutCursorWindowOverflow() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java).build()
        try {
            val source = Conversation("long-answer", "Long", 1, 2,
                messages = listOf(StoredMessage("answer", ChatRole.MODEL, listOf(
                    MessageVersion(content = "😀".repeat(17_000) + "c".repeat(2_200_000),
                        reasoningContent = "r".repeat(2_200_000))))),
                summary = ConversationSummary("summary", "s".repeat(2_200_000), "answer"))
            ConversationRepository(db).saveConversation(source)
            assertEquals(source, ConversationRepository(db).loadById(source.id))
        } finally { db.close() }
    }

    @Test fun persistedToolPayloadsSurviveEvictionAndJsonBackupRoundTrip() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java).build()
        try {
            val arguments = "{\"query\":\"hello\"}"
            val response = "large result ".repeat(3_000)
            val conversation = Conversation("backup-payload", "Tool backup", 1, 2, messages = listOf(
                StoredMessage("reply", ChatRole.MODEL, listOf(MessageVersion(
                    content = "Done", toolActivity = listOf(ToolActivity(
                        "call", "search", status = "success",
                        arguments = arguments.toToolPayload(), response = response.toToolPayload()
                    ))
                )))
            ))
            ConversationRepository(db).saveConversation(conversation)
            ToolPayloadCache.clear()
            val restored = ConversationRepository(db).loadById(conversation.id)!!
            val activity = restored.messages.single().versions.single().toolActivity.single()
            assertFalse(activity.arguments.isResident)
            assertFalse(activity.response.isResident)
            ToolPayloadCache.clear()
            assertNull(activity.arguments.peek())
            assertNull(activity.response.peek())
            assertEquals(arguments, activity.arguments.text())
            assertEquals(response, activity.response.text())
            ToolPayloadCache.clear()
            val backup = buildJsonExport(emptyList(), emptyList(), emptyList(), listOf(restored))
            val imported = parseFullAppDataExport(backup).conversations!!.single()
            val importedActivity = imported.messages.single().versions.single().toolActivity.single()
            assertEquals(arguments, importedActivity.arguments.text())
            assertEquals(response, importedActivity.response.text())
        } finally { ToolPayloadCache.clear(); db.close() }
    }

    @Test fun replacingAStoredCallDoesNotReadAnotherCallAtTheSameActivityIndex() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java).build()
        try {
            val repository = ConversationRepository(db)
            fun conversation(callId: String, content: String) = Conversation("replace-call", "Calls", 1, 2,
                messages = listOf(StoredMessage("reply", ChatRole.MODEL, listOf(MessageVersion(
                    content = "done", toolActivity = listOf(ToolActivity(callId, "search",
                        status = "success", response = content.toToolPayload())))))))
            repository.saveConversation(conversation("old", "old response"))
            ToolPayloadCache.clear()
            val obsolete = ConversationRepository(db).loadById("replace-call")!!
                .messages.single().versions.single().toolActivity.single().response
            repository.saveConversation(conversation("new", "new response"))
            ToolPayloadCache.clear()
            assertTrue("A deleted call must not read a replacement row",
                runCatching { obsolete.text() }.isFailure)
            val current = ConversationRepository(db).loadById("replace-call")!!
                .messages.single().versions.single().toolActivity.single().response
            assertEquals("new response", current.text())
        } finally { ToolPayloadCache.clear(); db.close() }
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
