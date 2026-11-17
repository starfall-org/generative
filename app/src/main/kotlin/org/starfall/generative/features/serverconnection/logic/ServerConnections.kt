package org.starfall.generative.features.serverconnection.logic

import java.util.UUID
import org.starfall.generative.database.repositories.ServerConnectionProfile
import org.starfall.generative.database.repositories.ServerConnectionRepository
import org.starfall.generative.database.repositories.RemoteConversationCache
import org.starfall.generative.integrations.serverside.ServerSideHttpTransport
import org.starfall.generative.integrations.serverside.ServerSideInvitation
import org.starfall.generative.integrations.serverside.ServerSideSessions

class ServerConnections(val repository: ServerConnectionRepository, private val cache: RemoteConversationCache? = null) {
    val profiles = repository.profiles
    suspend fun pair(name: String, url: String) {
        require(name.isNotBlank())
        val invitation = ServerSideInvitation.parse(url)
        val identity = ServerSideHttpTransport(invitation).pairAsync()
        repository.save(UUID.randomUUID().toString(), name, invitation.endpoint.toString(), invitation.pin, identity)
    }
    /** Pair a new identity for the same device-local profile; never retain the old credential. */
    suspend fun rePair(profile: ServerConnectionProfile, name: String, url: String) {
        require(name.isNotBlank())
        val invitation = ServerSideInvitation.parse(url)
        val identity = ServerSideHttpTransport(invitation).pairAsync()
        require(identity.serverId == profile.serverId) { "Invitation belongs to another server; add a separate connection" }
        repository.save(profile.id, name, invitation.endpoint.toString(), invitation.pin, identity)
        try { cache?.removeProfile(profile.id) } catch (_: Exception) { /* A cache miss cannot undo pairing. */ }
    }
    suspend fun rename(profile: ServerConnectionProfile, name: String) = repository.rename(profile.id, name)
    suspend fun select(id: String) = repository.select(id)
    /** Invalidate the server identity first, then remove its local profile on success. */
    suspend fun revoke(id: String) {
        repository.client(id).revoke()
        delete(id)
    }
    suspend fun delete(id: String) {
        repository.delete(id)
        try { cache?.removeProfile(id) } catch (_: Exception) { /* A cache miss cannot undo deletion. */ }
    }
    suspend fun test(id: String) {
        ServerSideSessions(repository.client(id)).verifyCapabilities()
    }
}
