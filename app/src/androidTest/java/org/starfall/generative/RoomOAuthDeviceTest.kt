package org.starfall.generative

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.integrations.modelapis.account.AccountTokenState
import org.starfall.generative.integrations.modelapis.account.AccountTokenStore
import org.starfall.generative.database.preferences.MigratedStringPreferences
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.repositories.RoomOAuthRecordStorage
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Exercises AndroidKeyStore encryption and migration into real Room on a device. */
@RunWith(AndroidJUnit4::class)
class RoomOAuthDeviceTest {
    @Test fun oldStringPreferencesAreImportedWithoutLosingValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileName = "generative.device-legacy-${UUID.randomUUID()}.oauth"
        val legacy = context.getSharedPreferences(fileName, 0)
        assertTrue(legacy.edit().putString("old", "preserve").commit())

        val room = RoomOAuthRecordStorage(context, AppDatabase.getInstance(context))
        assertEquals("preserve", room.get(fileName, "old"))
        assertFalse(legacy.contains("old"))
        assertTrue(MigratedStringPreferences.get(context, fileName).all.isEmpty())
        room.put(fileName, "new", "durable")
        assertEquals(mapOf("old" to "preserve", "new" to "durable"), room.all(fileName))
        room.remove(fileName, "old")
        assertNull(RoomOAuthRecordStorage(context, AppDatabase.getInstance(context)).get(fileName, "old"))
        room.replace(fileName, emptyMap())
    }

    @Test fun encryptedCredentialsSurviveStoreRecreationAndNeverWritePlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "migration_device_test"
        val legacyFile = "generative.$namespace.oauth"
        val key = "datastore-migration-test"
        val token = AccountTokenState(
            accessToken = "secret-access-data-store",
            refreshToken = "secret-refresh-data-store"
        )
        val store = AccountTokenStore(context, namespace)
        try {
            store.save(key, token)
            assertEquals(token, AccountTokenStore(context, namespace).load(key))

            val onDisk = runBlocking { AppDatabase.getInstance(context).oauthTokenDao().get(legacyFile, key) }
            assertNotNull(onDisk)
            assertTrue(onDisk!!.startsWith("keystore:v1:"))
            assertFalse(onDisk.contains(token.accessToken))
            assertFalse(onDisk.contains(token.refreshToken))
            assertFalse(MigratedStringPreferences.get(context, legacyFile).contains(key))
        } finally {
            store.delete(key)
        }
    }
}
