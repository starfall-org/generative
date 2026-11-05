package org.starfall.generative

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.appdata.logic.buildJsonExport
import org.starfall.generative.features.appdata.logic.parseFullAppDataExport
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.generation.ContextUsageSnapshot
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.MessageVersionEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ConversationContextUsageStorageTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After fun tearDown() { db.close() }

    @Test fun savesAndReloadsEveryVersionsSnapshotIncludingZeroAndUnknownOutput() = runBlocking {
        val source = sampleConversation()
        ConversationRepository(db).saveConversation(source)

        val reader = ConversationRepository(db)
        assertEquals(source, reader.loadById(source.id))
        assertEquals(listOf(source), reader.allConversations.first())
        val stored = db.conversationDao().getMessageVersions(source.id, "answer")
        source.messages.single().versions.zip(stored).forEach { (version, row) ->
            assertEquals(version.contextUsage?.usage?.inputTokens, row.usageInputTokens)
            assertEquals(version.contextUsage?.usage?.outputTokens, row.usageOutputTokens)
            assertEquals(version.contextUsage?.contextKey, row.usageContextKey)
            assertEquals(version.contextUsage?.messagesFingerprint, row.usageMessagesFingerprint)
        }
        assertEquals(4, stored.size)
    }

    @Test fun changingAndClearingSnapshotsUpdatesOnlyTheCorrespondingVersions() = runBlocking {
        val source = sampleConversation()
        val writer = ConversationRepository(db)
        writer.saveConversation(source)
        val answer = source.messages.single()
        val updated = source.copy(messages = listOf(answer.copy(
            versions = answer.versions.mapIndexed { index, version ->
                when (index) {
                    0 -> version.copy(contextUsage = null)
                    1 -> version.copy(contextUsage = snapshot(900, 10, "updated"))
                    3 -> version.copy(contextUsage = snapshot(111, null, "new"))
                    else -> version
                }
            },
            activeVersionIndex = 2
        )))
        writer.saveConversation(updated)

        assertEquals(updated, ConversationRepository(db).loadById(source.id))
        val stored = db.conversationDao().getMessageVersions(source.id, "answer")
        assertNoUsage(stored[0])
        assertEquals(900L, stored[1].usageInputTokens)
        assertEquals(10L, stored[1].usageOutputTokens)
        assertNull(stored[2].usageOutputTokens)
        assertEquals("context-inactive", stored[2].usageContextKey)
        assertEquals(111L, stored[3].usageInputTokens)
        assertNull(stored[3].usageOutputTokens)
        assertEquals("context-new", stored[3].usageContextKey)
    }

    @Test fun invalidSnapshotsAreSavedWithoutUsage() = runBlocking {
        val valid = snapshot(100, 20)
        val invalid = listOf(
            valid.copy(usage = TokenUsage(-1, 20)),
            valid.copy(usage = TokenUsage(100, -1)),
            valid.copy(usage = TokenUsage(Long.MAX_VALUE, 1)),
            valid.copy(contextKey = ""),
            valid.copy(contextKey = " \n"),
            valid.copy(messagesFingerprint = ""),
            valid.copy(messagesFingerprint = "\t")
        )
        val source = conversation(invalid.mapIndexed { index, usage ->
            MessageVersion(content = "Response $index", contextUsage = usage)
        })
        ConversationRepository(db).saveConversation(source)

        val restored = ConversationRepository(db).loadById(source.id)!!
        assertEquals(source.messages.single().versions.map { it.copy(contextUsage = null) }, restored.messages.single().versions)
        db.conversationDao().getMessageVersions(source.id, "answer").forEach(::assertNoUsage)
    }

    @Test fun incompleteOrInvalidStoredSnapshotsFallBackToNullWithoutLosingTheVersion() = runBlocking {
        val source = sampleConversation()
        val dao = db.conversationDao()
        ConversationRepository(db).saveConversation(source)
        val original = dao.getMessageVersions(source.id, "answer").first()
        val invalidRows = listOf(
            original.copy(usageInputTokens = null),
            original.copy(usageContextKey = null),
            original.copy(usageMessagesFingerprint = null),
            original.copy(usageContextKey = ""),
            original.copy(usageContextKey = " \n"),
            original.copy(usageMessagesFingerprint = ""),
            original.copy(usageMessagesFingerprint = "\t"),
            original.copy(usageInputTokens = -1),
            original.copy(usageOutputTokens = -1),
            original.copy(usageInputTokens = Long.MAX_VALUE, usageOutputTokens = 1),
            original.copy(usageInputTokens = null, usageOutputTokens = null, usageContextKey = null, usageMessagesFingerprint = null)
        )
        val answer = source.messages.single()
        val expected = source.copy(messages = listOf(answer.copy(
            versions = answer.versions.mapIndexed { index, version ->
                if (index == 0) version.copy(contextUsage = null) else version
            }
        )))
        invalidRows.forEachIndexed { index, row ->
            dao.updateMessageVersion(row)
            assertEquals("Invalid stored snapshot case $index", expected, ConversationRepository(db).loadById(source.id))
        }
    }

    @Test fun jsonExportImportRoundTripsSnapshotsThroughFreshStorage() = runBlocking {
        val source = sampleConversation()
        ConversationRepository(db).saveConversation(source)
        val export = buildJsonExport(emptyList(), emptyList(), emptyList(), listOf(ConversationRepository(db).loadById(source.id)!!))
        val exportedVersion = Json.parseToJsonElement(export).jsonObject.getValue("conversations").jsonArray
            .single().jsonObject.getValue("messages").jsonArray.single().jsonObject.getValue("versions").jsonArray.first().jsonObject
        assertTrue(exportedVersion.containsKey("context_usage"))
        assertFalse(exportedVersion.containsKey("contextUsage"))
        val imported = parseFullAppDataExport(export).conversations!!.single()
        assertEquals(source, imported)

        val destination = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            ConversationRepository(destination).saveConversation(imported)
            assertEquals(source, ConversationRepository(destination).loadById(source.id))
        } finally { destination.close() }
    }

    @Test fun legacyJsonWithoutUsageAndExplicitNullUsageImportAsNull() = runBlocking {
        val imported = parseFullAppDataExport("""
            {"formatVersion":1,"type":"full_backup","conversations":[{
                "id":"legacy","title":"Legacy","created_at":1,"updated_at":2,
                "messages":[{"id":"answer","role":"model","active_version_index":1,"versions":[
                    {"content":"Earlier response"},
                    {"content":"Current response","context_usage":null}
                ]}]
            }]}
        """.trimIndent()).conversations!!.single()
        imported.messages.single().versions.forEach { assertNull(it.contextUsage) }
        ConversationRepository(db).saveConversation(imported)

        assertEquals(imported, ConversationRepository(db).loadById(imported.id))
        db.conversationDao().getMessageVersions(imported.id, "answer").forEach(::assertNoUsage)
    }

    private fun sampleConversation() = conversation(listOf(
        MessageVersion(content = "Earlier response", contextUsage = snapshot(4_294_967_296L, 4_294_967_297L, "earlier")),
        MessageVersion(content = "Active response", contextUsage = snapshot(0, 0, "active")),
        MessageVersion(content = "Inactive response", contextUsage = snapshot(512, null, "inactive")),
        MessageVersion(content = "Legacy response")
    ), activeVersionIndex = 1)

    private fun conversation(versions: List<MessageVersion>, activeVersionIndex: Int = 0) = Conversation(
        "usage", "Context usage", 1, 2,
        messages = listOf(StoredMessage("answer", ChatRole.MODEL, versions, activeVersionIndex))
    )

    private fun snapshot(inputTokens: Long, outputTokens: Long?, suffix: String = "test") = ContextUsageSnapshot(
        TokenUsage(inputTokens, outputTokens), "context-$suffix", "messages-$suffix"
    )

    private fun assertNoUsage(row: MessageVersionEntity) {
        assertNull(row.usageInputTokens)
        assertNull(row.usageOutputTokens)
        assertNull(row.usageContextKey)
        assertNull(row.usageMessagesFingerprint)
    }
}
