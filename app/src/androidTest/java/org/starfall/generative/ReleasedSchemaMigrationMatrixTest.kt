package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.database.repositories.ConversationMigrationException
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.repositories.McpRepository
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.repositories.SpeechRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.integrations.security.SecretCipher

/** Every released source schema is opened directly, then migrated once to the v10 target. */
class ReleasedSchemaMigrationMatrixTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun everyReleasedSchemaV1ThroughV9MigratesDirectlyToV10() = runBlocking {
        for (sourceVersion in 1..9) {
            val name = "released-v$sourceVersion-${UUID.randomUUID()}"
            var room: AppDatabase? = null
            try {
                if (sourceVersion == 9) {
                    CredentialV9Fixture.create(context, name)
                    CredentialV9Fixture.addMalformedConversation(context, name)
                } else {
                    ReleasedSchemaMatrixFixture.create(context, name, sourceVersion)
                }
                room = openRoom(name)
                val db = room.openHelper.writableDatabase

                assertEquals("source v$sourceVersion must finish at target v10", 10, db.version)
                assertFinalRows(room, db, sourceVersion)
                assertCredentials(room, db, sourceVersion)
                assertConversationQuarantine(room, db, sourceVersion)
                assertFalse(db.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
            } finally {
                room?.close()
                context.deleteDatabase(name)
            }
        }
    }

    private fun openRoom(name: String): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10
        ).build()

    private suspend fun assertFinalRows(room: AppDatabase, db: SupportSQLiteDatabase, sourceVersion: Int) {
        assertEquals(2L, count(db, "conversations"))
        assertEquals(2L, count(db, "model_providers"))
        assertEquals(2L, count(db, "mcp_servers"))
        assertEquals(if (sourceVersion == 1) 0L else 2L, count(db, "speech_services"))
        assertEquals(if (sourceVersion >= 6) 1L else 0L, count(db, "provider_groups"))
        assertEquals(if (sourceVersion >= 3) 1L else 0L, count(db, "mcp_cached_tools"))
        assertEquals(2L, count(db, "mcp_tool_caches"))

        val providerIds = if (sourceVersion == 9) listOf("provider-v9", "provider-none")
        else listOf(ReleasedSchemaMatrixFixture.PROVIDER_ID, ReleasedSchemaMatrixFixture.NO_AUTH_PROVIDER_ID)
        assertEquals(providerIds, ids(db, "SELECT id FROM model_providers ORDER BY sortOrder, id"))
        val mcpIds = if (sourceVersion == 9) listOf("mcp-v9", "mcp-none")
        else listOf(ReleasedSchemaMatrixFixture.MCP_ID, ReleasedSchemaMatrixFixture.NO_AUTH_MCP_ID)
        assertEquals(mcpIds, ids(db, "SELECT id FROM mcp_servers ORDER BY sortOrder, id"))
        val speechIds = when {
            sourceVersion == 1 -> emptyList()
            sourceVersion == 9 -> listOf("speech-v9", "speech-defaults")
            else -> listOf(ReleasedSchemaMatrixFixture.SPEECH_ID, ReleasedSchemaMatrixFixture.NO_AUTH_SPEECH_ID)
        }
        assertEquals(speechIds, ids(db, "SELECT id FROM speech_services ORDER BY sortOrder, id"))

        val conversationIds = if (sourceVersion == 9) listOf("conversation-v9", "matrix-broken")
        else listOf(ReleasedSchemaMatrixFixture.CONVERSATION_ID, ReleasedSchemaMatrixFixture.BROKEN_CONVERSATION_ID)
        assertEquals(conversationIds, ids(db, "SELECT id FROM conversations ORDER BY updatedAt DESC, id DESC"))
        val richConversationId = conversationIds.first()
        val history = ConversationRepository(room).loadById(richConversationId)!!
        if (sourceVersion == 9) {
            assertEquals("Legacy JSON history", history.messages.single().activeVersion.content)
            assertEquals(listOf("fixture.txt"), history.messages.single().activeVersion.files)
        } else {
            assertEquals("Matrix content", history.messages.single().activeVersion.content)
            assertEquals(listOf("matrix.txt"), history.messages.single().activeVersion.files)
            assertEquals("Matrix summary", history.summary?.content)
            assertEquals("high", history.reasoningEffort)
        }
        assertEquals(1, history.messages.size)
        assertEquals(if (sourceVersion in 5..8) 1L else 0L, count(db, "conversation_summaries"))

        val modelIds = if (sourceVersion == 9) listOf("z-model", "custom", "a-model")
        else listOf(ReleasedSchemaMatrixFixture.MODEL_ID)
        assertEquals(modelIds, ids(db, "SELECT modelId FROM provider_model_selection WHERE providerId = ? ORDER BY ordinal",
            providerIds.first()))
        assertEquals(if (sourceVersion == 9) 2L else 1L, count(db, "provider_catalog_models"))
        assertEquals(if (sourceVersion == 9) 2L else 1L, count(db, "provider_catalog_tools"))

        val cacheStates = db.query("SELECT serverId, state FROM mcp_tool_caches ORDER BY serverId").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
            }
        }
        val expectedReadyId = if (sourceVersion >= 3) mcpIds.first() else null
        assertEquals(
            mcpIds.map { it to if (it == expectedReadyId) "READY" else "NO_CACHE" }.sortedBy { it.first },
            cacheStates
        )
    }

    private suspend fun assertCredentials(room: AppDatabase, db: SupportSQLiteDatabase, sourceVersion: Int) {
        val providerId = if (sourceVersion == 9) "provider-v9" else ReleasedSchemaMatrixFixture.PROVIDER_ID
        val noAuthProviderId = if (sourceVersion == 9) "provider-none" else ReleasedSchemaMatrixFixture.NO_AUTH_PROVIDER_ID
        val mcpId = if (sourceVersion == 9) "mcp-v9" else ReleasedSchemaMatrixFixture.MCP_ID
        val noAuthMcpId = if (sourceVersion == 9) "mcp-none" else ReleasedSchemaMatrixFixture.NO_AUTH_MCP_ID
        val speechId = when {
            sourceVersion == 9 -> "speech-v9"
            else -> ReleasedSchemaMatrixFixture.SPEECH_ID
        }
        val noAuthSpeechId = when {
            sourceVersion == 9 -> "speech-defaults"
            else -> ReleasedSchemaMatrixFixture.NO_AUTH_SPEECH_ID
        }

        val providerAuthId = nullableString(db, "SELECT authId FROM model_providers WHERE id = ?", providerId)
        val mcpAuthId = nullableString(db, "SELECT authId FROM mcp_servers WHERE id = ?", mcpId)
        val speechAuthId = if (sourceVersion == 1) null else
            nullableString(db, "SELECT authId FROM speech_services WHERE id = ?", speechId)
        assertNotNull(providerAuthId)
        assertNotNull(mcpAuthId)
        if (sourceVersion == 1) assertNull(speechAuthId) else assertNotNull(speechAuthId)
        assertNotEquals(providerAuthId, mcpAuthId)
        assertNotEquals(providerAuthId, speechAuthId)
        assertNotEquals(mcpAuthId, speechAuthId)

        providerAuthId?.let { id ->
            val encrypted = dbText(db, "SELECT encryptedPayload FROM provider_auth WHERE id = ?", id)
            assertTrue(SecretCipher.isEncrypted(encrypted))
            assertFalse(encrypted.contains("matrix-provider-token"))
            assertFalse(encrypted.contains("v9-provider-token"))
        }
        mcpAuthId?.let { id ->
            val encrypted = dbText(db, "SELECT encryptedPayload FROM mcp_auth WHERE id = ?", id)
            assertTrue(SecretCipher.isEncrypted(encrypted))
            assertFalse(encrypted.contains("matrix-mcp-token"))
            assertFalse(encrypted.contains("v9-mcp-token"))
        }
        speechAuthId?.let { id ->
            val encrypted = dbText(db, "SELECT encryptedApiKey FROM speech_auth WHERE id = ?", id)
            assertTrue(SecretCipher.isEncrypted(encrypted))
            assertFalse(encrypted.contains("matrix-speech-token"))
            assertFalse(encrypted.contains("v9-speech-token"))
        }

        assertNull(nullableString(db, "SELECT authId FROM model_providers WHERE id = ?", noAuthProviderId))
        assertNull(nullableString(db, "SELECT authId FROM mcp_servers WHERE id = ?", noAuthMcpId))
        if (sourceVersion != 1) {
            assertNull(nullableString(db, "SELECT authId FROM speech_services WHERE id = ?", noAuthSpeechId))
        }

        val providers = ModelRepository(room, AiService(context))
        val provider = providers.getProviderById(providerId)!!
        assertEquals(if (sourceVersion == 9) "v9-provider-token" else "matrix-provider-token", provider.auth.value)
        assertEquals(CredentialLoadState.LOADED, provider.credentialState)
        val noAuthProvider = providers.getProviderById(noAuthProviderId)!!
        assertEquals(AuthMethod.NONE, noAuthProvider.auth.method)
        assertEquals(CredentialLoadState.NOT_CONFIGURED, noAuthProvider.credentialState)

        val servers = McpRepository(room, McpService())
        assertEquals(if (sourceVersion == 9) "v9-mcp-token" else "matrix-mcp-token", servers.getById(mcpId)!!.auth.value)
        assertEquals(CredentialLoadState.NOT_CONFIGURED, servers.getById(noAuthMcpId)!!.credentialState)
        if (sourceVersion != 1) {
            assertEquals(if (sourceVersion == 9) "v9-speech-token" else "matrix-speech-token", SpeechRepository(room).getById(speechId)!!.apiKey)
        }
    }

    private suspend fun assertConversationQuarantine(room: AppDatabase, db: SupportSQLiteDatabase, sourceVersion: Int) {
        val brokenId = "matrix-broken"
        val issues = db.query(
            "SELECT payloadKind, rawJson, errorCode FROM conversation_migration_quarantine WHERE conversationId = ? ORDER BY payloadKind",
            arrayOf(brokenId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            }
        }
        val expectedKinds = if (sourceVersion >= 5) listOf("messages_json", "summary_json") else listOf("messages_json")
        assertEquals(expectedKinds, issues.map { it.first })
        assertTrue(issues.all { it.third == "invalid_json" })
        assertTrue(issues.all { it.second == "not valid conversation json" || it.second == "{not a summary}" })
        val failure = try {
            ConversationRepository(room).loadById(brokenId)
            null
        } catch (error: Throwable) {
            error
        }
        assertTrue(failure is ConversationMigrationException)
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun ids(db: SupportSQLiteDatabase, sql: String, vararg args: Any?): List<String> =
        db.query(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun nullableString(db: SupportSQLiteDatabase, sql: String, vararg args: Any?): String? =
        db.query(sql, args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun dbText(db: SupportSQLiteDatabase, sql: String, vararg args: Any?): String =
        db.query(sql, args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
}
