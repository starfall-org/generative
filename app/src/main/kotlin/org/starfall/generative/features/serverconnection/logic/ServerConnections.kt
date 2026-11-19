package org.starfall.generative.features.serverconnection.logic

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.repositories.McpRepository
import org.starfall.generative.integrations.opencode.openCodeMcpImportConfig
import org.starfall.generative.shared.model.isContentApiPreset
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.McpAuthMethod
import org.starfall.generative.database.repositories.ServerConnectionProfile
import org.starfall.generative.database.repositories.ServerConnectionRepository
import org.starfall.generative.integrations.serverside.RemoteConversationCache
import org.starfall.generative.integrations.serverside.ServerSideClient
import org.starfall.generative.integrations.serverside.ServerSideHttpTransport
import org.starfall.generative.integrations.serverside.ServerSideInvitation
import org.starfall.generative.integrations.serverside.ServerSideSessions
import org.starfall.generative.integrations.serverside.ServerSideSshTransport
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.integrations.opencode.OpenCodeAuthentication
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import org.starfall.generative.integrations.opencode.OpenCodePairingLink
import org.starfall.generative.integrations.opencode.OpenCodeApi
import org.starfall.generative.integrations.opencode.OpenCodeProviderConfigFile
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.shared.model.RemoteMachineType

class ServerConnections(val repository: ServerConnectionRepository, private val clientFactory: suspend (String) -> ServerSideClient,
    private val cache: RemoteConversationCache? = null,
    private val remote: RemoteMachineService? = null,
    private val localModels: ModelRepository? = null,
    private val localMcp: McpRepository? = null) {
    val sshMachines = remote?.state
    val profiles = repository.profiles
    private val providerConfigWrites = Mutex()

    private suspend fun openCode(id: String): OpenCodeApi {
        val profile = requireNotNull(repository.profile(id))
        require(profile.transport == "opencode")
        return OpenCodeApi(profile.endpoint, repository.openCodeCredentials(id))
    }

    suspend fun providerConfigFile(id: String, directory: String?) = openCode(id).providerConfigFile(directory)

    suspend fun addConfiguredProvider(id: String, file: OpenCodeProviderConfigFile, providerId: String,
        config: JsonObject, directory: String?): Boolean =
        providerConfigWrites.withLock { openCode(id).addConfiguredProvider(file, providerId, config, directory) }

    suspend fun integration(id: String, integrationId: String, directory: String?) = openCode(id).integration(integrationId, directory)
    suspend fun connectIntegrationKey(id: String, integrationId: String, key: String, answer: JsonObject?, directory: String?) =
        openCode(id).connectIntegrationKey(integrationId, key, answer, directory)
    suspend fun beginIntegrationOAuth(id: String, integrationId: String, methodId: String, answer: JsonObject?, directory: String?) =
        openCode(id).beginIntegrationOAuth(integrationId, methodId, answer, directory)
    suspend fun integrationOAuthStatus(id: String, integrationId: String, attemptId: String, directory: String?) =
        openCode(id).integrationOAuthStatus(integrationId, attemptId, directory)
    suspend fun completeIntegrationOAuth(id: String, integrationId: String, attemptId: String, code: String?, directory: String?) =
        openCode(id).completeIntegrationOAuth(integrationId, attemptId, code, directory)
    suspend fun cancelIntegrationOAuth(id: String, integrationId: String, attemptId: String, directory: String?) =
        openCode(id).cancelIntegrationOAuth(integrationId, attemptId, directory)

    /** Read from the requested connection, independently of the active chat connection. */
    suspend fun providers(id: String, directory: String? = null): List<ServerInventoryItem> {
        val profile = requireNotNull(repository.profile(id))
        if (profile.transport == "opencode") {
            val api = OpenCodeApi(profile.endpoint, repository.openCodeCredentials(id))
            return api.providers(directory).map { provider ->
                ServerInventoryItem(provider.getValue("id").jsonPrimitive.content,
                    provider.getValue("name").jsonPrimitive.content,
                    provider.getValue("package").jsonPrimitive.content,
                    provider["integrationID"]?.jsonPrimitive?.content)
            }
        }
        val sessions = ServerSideSessions(clientFactory(id))
        return try {
            sessions.catalog().providers.map { ServerInventoryItem(it.id, it.name, it.type) }
        } finally { sessions.close() }
    }

    /** The picker reads public metadata only. Resolve a single credential after selection. */
    internal suspend fun localImportItems(providers: Boolean): List<LocalImportItem> =
        if (providers) localModels?.allProviders?.first().orEmpty().map {
            LocalImportItem(it.id, it.name, it.type.displayName, it.icon)
        } else localMcp?.allServers?.first().orEmpty().map {
            LocalImportItem(it.id, it.name, it.protocol.name, it.icon)
        }

    /** Check server support before reading any local credentials. */
    suspend fun importSupported(id: String, providers: Boolean): Boolean {
        val profile = requireNotNull(repository.profile(id))
        if (profile.transport == "opencode") return !providers && profile.endpoint.startsWith("https://", true)
        val sessions = ServerSideSessions(clientFactory(id))
        return try { sessions.supportsCatalogImport(providers) } finally { sessions.close() }
    }

    suspend fun importLocal(id: String, localId: String, providers: Boolean, directory: String?) {
        val profile = requireNotNull(repository.profile(id))
        if (profile.transport == "opencode") {
            require(!providers && profile.endpoint.startsWith("https://", true))
            val source = requireNotNull(localMcp)
            val metadata = requireNotNull(source.allServers.first().firstOrNull { it.id == localId })
            require(!metadata.isContentApiPreset()) // Build-owned endpoint is not exportable.
            val api = openCode(id)
            // PUT replaces existing runtime names. Do not implicitly overwrite.
            require(api.mcpServers(directory).none { it["name"]?.jsonPrimitive?.content == metadata.name })
            api.addMcp(metadata.name, openCodeMcpImportConfig(source.resolveServer(metadata)), directory)
            return
        }
        val sessions = ServerSideSessions(clientFactory(id))
        try {
            require(sessions.supportsCatalogImport(providers))
            if (providers) {
                val source = requireNotNull(localModels)
                val metadata = requireNotNull(source.allProviders.first().firstOrNull { it.id == localId })
                val provider = source.resolveProvider(metadata)
                require(provider.auth.method != AuthMethod.OAUTH && provider.config.oauthAccounts.isEmpty())
                // Discovery-only providers have no fixed list; obtain the current models
                // instead of importing an empty selection or modifying the local record.
                val copy = if (provider.config.modelIds == null) provider.copy(config = provider.config.copy(
                    modelIds = (source.fetchProviderModels(provider) + provider.config.modelConfigs.keys).distinct()))
                    else provider
                sessions.importProvider(copy)
            } else {
                val source = requireNotNull(localMcp)
                val metadata = requireNotNull(source.allServers.first().firstOrNull { it.id == localId })
                require(!metadata.isContentApiPreset())
                val server = source.resolveServer(metadata)
                require(server.auth.method != McpAuthMethod.OAUTH2)
                sessions.importMcp(server)
            }
        } finally { sessions.close() }
    }

    /** A null result means the server protocol has no MCP inventory operation. */
    suspend fun mcpServers(id: String, directory: String? = null): List<ServerInventoryItem>? {
        val profile = requireNotNull(repository.profile(id))
        if (profile.transport != "opencode") {
            val sessions = ServerSideSessions(clientFactory(id))
            return try {
                sessions.mcpCatalog()?.map { server ->
                    ServerInventoryItem(server.getValue("id").jsonPrimitive.content,
                        server.getValue("name").jsonPrimitive.content, "unknown")
                }
            } finally { sessions.close() }
        }
        val api = OpenCodeApi(profile.endpoint, repository.openCodeCredentials(id))
        return api.mcpServers(directory).map { server ->
            val name = server.getValue("name").jsonPrimitive.content
            ServerInventoryItem(name, server["title"]?.jsonPrimitive?.content ?: name,
                server.getValue("status").jsonObject.getValue("status").jsonPrimitive.content)
        }
    }

    /** Null denotes a protocol that does not use OpenCode project locations. */
    suspend fun projects(id: String): List<ServerProject>? {
        val profile = requireNotNull(repository.profile(id))
        if (profile.transport != "opencode") return null
        return OpenCodeApi(profile.endpoint, repository.openCodeCredentials(id)).projects()
            .map { ServerProject(it.name, it.directory) }.distinctBy { it.directory }
    }

    suspend fun setMcpConnected(id: String, name: String, connected: Boolean, directory: String?) {
        val profile = requireNotNull(repository.profile(id))
        require(profile.transport == "opencode")
        OpenCodeApi(profile.endpoint, repository.openCodeCredentials(id)).setMcpConnected(name, connected, directory)
    }

    suspend fun addMcp(id: String, name: String, config: JsonObject, directory: String?) {
        val profile = requireNotNull(repository.profile(id))
        require(profile.transport == "opencode")
        OpenCodeApi(profile.endpoint, repository.openCodeCredentials(id)).addMcp(name, config, directory)
    }

    suspend fun removeMcp(id: String, name: String, directory: String?) {
        val profile = requireNotNull(repository.profile(id))
        require(profile.transport == "opencode")
        OpenCodeApi(profile.endpoint, repository.openCodeCredentials(id)).removeMcp(name, directory)
    }
    suspend fun pair(name: String, url: String, transport: String = "http", sshMachineId: String = "", allowLocalProviders: Boolean = false) {
        require(name.isNotBlank())
        val id = UUID.randomUUID().toString()
        if (transport == "ssh") {
            val machine = requireNotNull(remote).store.also { it.awaitInitialized() }.state.value.machines
                .firstOrNull { it.id == sshMachineId && it.type == RemoteMachineType.SSH }
                ?: error("Select a saved SSH machine")
            val identity = ServerSideSshTransport.pair(remote, machine.id)
            repository.save(id, name, "ssh://${machine.username}@${machine.host}:${machine.port}",
                "", identity.identity, "ssh", machine.id, allowLocalProviders)
        } else {
            require(transport == "http")
            val invitation = ServerSideInvitation.parse(url)
            val identity = ServerSideHttpTransport(invitation).pairAsync()
            repository.save(id, name, invitation.endpoint.toString(), invitation.pin, identity, allowLocalProviders = allowLocalProviders)
        }
    }
    /** A rotated credential may access only the same server, never another owner's chats. */
    suspend fun rePair(profile: ServerConnectionProfile, name: String, url: String,
        transport: String = "http", sshMachineId: String = "", allowLocalProviders: Boolean = profile.allowLocalProviders) {
        require(name.isNotBlank())
        if (transport == "ssh") {
            val machine = requireNotNull(remote).store.also { it.awaitInitialized() }.state.value.machines
                .firstOrNull { it.id == sshMachineId && it.type == RemoteMachineType.SSH }
                ?: error("Selected SSH machine is no longer available")
            val paired = ServerSideSshTransport.pair(remote, machine.id)
            require(paired.identity.serverId == profile.serverId) { "Different ServerSide server; create a new connection" }
            repository.save(profile.id, name, "ssh://${machine.username}@${machine.host}:${machine.port}",
                "", paired.identity, "ssh", machine.id, allowLocalProviders)
        } else {
            require(transport == "http")
            val invitation = ServerSideInvitation.parse(url)
            val identity = ServerSideHttpTransport(invitation).pairAsync()
            require(identity.serverId == profile.serverId) { "Invitation belongs to another server; add a separate connection" }
            repository.save(profile.id, name, invitation.endpoint.toString(), invitation.pin, identity, allowLocalProviders = allowLocalProviders)
        }
        try { cache?.removeProfile(profile.id) } catch (_: Exception) { /* A cache miss cannot undo pairing. */ }
    }
    /** OpenCode uses its own HTTP API; no ServerSide command is invoked. */
    suspend fun saveOpenCode(profile: ServerConnectionProfile?, name: String, endpoint: String,
        username: String, password: String, pairingUrl: String) {
        require(name.isNotBlank())
        val link = pairingUrl.takeIf { it.isNotBlank() }?.let(OpenCodePairingLink::parse)
        val server = if (link != null) link.endpoint.toString() else endpoint
        val api = OpenCodeAuthentication(server)
        if (profile != null) {
            require(profile.transport == "opencode" && profile.endpoint == api.base.toString()) {
                "Changing servers requires a new connection"
            }
        }
        val credentials = if (link != null) api.pair(link)
            else OpenCodeCredentials(username.ifBlank { "opencode" }, password)
        api.verify(credentials)
        repository.saveOpenCode(profile?.id ?: UUID.randomUUID().toString(), name, api.base.toString(), credentials)
    }
    suspend fun rename(profile: ServerConnectionProfile, name: String, allowLocalProviders: Boolean = profile.allowLocalProviders) =
        repository.rename(profile.id, name, allowLocalProviders)
    suspend fun select(id: String) = repository.select(id)
    /** Invalidate the server identity first, then remove its local profile on success. */
    suspend fun revoke(id: String) {
        val client = clientFactory(id)
        try { client.revoke() } finally { client.close() }
        delete(id)
    }
    suspend fun delete(id: String) {
        repository.delete(id)
        try { cache?.removeProfile(id) } catch (_: Exception) { /* A cache miss cannot undo deletion. */ }
    }
    suspend fun test(id: String) {
        val profile = requireNotNull(repository.profile(id))
        if (profile.transport == "opencode") {
            OpenCodeAuthentication(profile.endpoint).verify(repository.openCodeCredentials(id))
            return
        }
        val sessions = ServerSideSessions(clientFactory(id))
        try { sessions.verifyCapabilities() }
        finally { sessions.close() }
    }
}

data class ServerInventoryItem(val id: String, val name: String, val detail: String, val integrationId: String? = null)
data class ServerProject(val name: String, val directory: String)

internal data class LocalImportItem(val id: String, val name: String, val detail: String, val icon: String?)
