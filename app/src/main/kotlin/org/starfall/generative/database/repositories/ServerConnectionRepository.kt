package org.starfall.generative.database.repositories

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ServerConnectionEntity
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.integrations.opencode.OpenCodeAuthentication
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import org.starfall.generative.integrations.generativeserver.PairedIdentity
import org.starfall.generative.integrations.generativeserver.validateGenerativeServerHttpConnection
import org.starfall.generative.shared.state.ImmediateState
import org.starfall.generative.shared.state.upsert

/** Profiles are device-local. Export/restore intentionally excludes pairing credentials. */
data class ServerConnectionProfile(val id: String, val name: String, val endpoint: String, val pin: String,
    val clientId: String, val serverId: String, val selected: Boolean,
    val transport: String = "http", val sshMachineId: String = "", val allowLocalProviders: Boolean = false,
    val credentialRevision: Long = 0) {
    val isPaired: Boolean get() = transport != "ssh" || serverId.isNotEmpty()
}

data class StoredServerConnection(val profile: ServerConnectionProfile, val identity: PairedIdentity)
internal class StoredOpenCodeConnection(val profile: ServerConnectionProfile, val credentials: OpenCodeCredentials)

class ServerConnectionRepository(db: AppDatabase) {
    private data class ConnectionRecord(val row: ServerConnectionEntity, val credentialRevision: Long = 0) {
        val id: String get() = row.id

        fun profile() = with(row) {
            ServerConnectionProfile(id, name, endpoint, pin, clientId, serverId, selected, transport,
                sshMachineId, allowLocalProviders, credentialRevision)
        }
    }

    private val dao = db.serverConnectionDao()
    private val state = ImmediateState(load = { dao.all().map { ConnectionRecord(it) } })
    private val mutations = Mutex()
    private var credentialRevision = 0L

    val profiles = state.flow.map { records -> records.map { it.profile() } }.distinctUntilChanged()
    suspend fun snapshot(): List<ServerConnectionProfile> = profiles.first()
    suspend fun profile(id: String): ServerConnectionProfile? = state.flow.first().find { it.id == id }?.profile()

    suspend fun save(id: String, name: String, endpoint: String, pin: String, identity: PairedIdentity,
        transport: String = "http", sshMachineId: String = "", allowLocalProviders: Boolean = false) {
        require(id.isNotBlank() && name.isNotBlank())
        // Only HTTP uses a separate SPKI pin; SSH trust is owned by Remote Machines.
        when (transport) {
            "http" -> { require(sshMachineId.isEmpty()); validateGenerativeServerHttpConnection(endpoint, pin) }
            "ssh" -> { require(sshMachineId.isNotBlank() && pin.isEmpty()) }
            else -> throw IllegalArgumentException("Unsupported server transport")
        }
        saveCredentials(ServerConnectionEntity(id, name.trim(), endpoint, pin, identity.clientId, identity.serverId,
            SecretCipher.encrypt(identity.secret), false, transport, sshMachineId, allowLocalProviders))
    }

    /** Save the SSH configuration before its first identity is available. */
    internal suspend fun saveUnpairedSsh(id: String, name: String, endpoint: String, machineId: String,
        allowLocalProviders: Boolean) {
        require(id.isNotBlank() && name.isNotBlank() && machineId.isNotBlank())
        val row = ServerConnectionEntity(id, name.trim(), endpoint, "", "", "", "", false, "ssh", machineId,
            allowLocalProviders)
        update({ records ->
            require(records.none { it.id == id && it.profile().isPaired })
            records.upsert(ConnectionRecord(row), ConnectionRecord::id)
        }) { dao.put(row) }
    }

    internal suspend fun completeSshSetup(expected: ServerConnectionProfile, endpoint: String, machineId: String,
        identity: PairedIdentity) {
        require(identity.serverId.isNotBlank())
        require(!expected.isPaired || identity.serverId == expected.serverId) {
            "Different Generative Server; create a new connection"
        }
        val encryptedSecret = SecretCipher.encrypt(identity.secret)
        update({ records ->
            val previous = requireNotNull(records.find { it.id == expected.id })
            require(previous.credentialRevision == expected.credentialRevision)
            val row = previous.row.copy(endpoint = endpoint, pin = "", clientId = identity.clientId,
                serverId = identity.serverId, encryptedSecret = encryptedSecret, transport = "ssh", sshMachineId = machineId)
            records.upsert(ConnectionRecord(row, ++credentialRevision), ConnectionRecord::id)
        }) { records -> dao.put(records.first { it.id == expected.id }.row) }
    }
    /** Store only the verified endpoint and encrypted credential, never a one-time pairing URL. */
    suspend fun saveOpenCode(id: String, name: String, endpoint: String, credentials: OpenCodeCredentials) {
        require(id.isNotBlank() && name.isNotBlank())
        val url = OpenCodeAuthentication(endpoint).base.toString()
        saveCredentials(ServerConnectionEntity(id, name.trim(), url, "", credentials.username, url,
            SecretCipher.encrypt(credentials.password), false, "opencode", ""))
    }

    private suspend fun saveCredentials(row: ServerConnectionEntity) {
        update({ records ->
            val selected = records.find { it.id == row.id }?.row?.selected ?: false
            records.upsert(ConnectionRecord(row.copy(selected = selected), ++credentialRevision), ConnectionRecord::id)
        }) { records -> dao.put(records.first { it.id == row.id }.row) }
    }

    internal suspend fun openCodeConnection(id: String): StoredOpenCodeConnection? {
        val record = state.flow.first().find { it.id == id && it.row.transport == "opencode" } ?: return null
        val row = record.row
        return StoredOpenCodeConnection(record.profile(),
            OpenCodeCredentials(row.clientId, SecretCipher.decryptStrict(row.encryptedSecret)))
    }

    suspend fun openCodeCredentials(id: String): OpenCodeCredentials =
        requireNotNull(openCodeConnection(id)) { "OpenCode connection not found" }.credentials

    suspend fun rename(id: String, name: String, allowLocalProviders: Boolean? = null) {
        require(name.isNotBlank())
        update({ records ->
            val previous = requireNotNull(records.find { it.id == id })
            val row = previous.row
            val updated = row.copy(name = name.trim(), allowLocalProviders =
                row.transport != "opencode" && (allowLocalProviders ?: row.allowLocalProviders))
            records.upsert(previous.copy(row = updated), ConnectionRecord::id)
        }) { records -> dao.put(records.first { it.id == id }.row) }
    }

    suspend fun select(id: String) {
        update({ records ->
            require(records.any { it.id == id && it.profile().isPaired })
            records.map { it.copy(row = it.row.copy(selected = it.id == id)) }
        }) { dao.select(id) }
    }

    suspend fun delete(id: String) {
        update({ records -> records.filterNot { it.id == id } }) { dao.delete(id) }
    }

    suspend fun connection(id: String): StoredServerConnection {
        val record = requireNotNull(state.flow.first().find { it.id == id }) { "Connection not found" }
        require(record.profile().isPaired) { "Connection has not been paired" }
        val row = record.row
        return StoredServerConnection(record.profile(),
            PairedIdentity(row.clientId, SecretCipher.decryptStrict(row.encryptedSecret), row.serverId))
    }

    private suspend fun update(
        transform: (List<ConnectionRecord>) -> List<ConnectionRecord>,
        persist: suspend (List<ConnectionRecord>) -> Unit,
    ) = mutations.withLock {
        val current = state.flow.first()
        currentCoroutineContext().ensureActive()
        val updated = transform(current)
        // Keep the writer ordered even if its caller leaves after accepting a RAM edit.
        withContext(NonCancellable) {
            state.mutate({ updated }) { persist(updated) }
        }
        currentCoroutineContext().ensureActive()
    }
}
