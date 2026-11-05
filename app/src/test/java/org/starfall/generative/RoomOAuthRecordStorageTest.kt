package org.starfall.generative

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.OAuthTokenRecordEntity
import org.starfall.generative.database.repositories.RoomOAuthRecordStorage
import org.starfall.generative.database.preferences.MigratedStringPreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomOAuthRecordStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun namespace() = "generative.unit-${UUID.randomUUID()}.oauth"

    @Test fun sharedPreferencesMigratesWithoutReencryptingOrResurrectingDeletedTokens() {
        val namespace = namespace()
        val previous = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        assertTrue(previous.edit().putString("account-1", "keystore:v1:encrypted-original").commit())
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = RoomOAuthRecordStorage(context, database)
            assertEquals("keystore:v1:encrypted-original", store.get(namespace, "account-1"))
            assertTrue(previous.all.isEmpty())
            assertTrue(MigratedStringPreferences.get(context, namespace).all.isEmpty())
            store.remove(namespace, "account-1")
            assertNull(RoomOAuthRecordStorage(context, database).get(namespace, "account-1"))
        } finally { database.close() }
    }

    @Test fun transitionalDataStoreMigratesToRoomAndRoomWinsIfAlreadyUpdated() {
        val namespace = namespace()
        val transition = MigratedStringPreferences.get(context, namespace)
        transition.putString("old", "keystore:v1:staged")
        transition.putString("room-newer", "keystore:v1:stale")
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            runBlocking(Dispatchers.IO) {
                database.oauthTokenDao().put(OAuthTokenRecordEntity(namespace, "room-newer", "keystore:v1:newer"))
            }
            val store = RoomOAuthRecordStorage(context, database)
            assertEquals(
                mapOf("old" to "keystore:v1:staged", "room-newer" to "keystore:v1:newer"),
                store.all(namespace)
            )
            assertTrue(transition.all.isEmpty())
            store.replace(namespace, mapOf("restored" to "keystore:v1:replacement"))
            assertEquals(mapOf("restored" to "keystore:v1:replacement"),
                RoomOAuthRecordStorage(context, database).all(namespace))
        } finally { database.close() }
    }
}
