package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ServerConnectionEntity
import org.starfall.generative.database.repositories.ServerConnectionRepository
import org.starfall.generative.integrations.generativeserver.PairedIdentity
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import org.starfall.generative.integrations.security.SecretCipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ServerConnectionStorageTest {
    @Test fun sshConfigurationSurvivesReloadBeforePairing() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val endpoint = "ssh://user@host:22"
            val repository = ServerConnectionRepository(db)
            repository.saveUnpairedSsh("ssh", "Runtime", endpoint, "machine", allowLocalProviders = true)

            val reloaded = ServerConnectionRepository(db)
            val pending = requireNotNull(reloaded.profile("ssh"))
            assertFalse(pending.isPaired)
            assertEquals("machine", pending.sshMachineId)
            assertEquals("", db.serverConnectionDao().get("ssh")?.encryptedSecret)
            assertTrue(runCatching { reloaded.connection("ssh") }.isFailure)

            val identity = PairedIdentity("client", "secret", "server")
            reloaded.completeSshSetup(pending, endpoint, "machine", identity)
            val paired = reloaded.connection("ssh")
            assertTrue(paired.profile.isPaired)
            assertTrue(paired.profile.allowLocalProviders)
            assertEquals("Runtime", paired.profile.name)
            assertEquals(identity.clientId, paired.identity.clientId)
            assertEquals(identity.serverId, paired.identity.serverId)
            assertEquals(identity.secret, paired.identity.secret)
            assertTrue(SecretCipher.isEncrypted(requireNotNull(db.serverConnectionDao().get("ssh")).encryptedSecret))
        } finally { db.close() }
    }

    @Test fun selectionDoesNotAlterOtherIdentityOrCiphertext() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.serverConnectionDao()
            val first = ServerConnectionEntity("one", "First", "https://one.example", "pin-one", "client-one", "server-one", "keystore:v1:cipher-one", true)
            val second = ServerConnectionEntity("two", "Second", "https://two.example", "pin-two", "client-two", "server-two", "keystore:v1:cipher-two", false)
            dao.put(first); dao.put(second); dao.select("two")
            val ssh = ServerConnectionEntity("ssh", "SSH", "ssh://user@host:22", "", "client-ssh",
                "server-ssh", "keystore:v1:cipher-ssh", false, "ssh", "machine-id")
            dao.put(ssh)
            assertEquals("ssh", dao.get("ssh")?.transport)
            assertEquals("machine-id", dao.get("ssh")?.sshMachineId)
            assertEquals(first.copy(selected = false), dao.get("one"))
            assertEquals(second.copy(selected = true), dao.get("two"))
            // Re-pair replaces the stored identity and ciphertext in place, preserving the selection.
            val rotated = second.copy(clientId = "client-rotated", serverId = "server-rotated",
                pin = "pin-rotated", encryptedSecret = "keystore:v1:cipher-rotated")
            dao.put(rotated.copy(selected = true))
            assertEquals(rotated.copy(selected = true), dao.get("two"))
            assertFalse(dao.all().any { it.encryptedSecret == "keystore:v1:cipher-two" })
            dao.delete("one"); assertEquals(setOf(rotated.copy(selected = true), ssh), dao.all().toSet())
        } finally { db.close() }
    }
    @Test fun providerSourceIsSavedPerConnectionWithoutChangingIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.serverConnectionDao()
            val first = ServerConnectionEntity("one", "First", "https://one.example", "pin", "client", "server", "cipher", true)
            val second = first.copy(id = "two", selected = false)
            dao.put(first); dao.put(second)
            val repository = ServerConnectionRepository(db)
            repository.rename("one", "First", allowLocalProviders = true)
            assertEquals(first.copy(allowLocalProviders = true), dao.get("one"))
            assertEquals(second, dao.get("two"))
            repository.rename("one", "Renamed")
            assertTrue(requireNotNull(repository.profile("one")).allowLocalProviders)
            repository.rename("one", "Renamed", allowLocalProviders = false)
            assertFalse(requireNotNull(repository.profile("one")).allowLocalProviders)
        } finally { db.close() }
    }

    @Test fun openCodeCredentialsAreEncryptedAndEndpointIsPublic() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val repository = ServerConnectionRepository(db)
            repository.saveOpenCode("id", "OpenCode", "https://example.com/", OpenCodeCredentials("opencode", "private-token"))
            val row = requireNotNull(db.serverConnectionDao().get("id"))
            assertEquals("opencode", row.transport)
            assertEquals("https://example.com/", row.endpoint)
            assertTrue(SecretCipher.isEncrypted(row.encryptedSecret))
            assertFalse(row.encryptedSecret.contains("private-token"))
            assertEquals("private-token", repository.openCodeCredentials("id").password)
            assertFalse(repository.snapshot().single().toString().contains("private-token"))

            val revision = repository.snapshot().single().credentialRevision
            repository.select("id")
            repository.rename("id", "Renamed")
            assertEquals(revision, repository.snapshot().single().credentialRevision)
            repository.saveOpenCode("id", "Renamed", row.endpoint, OpenCodeCredentials("opencode", "replacement"))
            val updated = requireNotNull(repository.openCodeConnection("id"))
            assertTrue(updated.profile.selected)
            assertTrue(updated.profile.credentialRevision > revision)
            assertEquals("replacement", updated.credentials.password)
        } finally { db.close() }
    }

    @Test fun pointLookupsAndConcurrentEditsUseTheSameMemoryCatalog() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.serverConnectionDao()
            val first = ServerConnectionEntity("one", "First", "https://one.example", "pin", "client", "server", "cipher", true)
            dao.put(first)
            dao.put(first.copy(id = "two", name = "Second", selected = false))
            val repository = ServerConnectionRepository(db)
            assertEquals("First", repository.snapshot().first().name)

            dao.put(first.copy(name = "External write"))
            assertEquals("First", repository.profile("one")?.name)

            listOf(
                async(Dispatchers.Default) { repository.select("two") },
                async(Dispatchers.Default) { repository.rename("one", "Renamed") },
            ).awaitAll()
            assertEquals(listOf("two"), repository.snapshot().filter { it.selected }.map { it.id })
            assertEquals(listOf("two"), dao.all().filter { it.selected }.map { it.id })
            assertEquals("Renamed", repository.profile("one")?.name)
            assertEquals("Renamed", dao.get("one")?.name)
        } finally { db.close() }
    }

}
