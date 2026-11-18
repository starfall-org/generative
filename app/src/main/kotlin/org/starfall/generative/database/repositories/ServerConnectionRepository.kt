package org.starfall.generative.database.repositories

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ServerConnectionEntity
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.integrations.opencode.OpenCodeAuthentication
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import org.starfall.generative.integrations.serverside.PairedIdentity
import org.starfall.generative.integrations.serverside.validateServerSideHttpConnection
import org.starfall.generative.shared.state.ImmediateState
import org.starfall.generative.shared.state.upsert

/** Profiles are device-local. Export/restore intentionally excludes pairing credentials. */
data class ServerConnectionProfile(val id: String, val name: String, val endpoint: String, val pin: String,
    val clientId: String, val serverId: String, val selected: Boolean,
    val transport: String = "http", val sshMachineId: String = "", val allowLocalProviders: Boolean = false)

data class StoredServerConnection(val profile: ServerConnectionProfile, val identity: PairedIdentity)

class ServerConnectionRepository(private val db: AppDatabase) {
    private val dao = db.serverConnectionDao()
    private val state = ImmediateState(load = { dao.all().map { it.profile() } })
    val profiles = state.flow
    suspend fun snapshot(): List<ServerConnectionProfile> = profiles.first()
    suspend fun profile(id: String): ServerConnectionProfile? = dao.get(id)?.profile()

    suspend fun save(id: String, name: String, endpoint: String, pin: String, identity: PairedIdentity,
        transport: String = "http", sshMachineId: String = "", allowLocalProviders: Boolean = false) {
        require(id.isNotBlank() && name.isNotBlank())
        // Only HTTP uses a separate SPKI pin; SSH trust is owned by Remote Machines.
        when (transport) {
            "http" -> { require(sshMachineId.isEmpty()); validateServerSideHttpConnection(endpoint, pin) }
            "ssh" -> { require(sshMachineId.isNotBlank() && pin.isEmpty()) }
            else -> throw IllegalArgumentException("Unsupported server transport")
        }
        val prior = dao.get(id)
        val row = ServerConnectionEntity(id, name.trim(), endpoint, pin, identity.clientId, identity.serverId,
            SecretCipher.encrypt(identity.secret), prior?.selected ?: false, transport, sshMachineId, allowLocalProviders)
        state.mutate({ it.upsert(row.profile()) { value -> value.id } }) { dao.put(row) }
    }
    /** Store only the verified endpoint and encrypted credential, never a one-time pairing URL. */
    suspend fun saveOpenCode(id: String, name: String, endpoint: String, credentials: OpenCodeCredentials) {
        require(id.isNotBlank() && name.isNotBlank())
        val url = OpenCodeAuthentication(endpoint).base.toString()
        val previous = dao.get(id)
        val row = ServerConnectionEntity(id, name.trim(), url, "", credentials.username, url,
            SecretCipher.encrypt(credentials.password), previous?.selected ?: false, "opencode", "")
        state.mutate({ it.upsert(row.profile()) { value -> value.id } }) { dao.put(row) }
    }

    suspend fun openCodeCredentials(id: String): OpenCodeCredentials {
        val row = requireNotNull(dao.get(id)) { "Connection not found" }
        require(row.transport == "opencode")
        return OpenCodeCredentials(row.clientId, SecretCipher.decryptStrict(row.encryptedSecret))
    }
    suspend fun rename(id: String, name: String, allowLocalProviders: Boolean? = null) {
        require(name.isNotBlank())
        val previous = requireNotNull(dao.get(id))
        val row = previous.copy(name = name.trim(), allowLocalProviders =
            previous.transport != "opencode" && (allowLocalProviders ?: previous.allowLocalProviders))
        state.mutate({ it.upsert(row.profile()) { value -> value.id } }) { dao.put(row) }
    }
    suspend fun select(id: String) {
        require(dao.get(id) != null)
        state.mutate({ list -> list.map { it.copy(selected = it.id == id) } }) {
            db.withTransaction { check(dao.get(id) != null); dao.select(id) }
        }
    }
    suspend fun delete(id: String) {
        state.mutate({ it.filterNot { p -> p.id == id } }) { dao.delete(id) }
    }
    suspend fun connection(id: String): StoredServerConnection {
        val row = requireNotNull(dao.get(id)) { "Connection not found" }
        return StoredServerConnection(row.profile(),
            PairedIdentity(row.clientId, SecretCipher.decryptStrict(row.encryptedSecret), row.serverId))
    }
    private fun ServerConnectionEntity.profile() = ServerConnectionProfile(id, name, endpoint, pin, clientId, serverId, selected, transport, sshMachineId, allowLocalProviders)
}
