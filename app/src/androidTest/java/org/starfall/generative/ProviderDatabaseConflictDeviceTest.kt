package org.starfall.generative

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.database.AppDatabase
import org.starfall.generative.database.ModelRepository
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration

/** Uses in-memory Room only: cannot read or modify the device's real Generative database. */
@RunWith(AndroidJUnit4::class)
class ProviderDatabaseConflictDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun provider(id: String) = ModelProvider(id, "Synthetic $id", ModelApiType.OPENAI,
        baseUrl = "https://example.invalid/v1",
        config = ProviderConfiguration(modelIds = listOf("test-model"), modelConfigs = mapOf(
            "test-model" to ModelConfiguration(modelJson = buildJsonObject { put("name", "test") })
        )))

    @Test fun corruptProviderDoesNotLockFreshDbAndDeletingItPersists() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = ModelRepository(db, AiService(context))
            repository.saveProvider(provider("damaged-device"))
            repository.saveProvider(provider("healthy-device"))
            val document = db.structuredJsonDao().getDocument("provider_model", "damaged-device/test-model", "model_json")!!
            db.openHelper.writableDatabase.execSQL(
                "UPDATE structured_json_nodes SET nodeKind='corrupt' WHERE documentId=? AND nodeId=0", arrayOf(document.id))
            val fresh = ModelRepository(db, AiService(context))
            val list = withTimeout(8_000) { fresh.allProviders.first() }
            assertEquals(2, list.size)
            assertEquals(CredentialLoadState.ERROR, list.first { it.id == "damaged-device" }.credentialState)
            withTimeout(8_000) { fresh.deleteProvider("damaged-device") }
            assertNull(db.modelProviderDao().getProviderById("damaged-device"))
            assertEquals("healthy-device", ModelRepository(db, AiService(context)).allProviders.first().single().id)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close() }
    }

    @Test fun manyModelSelectionsPublishBeforeBlockedRoomAndKeepCredentialsUntouched() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = ModelRepository(db, AiService(context))
            repository.saveProvider(provider("bulk-device"))
            repository.allProviders.first()
            val modelConfigs = (1..125).associate { "model-$it" to ModelConfiguration(displayName = "Model $it") }
            val gate = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val transaction = async(Dispatchers.IO) { db.withTransaction { gate.complete(Unit); release.await() } }
            try {
                gate.await()
                val saving = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    repository.saveProviderModels("bulk-device", modelConfigs)
                }
                val fromMemory = withTimeout(2_000) {
                    repository.allProviders.first { it.single().config.modelConfigs.size == 125 }
                }
                assertEquals(125, fromMemory.single().config.modelConfigs.size)
                assertFalse(saving.isCompleted)
                release.complete(Unit)
                withTimeout(15_000) { saving.await(); transaction.await() }
                assertEquals(modelConfigs.keys, ModelRepository(db, AiService(context))
                    .getProviderById("bulk-device")!!.config.modelConfigs.keys)
                db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally { release.complete(Unit); transaction.cancel() }
        } finally { db.close() }
    }
}
