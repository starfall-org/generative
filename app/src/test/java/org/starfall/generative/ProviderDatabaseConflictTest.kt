package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.AppDatabase
import org.starfall.generative.database.ModelRepository
import org.starfall.generative.database.RoomOAuthRecordStorage
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.integrations.modelapis.account.AccountTokenState
import org.starfall.generative.integrations.modelapis.account.AccountTokenStore
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderPlacement
import org.starfall.generative.shared.model.provider.ProviderRootOrderItem

/** Database-only fixtures: no live provider requests or interaction with user app data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ProviderDatabaseConflictTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: LibraryTestApplication get() = context.applicationContext as LibraryTestApplication
    private lateinit var db: AppDatabase
    private lateinit var ai: AiService

    @Before fun setup() {
        installTestAndroidKeyStore()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        app.testOAuthStorage = RoomOAuthRecordStorage(context, db)
        ai = AiService(context)
    }

    @After fun tearDown() {
        app.testOAuthStorage = null
        db.close()
    }

    private fun repo() = ModelRepository(db, ai)
    private fun devin(id: String) = ModelProvider(id, "Legacy Devin", ModelApiType.DEVIN,
        baseUrl = ModelApiType.DEVIN.defaultBaseUrl,
        auth = Authorization(AuthMethod.OAUTH, "devin@example.test", "account:devin:v1"))
    private fun corruptModelJson(id: String) {
        val document = runBlocking {
            db.structuredJsonDao().getDocument("provider_model", "$id/second", "model_json")
        }!!
        db.openHelper.writableDatabase.execSQL(
            "UPDATE structured_json_nodes SET nodeKind = 'bad-legacy-json' WHERE documentId = ? AND nodeId = 0",
            arrayOf(document.id))
    }
    private fun foreignKeysAreHealthy(database: AppDatabase = db) {
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Foreign key violation", cursor.moveToFirst())
        }
    }

    @Test fun deletingLegacyDevinAccountDoesNotDeadlockItsOwnRoomOAuthTokenStore() = runBlocking {
        val repository = repo()
        val provider = devin("legacy-devin-770")
        val store = AccountTokenStore(context, "devin")
        store.save(provider.id, AccountTokenState("test-access", "test-refresh"))
        repository.saveProvider(provider)
        withTimeout(8_000) { repository.deleteProvider(provider.id) }
        assertNull(db.modelProviderDao().getProviderById(provider.id))
        assertNull(store.load(provider.id))
        withTimeout(8_000) { repository.saveProvider(Phase4StorageFixtures.provider("healthy-after-delete")) }
        assertNotNull(repo().getProviderById("healthy-after-delete"))
        foreignKeysAreHealthy()
    }

    @Test fun corruptJsonRowCannotPoisonEntireRamCatalogOrBlockDeletion() = runBlocking {
        val writer = repo()
        writer.saveProvider(Phase4StorageFixtures.provider("broken"))
        writer.saveProvider(Phase4StorageFixtures.provider("healthy"))
        corruptModelJson("broken")
        val reader = repo()
        val loaded = withTimeout(8_000) { reader.allProviders.first() }
        assertEquals(setOf("broken", "healthy"), loaded.map { it.id }.toSet())
        assertEquals(CredentialLoadState.ERROR, loaded.single { it.id == "broken" }.credentialState)
        assertEquals("healthy", reader.getProviderById("healthy")?.id)
        withTimeout(8_000) { reader.deleteProvider("broken") }
        assertNull(db.modelProviderDao().getProviderById("broken"))
        assertNull(db.structuredJsonDao().getDocument("provider_model", "broken/second", "model_json"))
        assertEquals(listOf("healthy"), withTimeout(8_000) { repo().allProviders.first() }.map { it.id })
        withTimeout(8_000) { reader.saveProvider(Phase4StorageFixtures.provider("third")) }
        foreignKeysAreHealthy()
    }

    @Test fun repairResetsOnlyBrokenDevinCredentialsAndMalformedJsonWhilePreservingModels() = runBlocking {
        val repository = repo()
        val legacy = Phase4StorageFixtures.provider("broken-legacy").copy(
            type = ModelApiType.DEVIN, baseUrl = ModelApiType.DEVIN.defaultBaseUrl,
            auth = devin("broken-legacy").auth)
        val healthy = Phase4StorageFixtures.provider("healthy")
        repository.saveProvider(legacy)
        repository.saveProvider(healthy)
        corruptModelJson(legacy.id)
        val authId = db.modelProviderDao().getProviderById(legacy.id)!!.authId!!
        db.openHelper.writableDatabase.execSQL(
            "UPDATE provider_auth SET encryptedPayload = 'damaged-encrypted-value' WHERE id = ?", arrayOf(authId))
        val recovered = withTimeout(8_000) { repo().repairCorruptProviders() }
        assertEquals(1, recovered.repaired)
        assertTrue(recovered.failedIds.isEmpty())
        val refreshed = repo().getProviderById(legacy.id)!!
        assertEquals(ModelApiType.DEVIN, refreshed.type)
        assertEquals(AuthMethod.OAUTH, refreshed.auth.method)
        assertNull(db.modelProviderDao().getProviderById(legacy.id)?.authId)
        assertEquals(legacy.config.modelIds, refreshed.config.modelIds)
        assertEquals(legacy.config.modelConfigs.keys, refreshed.config.modelConfigs.keys)
        assertTrue(refreshed.config.modelConfigs.getValue("second").modelJson.isEmpty())
        assertEquals(healthy, repo().getProviderById("healthy"))
        foreignKeysAreHealthy()
    }

    @Test fun bulkModelSelectionIsVisibleFromRamWhileRoomWriterIsBlocked() = runBlocking {
        val repository = repo()
        repository.saveProvider(Phase4StorageFixtures.provider("bulk"))
        repository.allProviders.first()
        val beforeAuth = db.modelProviderDao().getProviderById("bulk")!!.authId!!
        val beforeEncrypted = db.modelProviderDao().getAuth(beforeAuth)!!.encryptedPayload
        val models = (0 until 90).associate { "bulk-$it" to ModelConfiguration(displayName = "Bulk $it") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hold = async(Dispatchers.IO) {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        try {
            entered.await()
            val writing = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                repository.saveProviderModels("bulk", models)
            }
            val optimistic = withTimeout(1500) {
                repository.allProviders.first { providers ->
                    providers.firstOrNull { it.id == "bulk" }?.config?.modelConfigs?.size == 90
                }
            }
            assertEquals(90, optimistic.single().config.modelConfigs.size)
            assertFalse("The actual Room write is still blocked", writing.isCompleted)
            release.complete(Unit)
            withTimeout(15_000) { writing.await(); hold.await() }
            assertEquals(models.keys, repo().getProviderById("bulk")!!.config.modelConfigs.keys)
            assertEquals(beforeAuth, db.modelProviderDao().getProviderById("bulk")!!.authId)
            assertEquals(beforeEncrypted, db.modelProviderDao().getAuth(beforeAuth)!!.encryptedPayload)
            foreignKeysAreHealthy()
        } finally { release.complete(Unit); hold.cancel() }
    }

    @Test fun queuedCheckboxChangesAndImmediateReorderKeepTheirOrderWithoutRoom() = runBlocking {
        val repository = repo()
        repository.saveProvider(Phase4StorageFixtures.provider("checkbox-queue"))
        repository.allProviders.first()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hold = async(Dispatchers.IO) {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        try {
            entered.await()
            // Simulate the editor's off-Main FIFO consumer. No database write can finish yet.
            val writes = withTimeout(2_000) { withContext(Dispatchers.Default) {
                val first = repository.queueSaveProviderModels("checkbox-queue", linkedMapOf(
                    "one" to ModelConfiguration()))
                val second = repository.queueSaveProviderModels("checkbox-queue", linkedMapOf(
                    "one" to ModelConfiguration(), "two" to ModelConfiguration()))
                val reordered = repository.queueReorderProviderModels("checkbox-queue", listOf("two", "one"))
                Triple(first, second, reordered)
            } }
            val visible = withTimeout(2_000) { repository.allProviders.first { providers ->
                providers.firstOrNull { it.id == "checkbox-queue" }?.config?.modelIds == listOf("two", "one")
            } }
            assertEquals(listOf("two", "one"), visible.single().config.modelIds)
            assertEquals(setOf("one", "two"), visible.single().config.modelConfigs.keys)
            assertFalse("The first write must still be awaiting Room", writes.first.isCompleted)
            assertFalse("The second write must still be awaiting Room", writes.second.isCompleted)
            release.complete(Unit)
            withTimeout(15_000) {
                writes.first.await()
                writes.second.await()
                writes.third.await()
                hold.await()
            }
            val stored = repository.getProviderById("checkbox-queue")!!.config
            assertEquals(listOf("two", "one"), stored.modelIds)
            assertEquals(setOf("one", "two"), stored.modelConfigs.keys)
            foreignKeysAreHealthy()
        } finally { release.complete(Unit); hold.cancel() }
    }

    @Test fun modelReorderIsVisibleBeforeRoomUnlocksAndEventuallyPersists() = runBlocking {
        val repository = repo()
        repository.saveProvider(Phase4StorageFixtures.provider("queued-models"))
        val configs = linkedMapOf("one" to ModelConfiguration(), "two" to ModelConfiguration(),
            "three" to ModelConfiguration())
        repository.saveProviderModels("queued-models", configs)
        repository.allProviders.first()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val locked = async(Dispatchers.IO) { db.withTransaction { entered.complete(Unit); release.await() } }
        try {
            entered.await()
            repository.queueReorderProviderModels("queued-models", listOf("three", "one", "two"))
            val visible = withTimeout(1_500) { repository.allProviders.first {
                it.firstOrNull { provider -> provider.id == "queued-models" }
                    ?.config?.modelIds == listOf("three", "one", "two")
            } }
            assertEquals(listOf("three", "one", "two"), visible.single().config.modelIds)
            assertFalse(locked.isCompleted)
        } finally { release.complete(Unit); withTimeout(5_000) { locked.await() } }
        withTimeout(10_000) {
            while (repository.getProviderById("queued-models")!!.config.modelIds != listOf("three", "one", "two")) delay(20)
        }
    }

    @Test fun providerPlacementReordersInRamEvenWhileRoomIsBlocked() = runBlocking {
        val repository = repo()
        listOf("first", "second", "third").forEach { id -> repository.saveProvider(Phase4StorageFixtures.provider(id)) }
        repository.allProviders.first()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val locked = async(Dispatchers.IO) { db.withTransaction { entered.complete(Unit); release.await() } }
        try {
            entered.await()
            repository.queueProviderPlacement(ProviderPlacement("third", null,
                listOf("third", "first", "second").map { ProviderRootOrderItem(it, false) }, emptyMap()))
            val visible = withTimeout(1_500) { repository.allProviders.first { providers ->
                providers.filter { it.groupId == null }.map { it.id } == listOf("third", "first", "second")
            } }
            assertEquals(listOf("third", "first", "second"), visible.map { it.id })
            assertFalse(locked.isCompleted)
        } finally { release.complete(Unit); withTimeout(5_000) { locked.await() } }
        withTimeout(10_000) {
            while (repository.loadProvidersForExport().filter { it.groupId == null }.map { it.id } !=
                listOf("third", "first", "second")) delay(20)
        }
    }

    @Test fun repeatedBulkChangesAndDeleteCannotResurrectProvider() = runBlocking {
        val repository = repo()
        repository.saveProvider(Phase4StorageFixtures.provider("rapid"))
        repository.saveProvider(Phase4StorageFixtures.provider("unrelated"))
        val writes = (1..6).map { index ->
            async(Dispatchers.Default) {
                runCatching { repository.saveProviderModels("rapid", (1..index).associate {
                    "model-$it" to ModelConfiguration(displayName = "Run $index") }) }
            }
        }
        val deletion = async(Dispatchers.Default) { repository.deleteProvider("rapid") }
        withTimeout(15_000) { writes.awaitAll(); deletion.await() }
        assertNull(db.modelProviderDao().getProviderById("rapid"))
        assertEquals(listOf("unrelated"), withTimeout(8_000) { repo().allProviders.first() }.map { it.id })
        withTimeout(8_000) { repository.saveProvider(Phase4StorageFixtures.provider("still-writable")) }
        foreignKeysAreHealthy()
    }

    @Test fun deletingProviderCannotDeleteJsonOfSiblingWithIdPrefix() = runBlocking {
        val repository = repo()
        repository.saveProvider(Phase4StorageFixtures.provider("prefix"))
        repository.saveProvider(Phase4StorageFixtures.provider("prefix/other"))
        val docBefore = db.structuredJsonDao().getDocument("provider_model", "prefix/other/second", "model_json")
        assertNotNull(docBefore)
        withTimeout(8_000) { repository.deleteProvider("prefix") }
        assertEquals(docBefore, db.structuredJsonDao().getDocument("provider_model", "prefix/other/second", "model_json"))
        assertNotNull(repo().getProviderById("prefix/other"))
        foreignKeysAreHealthy()
    }

    @Test fun replacingDevinProviderTypeCleansOldCredentialsOnlyAfterCommit() = runBlocking {
        val repository = repo()
        val old = devin("switch-provider")
        val store = AccountTokenStore(context, "devin")
        store.save(old.id, AccountTokenState("test-old", "test-refresh"))
        repository.saveProvider(old)
        val replacement = Phase4StorageFixtures.provider(old.id).copy(groupId = null)
        withTimeout(8_000) { repository.saveProvider(replacement) }
        withTimeout(8_000) {
            while (store.load(old.id) != null) delay(25)
        }
        assertEquals(ModelApiType.OPENAI, repo().getProviderById(old.id)?.type)
        foreignKeysAreHealthy()
    }

    @Test fun oneFailedRepairDoesNotBlockOtherRecordsAndCanBeRetried() = runBlocking {
        val repository = repo()
        for (id in listOf("a-unrepairable", "b-repairable")) {
            repository.saveProvider(devin(id))
            val authId = db.modelProviderDao().getProviderById(id)!!.authId!!
            db.openHelper.writableDatabase.execSQL(
                "UPDATE provider_auth SET encryptedPayload='corrupted' WHERE id=?", arrayOf(authId))
        }
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_one_repair BEFORE UPDATE OF authId ON model_providers
               WHEN OLD.id = 'a-unrepairable'
               BEGIN SELECT RAISE(ABORT, 'synthetic row conflict'); END""")
        try {
            val first = withTimeout(8_000) { repository.repairCorruptProviders() }
            assertEquals(1, first.repaired)
            assertEquals(listOf("a-unrepairable"), first.failedIds)
            assertNotNull(repo().getProviderById("b-repairable"))
            assertNotNull(db.modelProviderDao().getProviderById("a-unrepairable"))
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_one_repair") }
        val retry = withTimeout(8_000) { repository.repairCorruptProviders() }
        assertEquals(1, retry.repaired)
        assertTrue(retry.failedIds.isEmpty())
        assertNull(db.modelProviderDao().getProviderById("a-unrepairable")?.authId)
        foreignKeysAreHealthy()
    }

    @Test fun deletionPersistsAcrossFreshRoomDatabaseReopen() = runBlocking {
        val name = "provider-restart-test-${UUID.randomUUID()}"
        val saved = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val writer = ModelRepository(saved, ai)
            writer.saveProvider(Phase4StorageFixtures.provider("legacy-persisted"))
            val id = saved.structuredJsonDao().getDocument("provider_model", "legacy-persisted/second", "model_json")!!.id
            saved.openHelper.writableDatabase.execSQL(
                "UPDATE structured_json_nodes SET nodeKind='invalid' WHERE documentId=? AND nodeId=0", arrayOf(id))
            withTimeout(8_000) { ModelRepository(saved, ai).deleteProvider("legacy-persisted") }
        } finally { saved.close() }
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            assertNull(reopened.modelProviderDao().getProviderById("legacy-persisted"))
            assertTrue(withTimeout(8_000) { ModelRepository(reopened, ai).allProviders.first() }.isEmpty())
            reopened.modelProviderDao().insertOrUpdate(
                Phase4StorageFixtures.provider("do-not-use").let { fixture ->
                    // The repository write itself is tested above; the reopened database remains writable.
                    org.starfall.generative.database.entities.ModelProviderEntity(
                        fixture.id, fixture.name, fixture.type.name, fixture.baseUrl, null, 0, null,
                        null, true, false, null, 4096, false, "EXPLICIT")
                })
            assertNotNull(reopened.modelProviderDao().getProviderById("do-not-use"))
            foreignKeysAreHealthy(reopened)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
