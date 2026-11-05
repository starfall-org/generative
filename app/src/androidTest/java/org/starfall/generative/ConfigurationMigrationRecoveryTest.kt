package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.database.repositories.McpRepository
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.integrations.security.SecretCipher

/** Failure and recovery cases against released v9 rows, real Room, and Android Keystore. */
class ConfigurationMigrationRecoveryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private data class InvalidCell(val table: String, val column: String, val id: String, val value: String)

    @Test fun malformedRequiredConfigurationRollsBackEveryReleasedV9SourceCell() {
        val invalidCells = listOf(
            InvalidCell("chat_profiles", "configJson", "profile-v9", "{invalid profile}"),
            InvalidCell("chat_profiles", "activeMcpJson", "profile-v9", "{}"),
            InvalidCell("chat_profiles", "activeModelToolsJson", "profile-v9", "keystore:v1:unreadable"),
            InvalidCell("model_providers", "configJson", "provider-v9", SecretCipher.encrypt("{invalid provider}")),
            InvalidCell("model_providers", "authJson", "provider-v9", "keystore:v1:unreadable"),
            InvalidCell("mcp_servers", "headersJson", "mcp-v9", "{\"headers\":[]}")
        )
        for (cell in invalidCells) {
            val name = "v9-required-${UUID.randomUUID()}"
            val room = openRoom(name)
            try {
                CredentialV9Fixture.create(context, name)
                val original = CredentialV9Fixture.openSource(context, name).use { helper ->
                    val source = helper.writableDatabase
                    source.execSQL("UPDATE ${cell.table} SET ${cell.column} = ? WHERE id = ?",
                        arrayOf(cell.value, cell.id))
                    CredentialV9Fixture.snapshot(source)
                }
                val failure = runCatching { room.openHelper.writableDatabase }.exceptionOrNull()
                assertNotNull("Required ${cell.table}.${cell.column} must not become a default", failure)
                assertTrue(generateSequence(failure) { it.cause }.any {
                    it.message?.contains("Configuration v9 to v10 migration cannot") == true
                })
                room.close()
                CredentialV9Fixture.openSource(context, name).use { helper ->
                    assertEquals(9, helper.writableDatabase.version)
                    assertEquals(original, CredentialV9Fixture.snapshot(helper.writableDatabase))
                }
            } finally { room.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun optionalCacheFailuresRetainExactEncryptedSourcesAndOrphanCatalogs() = runBlocking {
        val sources = listOf("{invalid cache}", SecretCipher.encrypt("{invalid cache}"), "keystore:v1:unreadable")
        for (raw in sources) {
            val name = "v9-cache-recovery-${UUID.randomUUID()}"
            val room = openRoom(name)
            try {
                CredentialV9Fixture.create(context, name)
                CredentialV9Fixture.openSource(context, name).use { helper ->
                    val source = helper.writableDatabase
                    source.execSQL("UPDATE provider_models SET modelsJson = ? WHERE id = 'provider-v9'", arrayOf(raw))
                    source.execSQL("INSERT INTO provider_models(id, modelsJson) VALUES('orphan-provider', ?)", arrayOf(raw))
                    source.execSQL("UPDATE mcp_servers SET cachedToolsJson = ? WHERE id = 'mcp-v9'", arrayOf(raw))
                }
                val sql = room.openHelper.writableDatabase
                assertEquals(10, sql.version)
                val providers = ModelRepository(room, AiService(context))
                val servers = McpRepository(room, McpService())
                assertEquals("v9-provider-token", providers.getProviderById("provider-v9")!!.auth.value)
                assertNull(providers.getModelsForProvider("provider-v9"))
                assertEquals("v9-mcp-token", servers.getById("mcp-v9")!!.auth.value)
                assertNull(servers.getById("mcp-v9")!!.cachedTools)
                assertEquals("ERROR", room.mcpServerDao().getServerById("mcp-v9")!!.cacheState)
                val expected = setOf(
                    Triple("provider_catalog", "provider-v9", "unreadable_cache"),
                    Triple("provider_catalog", "orphan-provider", "orphan_catalog"),
                    Triple("mcp", "mcp-v9", "unreadable_cache")
                )
                val actual = buildSet {
                    sql.query("""
                        SELECT ownerType, ownerId, errorCode, rawValue FROM configuration_migration_quarantine
                        WHERE fieldName IN ('models', 'cachedTools')
                    """.trimIndent()).use { issues ->
                        while (issues.moveToNext()) {
                            add(Triple(issues.getString(0), issues.getString(1), issues.getString(2)))
                            val retained = issues.getString(3)
                            assertTrue(SecretCipher.isEncrypted(retained))
                            if (SecretCipher.isEncrypted(raw)) assertEquals(raw, retained)
                            else assertEquals(raw, SecretCipher.decryptStrict(retained))
                        }
                    }
                }
                assertEquals(expected, actual)
                assertFalse(sql.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
            } finally { room.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun migrationMetadataReadsStayIndependentOfUnreadableUnrelatedAuth() = runBlocking {
        val name = "v9-metadata-auth-${UUID.randomUUID()}"
        val room = openRoom(name)
        try {
            CredentialV9Fixture.create(context, name)
            val sql = room.openHelper.writableDatabase
            val providerAuthId = room.modelProviderDao().getProviderById("provider-v9")!!.authId!!
            val serverAuthId = room.mcpServerDao().getServerById("mcp-v9")!!.authId!!
            val corrupt = "keystore:v1:unreadable"
            sql.execSQL("UPDATE provider_auth SET encryptedPayload = ? WHERE id = ?", arrayOf(corrupt, providerAuthId))
            sql.execSQL("UPDATE mcp_auth SET encryptedPayload = ? WHERE id = ?", arrayOf(corrupt, serverAuthId))
            val providers = ModelRepository(room, AiService(context))
            val servers = McpRepository(room, McpService())
            val providerMetadata = providers.allProviders.first()
            val serverMetadata = servers.allServers.first()
            assertEquals(2, providerMetadata.size)
            assertEquals(2, serverMetadata.size)
            val provider = providerMetadata.single { it.id == "provider-v9" }
            val server = serverMetadata.single { it.id == "mcp-v9" }
            assertEquals(CredentialLoadState.NOT_REQUESTED, provider.credentialState)
            assertEquals(CredentialLoadState.NOT_REQUESTED, server.credentialState)
            assertEquals("https://provider.example.test/v1", provider.baseUrl)
            assertEquals("https://mcp.example.test/tools", server.url)
            assertNull(provider.auth.value)
            assertNull(server.auth.value)
            assertTrue(provider.config.headers.isEmpty())
            assertNull(server.headers)
            assertEquals(CredentialLoadState.NOT_CONFIGURED, providers.getProviderById("provider-none")!!.credentialState)
            assertEquals(CredentialLoadState.NOT_CONFIGURED, servers.getById("mcp-none")!!.credentialState)
            val providerFailure = runCatching { providers.resolveProvider(provider) }.exceptionOrNull()
            val serverFailure = runCatching { servers.resolveServer(server) }.exceptionOrNull()
            assertTrue(providerFailure is IllegalStateException)
            assertTrue(providerFailure!!.message.orEmpty().contains("credentials are unreadable"))
            assertTrue(serverFailure is IllegalStateException)
            assertTrue(serverFailure!!.message.orEmpty().contains("credentials are unreadable"))
            assertEquals(corrupt, room.modelProviderDao().getAuth(providerAuthId)!!.encryptedPayload)
            assertEquals(corrupt, room.mcpServerDao().getAuth(serverAuthId)!!.encryptedPayload)
        } finally { room.close(); context.deleteDatabase(name) }
    }

    private fun openRoom(name: String): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_9_10).build()
}
