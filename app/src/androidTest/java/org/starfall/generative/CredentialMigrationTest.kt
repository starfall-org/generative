package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.database.repositories.ConversationMigrationException
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.repositories.McpRepository
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.repositories.ProfileRepository
import org.starfall.generative.database.repositories.SpeechRepository
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.language.ThinkingPassback
import java.util.UUID

/** Run on Android: real Room schema validation and real Keystore, never a fake cipher. */
class CredentialMigrationTest {
    private val fixtureJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun v1UpgradePreservesRowsAndMovesCredentialsToRelationalAuth() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, messagesJson TEXT NOT NULL, tokenCount INTEGER, providerId TEXT NOT NULL, modelId TEXT NOT NULL, profileId TEXT)")
                    db.execSQL("CREATE TABLE chat_profiles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, icon TEXT, configJson TEXT NOT NULL, activeMcpJson TEXT NOT NULL, activeModelToolsJson TEXT NOT NULL)")
                    db.execSQL("CREATE TABLE ${"ll" + "m_providers"} (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, baseUrl TEXT NOT NULL, authJson TEXT NOT NULL, configJson TEXT NOT NULL, icon TEXT)")
                    db.execSQL("CREATE TABLE ${"ll" + "m_models"} (id TEXT NOT NULL PRIMARY KEY, modelsJson TEXT NOT NULL)")
                    db.execSQL("CREATE TABLE mcp_servers (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, protocol TEXT NOT NULL, url TEXT, headersJson TEXT)")
                    db.execSQL("INSERT INTO conversations VALUES ('c','keep',1,2,'[]',NULL,'p','m','profile')")
                    db.execSQL("INSERT INTO chat_profiles VALUES ('profile','keep',NULL,'{}','[]','[]')")
                    db.execSQL("INSERT INTO ${"ll" + "m_providers"} VALUES ('p','keep','OPENAI','https://example.com','legacy-secret','{}',NULL)")
                    db.execSQL("INSERT INTO mcp_servers VALUES ('s','keep','SSE',NULL,'legacy-header')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10
        ).build()
        try {
            val db = room.openHelper.writableDatabase // Room validates every migrated table.
            assertEquals(10, db.version)
            val provider = ModelRepository(room, AiService(context)).getProviderById("p")!!
            assertEquals("keep", provider.name)
            assertEquals("legacy-secret", provider.auth.value)
            val providerRow = room.modelProviderDao().getProviderById("p")!!
            val providerAuth = room.modelProviderDao().getAuth(providerRow.authId!!)!!
            assertTrue(SecretCipher.isEncrypted(providerAuth.encryptedPayload))
            assertEquals("legacy-secret", fixtureJson.parseToJsonElement(SecretCipher.decryptStrict(providerAuth.encryptedPayload))
                .jsonObject.getValue("authorization").jsonObject.getValue("value").jsonPrimitive.content)
            val server = McpRepository(room, McpService()).getById("s")!!
            assertEquals(mapOf("Authorization" to "legacy-header"), server.headers)
            val mcpAuthId = room.mcpServerDao().getServerById("s")!!.authId!!
            assertTrue(SecretCipher.isEncrypted(room.mcpServerDao().getAuth(mcpAuthId)!!.encryptedPayload))
            assertEquals("keep", ConversationRepository(room).loadById("c")!!.title)
            assertEquals("keep", ProfileRepository(room).getById("profile")!!.name)
            assertEquals("", room.chatProfileDao().getConfig("profile")!!.systemPrompt)
            assertFalse(db.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
        } finally { room.close(); context.deleteDatabase(name) }
    }
    @Test fun corruptedEnvelopeFailsWithoutPlaintextFallback() {
        val encrypted = SecretCipher.encrypt("secret")
        val error = runCatching { SecretCipher.decrypt(encrypted.dropLast(8) + "AAAAAAAA") }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test fun v9FixturePreservesRepresentativePayloadsForMigrationBaseline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "v9-fixture-${UUID.randomUUID()}"
        createV9Fixture(context, name)
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_9_10)
            .build()
        try {
            assertEquals(10, room.openHelper.writableDatabase.version)
            val repository = ConversationRepository(room)
            val list = repository.conversationList.first()
            assertEquals(listOf("rich", "malformed"), list.map { it.id })
            assertTrue(list.first().hasMessages)
            assertTrue(list.first().hasSummary)
            assertTrue(list.last().hasMessages)
            assertTrue(list.last().hasSummary)

            val rich = repository.loadById("rich") ?: error("v9 fixture conversation was not readable")
            assertEquals("Rich history", rich.title)
            assertEquals(20L, rich.updatedAt)
            assertEquals(123, rich.tokenCount)
            assertEquals("provider-rich", rich.providerId)
            assertEquals("catalog-model", rich.modelId)
            assertEquals("profile-rich", rich.profileId)
            assertEquals("high", rich.reasoningEffort)
            assertEquals(
                ConversationSummary("summary-1", "Important decisions", "assistant-1", SummaryRole.SYSTEM, 19),
                rich.summary
            )

            val user = rich.messages[0]
            assertEquals("user-1", user.id)
            assertEquals(ChatRole.USER, user.role)
            assertEquals(2, user.versions.size)
            assertEquals(1, user.activeVersionIndex)
            assertEquals(listOf("shared.txt", "image.png"), user.activeVersion.files)
            assertEquals("revised request", user.activeVersion.content)

            val assistant = rich.messages[1]
            assertEquals("assistant-1", assistant.id)
            assertEquals(2, assistant.versions.size)
            assertEquals("", assistant.versions[0].content)
            assertEquals("failed", assistant.versions[0].generationError)
            assertEquals("provider-rich", assistant.activeVersion.providerId)
            assertEquals("catalog-model", assistant.activeVersion.modelId)
            assertEquals("signature", assistant.activeVersion.reasoningSignature)
            assertEquals(18L, assistant.activeVersion.processingFinishedAt)
            assertEquals(listOf("shared.txt", "result.json"), assistant.activeVersion.files)
            val activity = assistant.activeVersion.toolActivity.single()
            assertEquals("activity-1", activity.id)
            assertEquals("Search", activity.name)
            assertEquals("search", activity.wireName)
            assertEquals("interrupted", activity.status)
            assertEquals("The previous run did not finish.", activity.summary)
            assertEquals("query", activity.arguments)
            assertEquals("full tool response", activity.response)
            assertEquals(42, activity.contentOffset)
            assertEquals(7, activity.reasoningOffset)
            assertTrue(activity.inlineMedia)
            assertEquals(80, activity.responsePreviewLimit)

            val provider = ModelRepository(room, AiService(context)).getProviderById("provider-rich")!!
            assertEquals("https://api.example.test/v1", provider.baseUrl)
            assertEquals("provider-secret", provider.auth.value)
            assertEquals(listOf("catalog-model"), provider.config.modelIds)
            assertEquals(mapOf("X-Test" to "yes"), provider.config.headers)
            assertEquals(listOf("catalog-model"), room.modelProviderDao().getModelSelection(provider.id).map { it.modelId })
            assertTrue(SecretCipher.isEncrypted(room.modelProviderDao().getAuth(
                room.modelProviderDao().getProviderById(provider.id)!!.authId!!
            )!!.encryptedPayload))

            val catalog = ModelRepository(room, AiService(context)).getModelsForProvider("provider-rich")!!
            assertEquals("catalog-model", catalog.models.single().id)
            assertEquals("Catalog Model", catalog.models.single().displayName)
            assertEquals(listOf("search"), room.modelProviderDao().getCatalogTools(provider.id).map { it.toolName })

            val profile = ProfileRepository(room).getById("profile-rich")!!
            assertEquals("rich-profile", profile.name)
            assertEquals("default prompt", profile.config.systemPrompt)
            assertEquals(listOf("mcp-rich"), profile.activeMcp)
            assertEquals(listOf("search"), profile.activeModelTools)
            assertEquals("default prompt", room.chatProfileDao().getConfig(profile.id)!!.systemPrompt)
            assertEquals(listOf("mcp-rich"), room.chatProfileDao().getActiveMcp(profile.id).map { it.serverId })

            val mcp = McpRepository(room, McpService()).getById("mcp-rich")!!
            assertEquals("https://mcp.example.test", mcp.url)
            assertEquals("mcp-token", mcp.auth.value)
            val tools = mcp.cachedTools!!
            assertEquals("search", tools.single().originalName)
            assertEquals(listOf("query"), tools.single().schema["required"]?.let { fixtureJson.decodeFromJsonElement<List<String>>(it) })
            assertEquals("string", tools.single().schema["properties"]!!.jsonObject["query"]!!.jsonObject["type"]!!.toString().trim('"'))

            val speech = SpeechRepository(room).getById("speech-rich")!!
            assertEquals("speech-secret", speech.apiKey)
            assertEquals("en-US", speech.languageCode)
            assertEquals("speak clearly", room.speechServiceDao().getServiceById(speech.id)!!.instructions)
            assertTrue(SecretCipher.isEncrypted(room.speechServiceDao().getAuth(
                room.speechServiceDao().getServiceById(speech.id)!!.authId!!
            )!!.encryptedApiKey))

            // Malformed history must remain quarantined and must never appear as an empty chat.
            val malformedIssues = room.conversationDao().getMigrationIssues("malformed")
            assertEquals(setOf("invalid_json"), malformedIssues.map { it.errorCode }.toSet())
            assertEquals("not valid conversation json", malformedIssues.first { it.payloadKind == "messages_json" }.rawJson)
            assertEquals("{not a summary}", malformedIssues.first { it.payloadKind == "summary_json" }.rawJson)
            assertTrue(runCatching { repository.loadById("malformed") }.exceptionOrNull() is ConversationMigrationException)

            fun count(table: String): Long = room.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                it.getLong(0)
            }
            assertEquals(2L, count("messages"))
            assertEquals(4L, count("message_versions"))
            assertEquals(1L, count("tool_activities"))
            assertEquals(4L, count("message_version_files"))
            assertEquals(1L, count("tool_activity_files"))
            assertEquals(1L, count("conversation_summaries"))
            assertEquals(2L, count("conversation_migration_quarantine"))
            assertEquals(1L, count("provider_catalog_models"))
            assertEquals(1L, count("mcp_cached_tools"))
            assertTrue(room.openHelper.writableDatabase.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf("index_message_version_files_reference")
            ).use { it.moveToFirst() })
            assertFalse(room.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun realV9FixtureMigratesDirectlyToV10WithConfigurationAuthAndNestedPayloadsAndSurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "v9-direct-fixture-${UUID.randomUUID()}"
        fun openRoom() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_9_10).build()
        var room: AppDatabase? = null
        try {
            CredentialV9Fixture.create(context, name)
            val migrated = openRoom().also { room = it }
            val sql = migrated.openHelper.writableDatabase // Actual migration plus Room schema validation.
            assertEquals(10, sql.version)
            val providers = ModelRepository(migrated, AiService(context))
            val provider = providers.getProviderById("provider-v9")!!
            assertEquals(CredentialV9Fixture.PROVIDER_URL, provider.baseUrl)
            assertEquals("v9-provider-token", provider.auth.value)
            assertEquals(CredentialLoadState.LOADED, provider.credentialState)
            assertEquals("group-v9", provider.groupId)
            assertEquals("provider-icon", provider.icon)
            assertEquals(2, provider.sortOrder)
            assertFalse(provider.config.supportStream)
            assertTrue(provider.config.responsesApi)
            assertEquals(8192, provider.config.maxTokens)
            assertTrue(provider.config.multipleApiKeys)
            assertEquals(mapOf("X-Fixture" to "provider-header"), provider.config.headers)
            assertEquals(mapOf("url" to "http://proxy.example.test"), provider.config.httpProxy)
            assertEquals(mapOf("url" to "socks5://proxy.example.test"), provider.config.socksProxy)
            assertEquals(listOf("key-1", "key-2"), provider.config.apiKeys.map { it.id })
            assertEquals(listOf("v9-provider-token", "v9-backup-token"), provider.config.apiKeys.map { it.value })
            assertEquals("fixture-marker", provider.config.oauthAccounts.single().marker)
            assertEquals(listOf("z-model", "custom", "a-model"), provider.config.modelIds)
            assertEquals(listOf("z-model", "a-model"), provider.config.modelConfigs.keys.toList())
            val model = provider.config.modelConfigs.getValue("z-model")
            assertEquals(0.25, model.temperature!!, 0.0)
            assertEquals(0.75, model.topP!!, 0.0)
            assertEquals(8, model.topK)
            assertEquals(false, model.supportStream)
            assertEquals(64000, model.contextWindowTokens)
            assertTrue(model.supportsVideoInput)
            assertTrue(model.supportsAudioInput)
            assertFalse(model.supportsVision)
            assertFalse(model.supportsThinking)
            assertEquals(listOf("high", "low"), model.reasoningEfforts)
            assertEquals(listOf("search", "code"), model.modelTools)
            assertEquals(listOf("code"), model.enabledModelTools)
            assertEquals(ThinkingPassback.OFF, model.thinkingPassback)
            assertEquals(ModelType.EMBEDDING, provider.config.modelConfigs.getValue("a-model").modelType)
            assertEquals(CredentialV9Fixture.schema, model.modelJson)
            val providerDao = migrated.modelProviderDao()
            assertEquals("https://provider.example.test/v1", providerDao.getProviderById(provider.id)!!.baseUrl)
            assertEquals(listOf("z-model", "custom", "a-model"), providerDao.getModelSelection(provider.id).map { it.modelId })
            assertEquals(listOf("z-model", "a-model"), providerDao.getModelConfigurations(provider.id).map { it.modelId })
            val authId = providerDao.getProviderById(provider.id)!!.authId!!
            val encryptedAuth = providerDao.getAuth(authId)!!.encryptedPayload
            assertTrue(SecretCipher.isEncrypted(encryptedAuth))
            assertFalse(encryptedAuth.contains("v9-provider-token"))
            assertNull(providerDao.getProviderById("provider-none")!!.authId)
            val noAuth = providers.getProviderById("provider-none")!!
            assertEquals(emptyList<String>(), noAuth.config.modelIds)
            assertEquals(AuthMethod.NONE, noAuth.auth.method)
            assertEquals(CredentialLoadState.NOT_CONFIGURED, noAuth.credentialState)

            val catalog = providers.getModelsForProvider(provider.id)!!
            assertEquals(listOf("z-model", "a-model"), catalog.models.map { it.id })
            assertEquals(listOf("code", "search"), catalog.models.first().modelTools)
            assertEquals("remote-provider", catalog.models.first().providerId)
            assertFalse(catalog.models.first().inputCapabilities.text)
            assertTrue(catalog.models.first().inputCapabilities.audio)
            assertTrue(catalog.models.first().outputCapabilities.embed)
            assertEquals("input-extra", catalog.models.first().inputCapabilities.others)
            assertEquals("output-extra", catalog.models.first().outputCapabilities.others)
            assertEquals(listOf("code", "search"), providerDao.getCatalogTools(provider.id).map { it.toolName })

            val profile = ProfileRepository(migrated).getById("profile-v9")!!
            assertEquals("Exact prompt.\nSecond line.", profile.config.systemPrompt)
            assertEquals(listOf("mcp-v9", "mcp-none"), profile.activeMcp)
            assertEquals(listOf("code", "search"), profile.activeModelTools)
            assertEquals(McpAccess(true, linkedMapOf("search" to true, "delete" to false)), profile.config.mcpAccess["mcp-v9"])
            assertEquals(McpAccess(false, mapOf("read" to false)), profile.config.mcpAccess["mcp-none"])
            assertEquals(2, migrated.chatProfileDao().getMcpAccess(profile.id).size)
            assertEquals(3, migrated.chatProfileDao().getMcpTools(profile.id).size)

            val servers = McpRepository(migrated, McpService())
            val server = servers.getById("mcp-v9")!!
            assertEquals(CredentialV9Fixture.MCP_URL, server.url)
            assertEquals("v9-mcp-token", server.auth.value)
            assertEquals(mapOf("X-Fixture" to "mcp-header"), server.headers)
            assertEquals(CredentialV9Fixture.schema, server.cachedTools!!.single().schema)
            assertEquals("search", migrated.mcpServerDao().getCachedTools(server.id).single().originalName)
            assertNull(migrated.mcpServerDao().getServerById("mcp-none")!!.authId)
            assertNull(servers.getById("mcp-none")!!.cachedTools)
            assertEquals(CredentialLoadState.NOT_CONFIGURED, servers.getById("mcp-none")!!.credentialState)
            val speech = SpeechRepository(migrated).getById("speech-v9")!!
            assertEquals("v9-speech-token", speech.apiKey)
            val speechAuth = migrated.speechServiceDao().getAuth(
                migrated.speechServiceDao().getServiceById(speech.id)!!.authId!!
            )!!
            assertTrue(SecretCipher.isEncrypted(speechAuth.encryptedApiKey))
            assertEquals("v9-speech-token", SecretCipher.decryptStrict(speechAuth.encryptedApiKey))
            assertEquals("Speak clearly", speech.instructions)
            assertEquals("wav", speech.responseFormat)
            assertEquals("vi-VN", speech.languageCode)
            assertEquals(CredentialV9Fixture.extraBody, speech.extraBody)
            assertEquals(1.25f, speech.speed, 0f)
            assertEquals(0.75f, speech.pitch, 0f)
            val defaults = SpeechRepository(migrated).getById("speech-defaults")!!
            assertEquals(SpeechService("speech-defaults", "System voice", sortOrder = 8), defaults)
            assertNull(migrated.speechServiceDao().getServiceById(defaults.id)!!.authId)
            val history = ConversationRepository(migrated).loadById("conversation-v9")!!
            assertEquals("Legacy JSON history", history.messages.single().activeVersion.content)
            assertEquals(listOf("fixture.txt"), history.messages.single().activeVersion.files)
            sql.query("SELECT ownerType, ownerId, fieldName, rawValue, errorCode FROM configuration_migration_quarantine").use {
                assertTrue(it.moveToFirst())
                assertEquals("profile", it.getString(0))
                assertEquals(profile.id, it.getString(1))
                assertEquals("config", it.getString(2))
                assertTrue(SecretCipher.isEncrypted(it.getString(3)))
                assertEquals(CredentialV9Fixture.profileConfig, SecretCipher.decryptStrict(it.getString(3)))
                assertEquals("unrepresented_json", it.getString(4))
                assertFalse(it.moveToNext())
            }
            assertFalse(sql.query("PRAGMA foreign_key_check").use { it.moveToFirst() })

            migrated.close()
            val reopened = openRoom().also { room = it }
            assertEquals(10, reopened.openHelper.writableDatabase.version)
            assertEquals(provider, ModelRepository(reopened, AiService(context)).getProviderById(provider.id))
            assertEquals(catalog, ModelRepository(reopened, AiService(context)).getModelsForProvider(provider.id))
            assertEquals(profile, ProfileRepository(reopened).getById(profile.id))
            assertEquals(server, McpRepository(reopened, McpService()).getById(server.id))
            assertEquals(speech, SpeechRepository(reopened).getById(speech.id))
            assertEquals(history, ConversationRepository(reopened).loadById(history.id))
            assertEquals(encryptedAuth, reopened.modelProviderDao().getAuth(authId)!!.encryptedPayload)
        } finally {
            room?.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun v9ConfigurationFailureRollsBackConversationAndConfigurationChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (invalid in listOf("{invalid options}", "keystore:v1:unreadable")) {
            val name = "v9-rollback-${UUID.randomUUID()}"
            val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_9_10).build()
            try {
                CredentialV9Fixture.create(context, name)
                val original = CredentialV9Fixture.openSource(context, name).use { helper ->
                    val source = helper.writableDatabase
                    source.execSQL("UPDATE speech_services SET optionsJson = ? WHERE id = 'speech-v9'", arrayOf(invalid))
                    CredentialV9Fixture.snapshot(source)
                }
                val failure = runCatching { room.openHelper.writableDatabase }.exceptionOrNull()
                assertNotNull("Required configuration must abort the combined migration", failure)
                assertTrue(generateSequence(failure) { it.cause }.any {
                    it.message?.contains("Configuration v9 to v10 migration cannot") == true
                })
                room.close()
                CredentialV9Fixture.openSource(context, name).use { helper ->
                    val source = helper.writableDatabase
                    assertEquals(9, source.version)
                    assertEquals(original, CredentialV9Fixture.snapshot(source))
                    // Direct callers must also roll back both slices, without Room's transaction.
                    assertFalse(source.inTransaction())
                    assertNotNull(runCatching { AppDatabase.MIGRATION_9_10.migrate(source) }.exceptionOrNull())
                    assertFalse(source.inTransaction())
                    assertEquals(original, CredentialV9Fixture.snapshot(source))
                }
            } finally { room.close(); context.deleteDatabase(name) }
        }
    }

    private fun createV9Fixture(context: Context, name: String) {
        val richMessages = listOf(
            StoredMessage(
                id = "user-1",
                role = ChatRole.USER,
                versions = listOf(
                    MessageVersion(content = "original request", timestamp = "2026-01-01T00:00:00Z"),
                    MessageVersion(
                        content = "revised request",
                        timestamp = "2026-01-01T00:01:00Z",
                        files = listOf("shared.txt", "image.png"),
                        reasoningContent = "user reasoning"
                    )
                ),
                activeVersionIndex = 1
            ),
            StoredMessage(
                id = "assistant-1",
                role = ChatRole.MODEL,
                versions = listOf(
                    MessageVersion(content = "[Error: failed]", timestamp = "2026-01-01T00:02:00Z"),
                    MessageVersion(
                        content = "current answer",
                        timestamp = "2026-01-01T00:03:00Z",
                        files = listOf("shared.txt", "result.json"),
                        toolActivity = listOf(
                            ToolActivity(
                                id = "activity-1",
                                name = "Search",
                                wireName = "search",
                                status = "running",
                                summary = "Found a result",
                                files = listOf("result.json"),
                                arguments = "query",
                                response = "full tool response",
                                contentOffset = 42,
                                reasoningOffset = 7,
                                inlineMedia = true,
                                responsePreviewLimit = 80
                            )
                        ),
                        reasoningContent = "private reasoning",
                        reasoningSignature = "signature",
                        processingFinishedAt = 18,
                        providerId = "provider-rich",
                        modelId = "catalog-model",
                        modelDisplayName = "Catalog Model"
                    )
                ),
                activeVersionIndex = 1
            )
        )
        val summary = ConversationSummary("summary-1", "Important decisions", "assistant-1", SummaryRole.SYSTEM, 19)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        CredentialV9Fixture.createSchema(db)
                        db.execSQL(
                            "INSERT INTO conversations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            sqlArgs("rich", "Rich history", 10, 20, fixtureJson.encodeToString(richMessages), 123, "provider-rich", "catalog-model", "profile-rich", fixtureJson.encodeToString(summary), "high")
                        )
                        db.execSQL(
                            "INSERT INTO conversations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            sqlArgs("malformed", "Needs recovery", 5, 10, "not valid conversation json", null, "", "", null, "{not a summary}", null)
                        )
                        db.execSQL("INSERT INTO chat_profiles VALUES (?, ?, ?, ?, ?, ?, ?)", sqlArgs("profile-rich", "rich-profile", "profile-icon", "{\"system_prompt\":\"default prompt\",\"mcpAccess\":{}}", "[\"mcp-rich\"]", "[\"search\"]", 2))
                        db.execSQL("INSERT INTO provider_groups VALUES (?, ?, ?, ?)", sqlArgs("group-rich", "Primary", 1, "group-icon"))
                        db.execSQL("INSERT INTO model_providers VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", sqlArgs("provider-rich", "Rich Provider", "OPENAI", SecretCipher.encrypt("https://api.example.test/v1"), SecretCipher.encrypt("{\"method\":\"BEARER_TOKEN\",\"key\":\"Authorization\",\"value\":\"provider-secret\"}"), SecretCipher.encrypt("{\"modelIds\":[\"catalog-model\"],\"headers\":{\"X-Test\":\"yes\"}}"), "provider-icon", 3, "group-rich"))
                        db.execSQL("INSERT INTO provider_models VALUES (?, ?)", sqlArgs("provider-rich", "[{\"id\":\"catalog-model\",\"display_name\":\"Catalog Model\",\"provider_id\":\"provider-rich\",\"input_capabilities\":{\"text\":true,\"image\":true},\"output_capabilities\":{\"text\":true},\"model_tools\":[\"search\"]}]"))
                        db.execSQL("INSERT INTO mcp_servers VALUES (?, ?, ?, ?, ?, ?, ?)", sqlArgs("mcp-rich", "Rich MCP", "STREAMABLE_HTTP", SecretCipher.encrypt("https://mcp.example.test"), SecretCipher.encrypt("{\"headers\":{\"X-Test\":\"yes\"},\"auth\":{\"method\":\"BEARER_TOKEN\",\"key\":\"Authorization\",\"value\":\"mcp-token\"},\"icon\":\"mcp-icon\"}"), "[{\"name\":\"search\",\"description\":\"Search tool\",\"schema\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]},\"serverId\":\"mcp-rich\",\"originalName\":\"search\"}]", 4))
                        db.execSQL("INSERT INTO speech_services (id, name, provider, modelId, voice, speed, pitch, apiKey, sortOrder, optionsJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", sqlArgs("speech-rich", "Rich Speech", "provider-rich", null, "voice-1", 1.1f, 0.9f, SecretCipher.encrypt("speech-secret"), 5, SecretCipher.encrypt("{\"icon\":\"speech-icon\",\"instructions\":\"speak clearly\",\"responseFormat\":\"wav\",\"languageCode\":\"en-US\",\"extraBody\":{\"temperature\":0.2}}")))
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase
        helper.close()
    }

    private fun sqlArgs(vararg values: Any?): Array<Any?> {
        val result = arrayOfNulls<Any>(values.size)
        values.forEachIndexed { index, value -> result[index] = value }
        return result
    }
}
