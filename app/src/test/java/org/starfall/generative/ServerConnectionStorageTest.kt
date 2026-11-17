package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ServerConnectionEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ServerConnectionStorageTest {
    @Test fun selectionDoesNotAlterOtherIdentityOrCiphertext() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.serverConnectionDao()
            val first = ServerConnectionEntity("one", "First", "https://one.example", "pin-one", "client-one", "server-one", "keystore:v1:cipher-one", true)
            val second = ServerConnectionEntity("two", "Second", "https://two.example", "pin-two", "client-two", "server-two", "keystore:v1:cipher-two", false)
            dao.put(first); dao.put(second); dao.select("two")
            assertEquals(first.copy(selected = false), dao.get("one"))
            assertEquals(second.copy(selected = true), dao.get("two"))
            // Re-pair replaces the stored identity and ciphertext in place, preserving the selection.
            val rotated = second.copy(clientId = "client-rotated", serverId = "server-rotated",
                pin = "pin-rotated", encryptedSecret = "keystore:v1:cipher-rotated")
            dao.put(rotated.copy(selected = true))
            assertEquals(rotated.copy(selected = true), dao.get("two"))
            assertFalse(dao.all().any { it.encryptedSecret == "keystore:v1:cipher-two" })
            dao.delete("one"); assertEquals(listOf(rotated.copy(selected = true)), dao.all())
        } finally { db.close() }
    }
}
