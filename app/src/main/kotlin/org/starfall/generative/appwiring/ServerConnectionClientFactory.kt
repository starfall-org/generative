package org.starfall.generative.appwiring

import org.starfall.generative.database.repositories.ServerConnectionRepository
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.integrations.generativeserver.GenerativeServerClient
import org.starfall.generative.integrations.generativeserver.GenerativeServerHttpTransport
import org.starfall.generative.integrations.generativeserver.GenerativeServerSshTransport

/** Combines stored credentials with the transport supplied by the application. */
class ServerConnectionClientFactory(
    private val repository: ServerConnectionRepository,
    private val remote: RemoteMachineService,
) {
    suspend fun create(id: String): GenerativeServerClient = create(id, disconnectSshWhenIdle = false)

    suspend fun createForRuntime(id: String): GenerativeServerClient = create(id, disconnectSshWhenIdle = true)

    private suspend fun create(id: String, disconnectSshWhenIdle: Boolean): GenerativeServerClient {
        val connection = repository.connection(id)
        val profile = connection.profile
        val wire = when (profile.transport) {
            "http" -> GenerativeServerHttpTransport(profile.endpoint, profile.pin)
            "ssh" -> GenerativeServerSshTransport(remote, profile.sshMachineId, connection.identity, disconnectSshWhenIdle)
            else -> error("Unsupported server transport")
        }
        return GenerativeServerClient(wire, connection.identity)
    }
}
