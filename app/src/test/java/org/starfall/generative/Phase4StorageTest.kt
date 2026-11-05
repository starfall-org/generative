package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.appdata.logic.buildJsonExport
import org.starfall.generative.features.appdata.logic.parseFullAppDataExport
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.*
import org.starfall.generative.shared.model.language.Capabilities
import org.starfall.generative.shared.model.language.LanguageModel
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.database.repositories.*
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.integrations.security.SecretCipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class Phase4StorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var ai: AiService

    @Before fun setUp() {
        installTestAndroidKeyStore()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        ai = AiService(context)
    }

    @After fun tearDown() { db.close() }

    private fun models(database: AppDatabase = db) = ModelRepository(database, ai)
    private fun mcp(database: AppDatabase = db) = McpRepository(database, McpService())

    @Test fun providerMetadataDoesNotDecryptCorruptUnrelatedAuthButSelectedReadsFailExplicitly() = runBlocking {
        val writer = models()
        val healthy = Phase4StorageFixtures.provider("healthy")
        val broken = Phase4StorageFixtures.provider("broken")
        writer.saveProvider(healthy)
        writer.saveProvider(broken)
        val authId = db.modelProviderDao().getProviderById(broken.id)!!.authId!!
        db.openHelper.writableDatabase.execSQL(
            "UPDATE provider_auth SET encryptedPayload = ? WHERE id = ?", arrayOf("keystore:v1:AAAA", authId))
        val corruptRow = db.modelProviderDao().getAuth(authId)!!

        // A fresh reader must actually load from Room rather than reuse the writer's optimistic state.
        val reader = models()
        val metadata = withTimeout(5000) { reader.allProviders.first() }
        assertEquals(setOf("healthy", "broken"), metadata.map { it.id }.toSet())
        metadata.forEach {
            assertEquals(CredentialLoadState.NOT_REQUESTED, it.credentialState)
            assertNull(it.auth.value)
            assertTrue(it.config.headers.isEmpty())
            assertTrue(it.config.apiKeys.isEmpty())
            assertTrue(it.config.oauthAccounts.isEmpty())
            assertTrue(it.config.httpProxy.isEmpty())
            assertTrue(it.config.socksProxy.isEmpty())
            assertEquals("https://provider.example.test/v1", it.baseUrl)
            assertEquals("https://provider.example.test/models", it.config.customListModelsUrl)
            assertEquals("0.158.0", it.config.codexClientVersion)
            assertEquals("2.25.0", it.config.antigravityVersion)
            assertEquals("123456789", it.config.antigravityChangelist)
            assertEquals(mapOf("account-1" to "project-fixture", "account-2" to "project-backup"),
                it.config.antigravityProjectIds)
            assertEquals(broken.config.modelConfigs, it.config.modelConfigs)
        }
        assertEquals(healthy, reader.getProviderById(healthy.id))
        assertEquals(healthy, reader.resolveProvider(metadata.single { it.id == healthy.id }))
        assertUnreadable("Provider broken", runCatching { reader.getProviderById(broken.id) }.exceptionOrNull())
        assertUnreadable("Provider broken", runCatching {
            reader.resolveProvider(metadata.single { it.id == broken.id })
        }.exceptionOrNull())
        assertEquals(corruptRow, db.modelProviderDao().getAuth(authId))
        assertEquals(authId, db.modelProviderDao().getProviderById(broken.id)!!.authId)
    }

    @Test fun mcpMetadataDoesNotDecryptCorruptUnrelatedAuthButSelectedReadsFailExplicitly() = runBlocking {
        val writer = mcp()
        val healthy = Phase4StorageFixtures.server("healthy")
        val broken = Phase4StorageFixtures.server("broken")
        writer.saveServer(healthy)
        writer.saveServer(broken)
        val authId = db.mcpServerDao().getServerById(broken.id)!!.authId!!
        // Plaintext is also corrupt normalized storage: selected reads must never fall back to it.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE mcp_auth SET encryptedPayload = ? WHERE id = ?", arrayOf("not-an-encrypted-envelope", authId))
        val corruptRow = db.mcpServerDao().getAuth(authId)!!

        val reader = mcp()
        val metadata = withTimeout(5000) { reader.allServers.first() }
        assertEquals(setOf("healthy", "broken"), metadata.map { it.id }.toSet())
        metadata.forEach {
            assertEquals(CredentialLoadState.NOT_REQUESTED, it.credentialState)
            assertNull(it.headers)
            assertNull(it.auth.value)
            assertEquals("https://mcp.example.test/tools", it.url)
            assertEquals(Phase4StorageFixtures.nestedSchema, it.cachedTools!!.first().schema)
        }
        assertEquals(healthy, reader.getById(healthy.id))
        assertEquals(healthy, reader.resolveServer(metadata.single { it.id == healthy.id }))
        assertUnreadable("MCP server broken", runCatching { reader.getById(broken.id) }.exceptionOrNull())
        assertUnreadable("MCP server broken", runCatching {
            reader.resolveServer(metadata.single { it.id == broken.id })
        }.exceptionOrNull())
        assertEquals(corruptRow, db.mcpServerDao().getAuth(authId))
        assertEquals(authId, db.mcpServerDao().getServerById(broken.id)!!.authId)
    }

    @Test fun noAuthUsesNullReferencesAndPreservesAuthenticationPolicy() = runBlocking {
        val providers = models()
        for (method in listOf(AuthMethod.NONE, AuthMethod.PLATFORM_DEFAULT, AuthMethod.OAUTH)) {
            val provider = ModelProvider(method.name, method.name, ModelApiType.OPENAI,
                baseUrl = "https://public.example.test", auth = Authorization(method), sortOrder = 0)
            providers.saveProvider(provider)
            assertNull(db.modelProviderDao().getProviderById(provider.id)!!.authId)
            assertEquals(provider.copy(credentialState = CredentialLoadState.NOT_CONFIGURED), models().getProviderById(provider.id))
        }
        val servers = mcp()
        for (method in listOf(McpAuthMethod.NONE, McpAuthMethod.OAUTH2)) {
            val server = McpInfo(method.name, method.name, url = "https://public.example.test", auth = McpAuthorization(method))
            servers.saveServer(server)
            assertNull(db.mcpServerDao().getServerById(server.id)!!.authId)
            assertEquals(server.copy(credentialState = CredentialLoadState.NOT_CONFIGURED), mcp().getById(server.id))
        }
        val speech = SpeechService("system", "System voice")
        SpeechRepository(db).saveService(speech)
        assertNull(db.speechServiceDao().getServiceById(speech.id)!!.authId)
        assertEquals(speech, SpeechRepository(db).getById(speech.id))
        assertEquals(0L, count("provider_auth"))
        assertEquals(0L, count("mcp_auth"))
        assertEquals(0L, count("speech_auth"))
    }

    @Test fun mediaApiTypeAndProfileSurviveProviderMetadataRoundTrip() = runBlocking {
        val source = Phase4StorageFixtures.provider().let { provider ->
            val media = ModelConfiguration(
                modelType = ModelType.IMAGE_GENERATION,
                mediaApiType = MediaGenerationMode.CUSTOM,
                mediaRequestProfileId = "shared-image-profile"
            )
            provider.copy(config = provider.config.copy(
                modelIds = provider.config.modelIds.orEmpty() + "image",
                modelConfigs = provider.config.modelConfigs + ("image" to media)
            ))
        }
        models().saveProvider(source)

        val restored = models().getProviderById(source.id)!!
        assertEquals(MediaGenerationMode.CUSTOM, restored.config.modelConfigs.getValue("image").mediaApiType)
        assertEquals("shared-image-profile", restored.config.modelConfigs.getValue("image").mediaRequestProfileId)
    }

    @Test fun publicConfigurationEditsPreserveUnreadableCredentialsWithoutDecryptingThem() = runBlocking {
        val provider = Phase4StorageFixtures.provider()
        val server = Phase4StorageFixtures.server()
        models().saveProvider(provider)
        mcp().saveServer(server)
        val providerAuthId = db.modelProviderDao().getProviderById(provider.id)!!.authId!!
        val serverAuthId = db.mcpServerDao().getServerById(server.id)!!.authId!!
        db.openHelper.writableDatabase.execSQL("UPDATE provider_auth SET encryptedPayload = ? WHERE id = ?",
            arrayOf("keystore:v1:AAAA", providerAuthId))
        db.openHelper.writableDatabase.execSQL("UPDATE mcp_auth SET encryptedPayload = ? WHERE id = ?",
            arrayOf("keystore:v1:AAAA", serverAuthId))
        val providerAuth = db.modelProviderDao().getAuth(providerAuthId)!!
        val serverAuth = db.mcpServerDao().getAuth(serverAuthId)!!
        val providers = models()
        val servers = mcp()
        val providerMetadata = withTimeout(5000) { providers.allProviders.first() }.single()
        val serverMetadata = withTimeout(5000) { servers.allServers.first() }.single()
        providers.saveProvider(providerMetadata.copy(name = "Public edit", config = providerMetadata.config.copy(maxTokens = 321)))
        servers.saveServer(serverMetadata.copy(name = "Public server edit"))
        assertEquals(providerAuth, db.modelProviderDao().getAuth(providerAuthId))
        assertEquals(serverAuth, db.mcpServerDao().getAuth(serverAuthId))
        assertEquals(321, db.modelProviderDao().getProviderById(provider.id)!!.maxTokens)
        assertEquals("Public server edit", db.mcpServerDao().getServerById(server.id)!!.name)
        assertEquals(provider.config.modelConfigs, models().allProviders.first().single().config.modelConfigs)
        assertUnreadable("Provider ${provider.id}", runCatching { providers.getProviderById(provider.id) }.exceptionOrNull())
        assertUnreadable("MCP server ${server.id}", runCatching { servers.getById(server.id) }.exceptionOrNull())
    }

    @Test fun deletingAuthSetsForeignKeysNullAndRetainsPublicConfiguration() = runBlocking {
        val provider = Phase4StorageFixtures.provider()
        val server = Phase4StorageFixtures.server()
        val speech = Phase4StorageFixtures.speech()
        models().saveProvider(provider)
        mcp().saveServer(server)
        SpeechRepository(db).saveService(speech)
        val providerAuth = db.modelProviderDao().getProviderById(provider.id)!!.authId!!
        val mcpAuth = db.mcpServerDao().getServerById(server.id)!!.authId!!
        val speechAuth = db.speechServiceDao().getServiceById(speech.id)!!.authId!!

        db.modelProviderDao().deleteAuth(providerAuth)
        db.mcpServerDao().deleteAuth(mcpAuth)
        db.speechServiceDao().deleteAuth(speechAuth)
        assertNull(db.modelProviderDao().getProviderById(provider.id)!!.authId)
        assertNull(db.mcpServerDao().getServerById(server.id)!!.authId)
        assertNull(db.speechServiceDao().getServiceById(speech.id)!!.authId)
        assertNull(db.modelProviderDao().getAuth(providerAuth))
        assertNull(db.mcpServerDao().getAuth(mcpAuth))
        assertNull(db.speechServiceDao().getAuth(speechAuth))
        val reloadedProvider = models().getProviderById(provider.id)!!
        assertEquals(CredentialLoadState.NOT_CONFIGURED, reloadedProvider.credentialState)
        assertEquals(provider.config.modelConfigs, reloadedProvider.config.modelConfigs)
        assertEquals(provider.config.modelIds, reloadedProvider.config.modelIds)
        assertNull(reloadedProvider.auth.value)
        val reloadedServer = mcp().getById(server.id)!!
        assertEquals(CredentialLoadState.NOT_CONFIGURED, reloadedServer.credentialState)
        assertEquals(server.cachedTools, reloadedServer.cachedTools)
        assertNull(reloadedServer.auth.value)
        assertEquals(speech.copy(apiKey = ""), SpeechRepository(db).getById(speech.id))
        assertFalse(db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
    }

    @Test fun providerConfigurationsAndCatalogNormalizeAndReplaceWithoutLosingOrderOrCapabilities() = runBlocking {
        val repository = models()
        val provider = Phase4StorageFixtures.provider()
        repository.saveProvider(provider)
        assertEquals(provider, models().getProviderById(provider.id))
        val dao = db.modelProviderDao()
        assertEquals(listOf("second", "custom", "first"), dao.getModelSelection(provider.id).map { it.modelId })
        assertEquals(listOf("second", "first"), dao.getModelConfigurations(provider.id).map { it.modelId })
        assertEquals(listOf(0, 1), dao.getModelConfigurations(provider.id).map { it.ordinal })
        assertEquals(listOf("high", "low"), dao.getModelValues(provider.id)
            .filter { it.modelId == "second" && it.listKind == "reasoning_efforts" }.map { it.value })
        val catalog = ProviderModels(provider.id, listOf(
            LanguageModel(
                "second", "Catalog second", "catalog-icon", "remote-provider",
                Capabilities(false, true, true, false, true, "input-extra"),
                Capabilities(true, false, false, true, false, "output-extra"),
                listOf("code", "search")),
            LanguageModel("second", "Duplicate ID, distinct entry", providerId = provider.id)
        ))
        assertNull(repository.getModelsForProvider(provider.id))
        repository.saveModelsForProvider(catalog)
        assertEquals(catalog, models().getModelsForProvider(provider.id))
        assertEquals(listOf(0, 1), dao.getCatalogModels(provider.id).map { it.ordinal })
        assertEquals(listOf("code", "search"), dao.getCatalogTools(provider.id).map { it.toolName })

        val updated = provider.copy(name = "Renamed", config = provider.config.copy(
            modelIds = listOf("first"), modelConfigs = mapOf("first" to provider.config.modelConfigs.getValue("first"))))
        repository.saveProvider(updated)
        assertEquals(updated, models().getProviderById(provider.id))
        assertEquals(catalog, models().getModelsForProvider(provider.id))
        assertEquals(listOf("first"), dao.getModelConfigurations(provider.id).map { it.modelId })
        assertTrue(dao.getModelValues(provider.id).all { it.modelId == "first" })
        assertNull(db.structuredJsonDao().getDocument("provider_model", "${provider.id}/second", "model_json"))
        val replacement = catalog.copy(models = listOf(catalog.models.last().copy(modelTools = listOf("browse"))))
        repository.saveModelsForProvider(replacement)
        assertEquals(replacement, models().getModelsForProvider(provider.id))
        assertEquals(listOf("browse"), dao.getCatalogTools(provider.id).map { it.toolName })
        repository.saveModelsForProvider(catalog.copy(models = emptyList()))
        assertEquals(catalog.copy(models = emptyList()), models().getModelsForProvider(provider.id))
        assertTrue(dao.getCatalogModels(provider.id).isEmpty())
        assertTrue(dao.getCatalogTools(provider.id).isEmpty())
    }

    @Test fun discoveryAndExplicitEmptyModelSelectionsRemainDistinct() = runBlocking {
        val repository = models()
        val provider = Phase4StorageFixtures.provider()
        for (selection in listOf(null, emptyList(), listOf("second", "first"))) {
            val expected = provider.copy(config = provider.config.copy(modelIds = selection))
            repository.saveProvider(expected)
            assertEquals(expected, models().getProviderById(provider.id))
            assertEquals(if (selection == null) "DISCOVERY" else "EXPLICIT",
                db.modelProviderDao().getProviderById(provider.id)!!.modelSelectionMode)
        }
    }

    @Test fun profileActiveSelectionsAndMcpAccessRoundTripAndReplaceRemovedEntries() = runBlocking {
        val profile = Phase4StorageFixtures.profile()
        ProfileRepository(db).saveProfile(profile)
        assertEquals(profile, ProfileRepository(db).getById(profile.id))
        val dao = db.chatProfileDao()
        assertEquals(listOf("mcp-b", "mcp-a"), dao.getMcpAccess(profile.id).map { it.serverId })
        assertEquals(listOf(true, false), dao.getMcpAccess(profile.id).map { it.enabled })
        assertEquals(listOf("mcp-b", "mcp-a"), dao.getActiveMcp(profile.id).map { it.serverId })
        assertEquals(listOf("code", "search"), dao.getActiveModelTools(profile.id).map { it.toolName })
        assertEquals(mapOf("read" to false, "write" to true), dao.getMcpTools(profile.id)
            .filter { it.serverId == "mcp-a" }.associate { it.toolName to it.enabled })

        val updated = profile.copy(config = AiChatConfig("New prompt", mapOf("mcp-a" to McpAccess(true, mapOf("read" to true)))),
            activeMcp = listOf("mcp-a"), activeModelTools = emptyList())
        ProfileRepository(db).saveProfile(updated)
        assertEquals(updated, ProfileRepository(db).getById(profile.id))
        assertEquals(listOf("mcp-a"), dao.getMcpAccess(profile.id).map { it.serverId })
        assertEquals(listOf("read"), dao.getMcpTools(profile.id).map { it.toolName })
        assertTrue(dao.getActiveModelTools(profile.id).isEmpty())
    }

    @Test fun nestedMcpSchemasAndSpeechExtraBodyPreserveJsonTypesAndClearStaleNodes() = runBlocking {
        val server = Phase4StorageFixtures.server()
        val speech = Phase4StorageFixtures.speech()
        val servers = mcp()
        servers.saveServer(server)
        SpeechRepository(db).saveService(speech)
        assertEquals(server, mcp().getById(server.id))
        assertEquals(speech, SpeechRepository(db).getById(speech.id))
        for ((type, owner, field) in listOf(Triple("mcp_tool", "mcp/0", "schema"), Triple("speech_service", "speech", "extra_body"))) {
            val document = db.structuredJsonDao().getDocument(type, owner, field)!!
            val nodes = db.structuredJsonDao().getNodes(document.id)
            assertEquals(setOf("object", "array", "string", "number", "boolean", "null"), nodes.map { it.nodeKind }.toSet())
        }
        val auth = db.speechServiceDao().getAuth(db.speechServiceDao().getServiceById(speech.id)!!.authId!!)!!
        assertTrue(SecretCipher.isEncrypted(auth.encryptedApiKey))
        assertEquals(speech.apiKey, SecretCipher.decryptStrict(auth.encryptedApiKey))

        val emptySchema = JsonObject(emptyMap())
        val replacement = listOf(server.cachedTools!!.first().copy(schema = emptySchema))
        servers.saveCachedTools(server.id, replacement)
        SpeechRepository(db).saveService(speech.copy(extraBody = emptySchema))
        assertEquals(replacement, mcp().getById(server.id)!!.cachedTools)
        assertNull(db.structuredJsonDao().getDocument("mcp_tool", "mcp/0", "schema"))
        assertNull(db.structuredJsonDao().getDocument("mcp_tool", "mcp/1", "schema"))
        assertNull(db.structuredJsonDao().getDocument("speech_service", "speech", "extra_body"))
        assertEquals(speech.copy(extraBody = emptySchema), SpeechRepository(db).getById(speech.id))
        servers.saveServer(server.copy(cachedTools = null))
        assertNull(mcp().getById(server.id)!!.cachedTools)
        servers.saveCachedTools(server.id, emptyList())
        assertEquals(emptyList<ToolDefinition>(), mcp().getById(server.id)!!.cachedTools)
    }

    @Test fun externalDtoExportImportRoundTripsThroughFreshRepositories() = runBlocking {
        val group = ProviderGroup("group", "Fixture group", 1, "group-icon")
        val provider = Phase4StorageFixtures.provider(groupId = group.id)
        val server = Phase4StorageFixtures.server()
        val speech = Phase4StorageFixtures.speech()
        val sourceModels = models()
        sourceModels.saveGroup(group)
        sourceModels.saveProvider(provider)
        mcp().saveServer(server)
        SpeechRepository(db).saveService(speech)
        val exported = buildJsonExport(models().loadProvidersForExport(), mcp().loadServersForExport(),
            listOf(SpeechRepository(db).getById(speech.id)!!), emptyList(), sourceModels.allGroups.first())
        val root = Json.parseToJsonElement(exported).jsonObject
        assertEquals(1, root.getValue("formatVersion").jsonPrimitive.int)
        assertEquals("full_backup", root.getValue("type").jsonPrimitive.content)
        val providerDto = root.getValue("providers").jsonArray.single().jsonObject
        val serverDto = root.getValue("mcpServers").jsonArray.single().jsonObject
        assertFalse(providerDto.containsKey("credentialState"))
        assertFalse(serverDto.containsKey("credentialState"))
        assertFalse(providerDto.containsKey("authId"))
        assertFalse(serverDto.containsKey("authId"))
        assertEquals("fixture-provider-token", providerDto.getValue("auth").jsonObject.getValue("value").jsonPrimitive.content)
        val imported = parseFullAppDataExport(exported)
        assertEquals(listOf(provider), imported.providers)
        assertEquals(listOf(server), imported.mcpServers)
        assertEquals(listOf(speech), imported.speechServices)

        val destination = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            models(destination).importProviders(imported.providers!!, imported.providerGroups)
            imported.mcpServers!!.forEach { mcp(destination).saveServer(it) }
            imported.speechServices!!.forEach { SpeechRepository(destination).saveService(it) }
            assertEquals(listOf(provider), models(destination).loadProvidersForExport())
            assertEquals(listOf(group), models(destination).allGroups.first())
            assertEquals(listOf(server), mcp(destination).loadServersForExport())
            assertEquals(speech, SpeechRepository(destination).getById(speech.id))
            val reexported = buildJsonExport(models(destination).loadProvidersForExport(), mcp(destination).loadServersForExport(),
                listOf(SpeechRepository(destination).getById(speech.id)!!), emptyList(), models(destination).allGroups.first())
            assertEquals(imported, parseFullAppDataExport(reexported))
        } finally { destination.close() }
    }

    private fun count(table: String): Long = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }

    private fun assertUnreadable(owner: String, error: Throwable?) {
        assertTrue("Expected explicit credential failure for $owner, got $error", error is IllegalStateException)
        assertTrue(error!!.message.orEmpty().contains("$owner credentials are unreadable"))
        assertNotNull(error.cause)
    }
}
