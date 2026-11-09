package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationSummary
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.generation.ContextUsageSnapshot
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.room.AppDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ConversationContextUsageMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun migration11To12RetainsLegacyVersionsAndTheirDescendants() = runBlocking {
        val name = "context-usage-v11-${UUID.randomUUID()}"
        var room: AppDatabase? = null
        try {
            val originalRowIds = openVersion11(name).use { helper ->
                val source = helper.writableDatabase
                assertEquals(11, source.version)
                versionRowIds(source)
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13,
                    AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15,
                    AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17).build()
            room = migrated
            val sql = migrated.openHelper.writableDatabase
            assertEquals(17, sql.version)
            assertEquals(originalRowIds, versionRowIds(sql))
            assertUsageColumns(sql)

            val expected = legacyConversation()
            val restored = ConversationRepository(migrated).loadById(expected.id)!!
            assertEquals(expected, restored)
            migrated.conversationDao().getMessageVersions(expected.id, "answer").forEach {
                assertNull(it.usageInputTokens)
                assertNull(it.usageOutputTokens)
                assertNull(it.usageContextKey)
                assertNull(it.usageMessagesFingerprint)
            }

            val usage = ContextUsageSnapshot(TokenUsage(256, null), "context-key", "messages-fingerprint")
            val answer = restored.messages.single()
            val updated = restored.copy(remoteWorkspaces = mapOf("ssh" to "/home/dev/project"), messages = listOf(answer.copy(versions = answer.versions.mapIndexed { index, version ->
                if (index == answer.activeVersionIndex) version.copy(contextUsage = usage) else version
            })))
            ConversationRepository(migrated).saveConversation(updated)
            assertEquals(updated, ConversationRepository(migrated).loadById(updated.id))
            assertEquals(originalRowIds, versionRowIds(sql))
            val cleared = updated.copy(remoteWorkspaces = emptyMap())
            ConversationRepository(migrated).saveConversation(cleared)
            assertEquals(cleared, ConversationRepository(migrated).loadById(cleared.id))
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            room?.close()
            context.deleteDatabase(name)
        }
    }

    private fun openVersion11(name: String): SupportSQLiteOpenHelper {
        // Use the archived schema directly, independent of the current entities and migrations.
        val path = "schemas/${AppDatabase::class.java.name}/11.json"
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: error("Cannot find the archived Room v11 schema at $path")
        val schema = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                    override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.forEach { element ->
                            val entity = element.jsonObject
                            val table = entity.getValue("tableName").jsonPrimitive.content
                            db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                            entity["indices"]?.jsonArray?.forEach { index ->
                                db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                            }
                        }
                        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                        insertLegacyRows(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("The source fixture must be created directly at v11")
                }).build()
        )
    }

    private fun insertLegacyRows(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO conversations VALUES ('legacy', 'Legacy', 1, 2, 42, 'provider', 'model', NULL, NULL)")
        db.execSQL("INSERT INTO messages VALUES ('legacy', 'answer', 0, 'model', 1)")
        db.execSQL("""
            INSERT INTO message_versions VALUES
                ('legacy', 'answer', 0, 'Earlier response', 'earlier', NULL, NULL, NULL, '', '', '', 0, NULL),
                ('legacy', 'answer', 1, 'Current response', 'current', 'Reasoning', 'signature', 123, 'provider', 'model', 'Model', 0, NULL)
        """.trimIndent())
        db.execSQL("INSERT INTO message_version_files VALUES ('legacy', 'answer', 1, 0, 'answer.txt')")
        db.execSQL("""
            INSERT INTO tool_activities VALUES
                ('legacy', 'answer', 1, 0, 'tool', 'Search', '', 'success', '', '', '', 0, NULL, 0, 0)
        """.trimIndent())
        db.execSQL("INSERT INTO tool_activity_files VALUES ('legacy', 'answer', 1, 0, 0, 'result.json')")
        db.execSQL("INSERT INTO conversation_summaries VALUES ('legacy', 'summary', 'Summary', 'answer', 'SYSTEM', 3)")
    }

    private fun legacyConversation() = Conversation(
        "legacy", "Legacy", 1, 2,
        messages = listOf(StoredMessage("answer", ChatRole.MODEL, listOf(
            MessageVersion(content = "Earlier response", timestamp = "earlier"),
            MessageVersion(
                content = "Current response", timestamp = "current", files = listOf("answer.txt"),
                reasoningContent = "Reasoning", reasoningSignature = "signature", processingFinishedAt = 123,
                providerId = "provider", modelId = "model", modelDisplayName = "Model",
                toolActivity = listOf(ToolActivity("tool", "Search", status = "success", files = listOf("result.json")))
            )
        ), activeVersionIndex = 1)),
        tokenCount = 42, providerId = "provider", modelId = "model",
        summary = ConversationSummary("summary", "Summary", "answer", createdAt = 3)
    )

    private fun versionRowIds(db: SupportSQLiteDatabase): List<Long> =
        db.query("SELECT rowid FROM message_versions ORDER BY versionIndex").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

    private fun assertUsageColumns(db: SupportSQLiteDatabase) {
        val types = db.query("PRAGMA table_info(message_versions)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (name.startsWith("usage")) {
                        put(name, cursor.getString(2))
                        assertEquals("$name must remain nullable", 0, cursor.getInt(3))
                        assertTrue("$name must default to NULL", cursor.isNull(4))
                    }
                }
            }
        }
        assertEquals(mapOf(
            "usageInputTokens" to "INTEGER", "usageOutputTokens" to "INTEGER",
            "usageContextKey" to "TEXT", "usageMessagesFingerprint" to "TEXT"
        ), types)
    }
}
