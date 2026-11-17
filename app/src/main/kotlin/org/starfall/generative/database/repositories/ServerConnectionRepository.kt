package org.starfall.generative.database.repositories

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ServerConnectionEntity
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.integrations.serverside.PairedIdentity
import org.starfall.generative.integrations.serverside.ServerSideClient
import org.starfall.generative.integrations.serverside.ServerSideHttpTransport
import org.starfall.generative.shared.state.ImmediateState
import org.starfall.generative.shared.state.upsert

/** Profiles are device-local. Export/restore intentionally excludes pairing credentials. */
data class ServerConnectionProfile(val id: String, val name: String, val endpoint: String, val pin: String,
    val clientId: String, val serverId: String, val selected: Boolean)

class ServerConnectionRepository(private val db: AppDatabase) {
    private val dao = db.serverConnectionDao()
    private val state = ImmediateState(load = { dao.all().map { it.profile() } })
    val profiles = state.flow
    suspend fun snapshot(): List<ServerConnectionProfile> = profiles.first()
    suspend fun cacheScope(id: String): RemoteCacheScope? = dao.get(id)?.let {
        RemoteCacheScope(it.id, it.clientId, it.serverId, it.pin)
    }

    suspend fun save(id: String, name: String, endpoint: String, pin: String, identity: PairedIdentity) {
        require(id.isNotBlank() && name.isNotBlank())
        // Constructor validates endpoint and configures pin before persisting trust details.
        ServerSideHttpTransport(endpoint, pin)
        val prior = dao.get(id)
        val row = ServerConnectionEntity(id, name.trim(), endpoint, pin, identity.clientId, identity.serverId,
            SecretCipher.encrypt(identity.secret), prior?.selected ?: false)
        state.mutate({ it.upsert(row.profile()) { value -> value.id } }) { dao.put(row) }
    }
    suspend fun rename(id: String, name: String) {
        require(name.isNotBlank())
        val row = requireNotNull(dao.get(id)).copy(name = name.trim())
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
    suspend fun client(id: String): ServerSideClient {
        val row = requireNotNull(dao.get(id)) { "Connection not found" }
        val identity = PairedIdentity(row.clientId, SecretCipher.decryptStrict(row.encryptedSecret), row.serverId)
        return ServerSideClient(ServerSideHttpTransport(row.endpoint, row.pin), identity)
    }
    private fun ServerConnectionEntity.profile() = ServerConnectionProfile(id, name, endpoint, pin, clientId, serverId, selected)
}
