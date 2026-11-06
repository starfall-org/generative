package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.SpeechServiceEntity
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.iconMatchName
import org.starfall.generative.database.repositories.SpeechRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpeechServiceStorageTest {
    @Test fun dedicatedSpeechLogosMatchEngineRegardlessOfCustomServiceName() {
        val brands = mapOf(
            SpeechEngine.ELEVENLABS to "ElevenLabs",
            SpeechEngine.FISH_AUDIO to "Fish Audio",
            SpeechEngine.CARTESIA to "Cartesia",
            SpeechEngine.SPEECHIFY to "Speechify"
        )
        brands.forEach { (engine, name) ->
            assertEquals(name, SpeechService("a", "My custom narrator", engine = engine).engine.iconMatchName)
            assertEquals(name, SpeechService("b", "Completely different name", engine = engine).engine.iconMatchName)
        }
        assertNull(SpeechEngine.SYSTEM.iconMatchName)
        assertNull(SpeechEngine.PROVIDER.iconMatchName)
    }

    @Test fun speechOptionsSurviveRepositoryReloadAndReordering() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val service = SpeechService("s", "Voice", icon = "https://example.com/voice.png", provider = "p", modelId = "tts", voice = "Kore",
                instructions = "Speak calmly", responseFormat = "wav", languageCode = "vi-VN",
                parallelRequests = true, requestSpacingSeconds = 1.5f, maxConcurrentRequests = 3,
                extraBody = buildJsonObject { put("custom", "value") })
            SpeechRepository(db).saveService(service)
            assertEquals(service, SpeechRepository(db).getById("s"))
            SpeechRepository(db).reorderServices(listOf("s"))
            assertEquals(service.copy(sortOrder = 0), SpeechRepository(db).getById("s"))
        } finally { db.close() }
    }

    @Test fun standaloneEnginesKeepEncryptedKeysAndVoiceIdentity() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            org.starfall.generative.shared.model.SpeechEngine.entries.filter { it.standalone }.forEach { engine ->
                val service = SpeechService(engine.name, "Standalone", provider = "", engine = engine,
                    modelId = "engine-model", voice = "voice-id", voiceName = "Friendly name", apiKey = "private-key",
                    speed = 1.6f, maxWordsPerChunk = 27)
                SpeechRepository(db).saveService(service)
                assertEquals(service, SpeechRepository(db).getById(service.id))
                val entity = db.speechServiceDao().getServiceById(service.id)!!
                val auth = db.speechServiceDao().getAuth(entity.authId!!)!!
                assertNotEquals(service.apiKey, auth.encryptedApiKey)
                assertTrue(auth.encryptedApiKey.startsWith("keystore:v1:"))
            }
        } finally { db.close() }
    }

    @Test fun anExistingTwentyWordSettingRemainsUnchanged() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val existing = SpeechService("existing", "Voice", maxWordsPerChunk = 20)
            SpeechRepository(db).saveService(existing)
            assertEquals(20, SpeechRepository(db).getById(existing.id)?.maxWordsPerChunk)
            val defaults = SpeechService("new", "Voice")
            assertEquals(100, defaults.maxWordsPerChunk)
            assertFalse(defaults.parallelRequests)
            assertEquals(1f, defaults.requestSpacingSeconds, 0f)
            assertEquals(5, defaults.maxConcurrentRequests)
        } finally { db.close() }
    }

    @Test fun legacyRowsWithoutOptionsKeepDefaults() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            db.speechServiceDao().insertOrUpdate(SpeechServiceEntity(
                id = "s", name = "Old", provider = "system", modelId = null, voice = "Default",
                speed = 1f, pitch = 1f, sortOrder = 0, icon = null, instructions = "",
                responseFormat = "mp3", languageCode = "", authId = null
            ))
            val service = SpeechRepository(db).getById("s")!!
            assertEquals(100, service.maxWordsPerChunk)
            assertFalse(service.parallelRequests)
            assertEquals(1f, service.requestSpacingSeconds, 0f)
            assertEquals(5, service.maxConcurrentRequests)
            assertEquals("mp3", service.responseFormat)
            assertEquals("", service.instructions)
            assertTrue(service.extraBody.isEmpty())
        } finally { db.close() }
    }
}
