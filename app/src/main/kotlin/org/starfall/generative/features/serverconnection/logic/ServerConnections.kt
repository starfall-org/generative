package org.starfall.generative.features.serverconnection.logic

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.repositories.McpRepository
import org.starfall.generative.integrations.opencode.openCodeMcpImportConfig
import org.starfall.generative.integrations.opencode.openCodeProviderType
import org.starfall.generative.shared.model.isContentApiPreset
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.McpAuthMethod
import org.starfall.generative.database.repositories.ServerConnectionProfile
import org.starfall.generative.database.repositories.ServerConnectionRepository
import org.starfall.generative.features.serverconnection.model.ServerConnectionDraft
import org.starfall.generative.integrations.generativeserver.RemoteConversationCache
import org.starfall.generative.integrations.generativeserver.GenerativeServerClient
import org.starfall.generative.integrations.generativeserver.GenerativeServerHttpTransport
import org.starfall.generative.integrations.generativeserver.GenerativeServerInvitation
import org.starfall.generative.integrations.generativeserver.GenerativeServerSessions
import org.starfall.generative.integrations.generativeserver.GenerativeServerSshTransport
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.integrations.opencode.OpenCodeAuthentication
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import org.starfall.generative.integrations.opencode.OpenCodePairingLink
import org.starfall.generative.integrations.opencode.OpenCodeApi
import org.starfall.generative.integrations.opencode.OpenCodeProviderConfigFile
import org.starfall.generative.integrations.opencode.OpenCodeCatalogEntry
import org.starfall.generative.integrations.opencode.OpenCodeConfigEntryNotWritable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.RemoteMachineType

class ServerConnections(val repository: ServerConnectionRepository, private val clientFactory: suspend (String) -> GenerativeServerClient,
    private val cache: RemoteConversationCache? = null,
    private val remote: RemoteMachineService? = null,
    private val localModels: ModelRepository? = null,
    private val localMcp: McpRepository? = null) {
    val sshMachines = remote?.state
    val profiles = repository.profiles
    private val providerConfigWrites = Mutex()

    internal suspend fun openCode(id: String): OpenCodeApi {
        val connection = requireNotNull(repository.openCodeConnection(id))
        return OpenCodeApi(connection.profile.endpoint, connection.credentials)
    }

    suspend fun providerConfigFile(id: String, directory: String?) = openCode(id).providerConfigFile(directory)

    suspend fun addConfiguredProvider(id: String, file: OpenCodeProviderConfigFile, providerId: String,
        config: JsonObject, directory: String?): Boolean =
        providerConfigWrites.withLock { openCode(id).addConfiguredProvider(file, providerId, config, directory) }

    internal suspend fun providerConfigEntry(id: String, provider: String): OpenCodeCatalogEntry =
        openCode(id).catalogConfigEntry(listOf("providers", provider), directory = null)

    internal suspend fun mcpConfigEntry(id: String, name: String, directory: String? = null): OpenCodeCatalogEntry =
        openCode(id).catalogConfigEntry(listOf("mcp", "servers", name), directory)

    internal suspend fun changeCatalogConfigEntry(
        id: String, entry: OpenCodeCatalogEntry, replacement: JsonObject?, newKey: String? = null, directory: String? = null
    ): Boolean = providerConfigWrites.withLock {
        openCode(id).changeCatalogConfigEntry(entry, replacement, directory, newKey)
    }

    /** Null means no writable record, false means deletion succeeded but reload failed. */
    internal suspend fun deleteCatalogProvider(
        id: String, providerId: String, openedEntry: OpenCodeCatalogEntry? = null
    ): Boolean? = providerConfigWrites.withLock {
        val api = openCode(id)
        val path = listOf("providers", providerId)
        val entry = openedEntry ?: try {
            api.catalogConfigEntry(path, directory = null)
        } catch (_: OpenCodeConfigEntryNotWritable) {
            return@withLock null
        }
        require(entry.path == path)
        api.changeCatalogConfigEntry(entry, replacement = null, directory = null)
    }

    suspend fun connectIntegrationKey(id: String, integrationId: String, key: String, answer: JsonObject?, directory: String?) =
        openCode(id).connectIntegrationKey(integrationId, key, answer, directory)

    /** Read from the requested connection, independently of the active chat connection. */
    suspend fun providers(id: String, directory: String? = null): List<ServerInventoryItem> {
        val profile = requireNotNull(repository.profile(id))
        if (profile.transport == "opencode") {
            val api = openCode(id)
            return api.providers(directory).map { provider ->
                ServerInventoryItem(provider.getValue("id").jsonPrimitive.content,
                    provider.getValue("name").jsonPrimitive.content,
                    provider.getValue("package").jsonPrimitive.content,
                    provider["integrationID"]?.jsonPrimitive?.content,
                    providerType = openCodeProviderType(provider.getValue("package").jsonPrimitive.content),
                    url = ((provider["settings"] as? JsonObject)?.get("baseURL") as? JsonPrimitive)?.contentOrNull)
            }
        }
        return withServerSession(id) {
            catalog().providers.map { ServerInventoryItem(it.id, it.name, it.type,
                providerType = ModelApiType.entries.firstOrNull { type -> type.name.equals(it.type, true) }
                    ?: ModelApiType.OPENAI) }
        }
    }

    internal suspend fun saveImportedProvider(id: String, provider: ModelProvider) {
        withServerSession(id) {
            require(supportsCatalogImport(true))
            importProvider(provider)
        }
    }

    internal suspend fun saveImportedMcp(id: String, server: McpInfo) {
        withServerSession(id) {
            require(supportsCatalogImport(false))
            importMcp(server)
        }
    }

    /** The picker reads public metadata only. Resolve a single credential after selection. */
    internal suspend fun localImportItems(providers: Boolean): List<LocalImportItem> =
        if (providers) localModels?.allProviders?.first().orEmpty().map {
            LocalImportItem(it.id, it.name, it.type.displayName, it.icon)
        } else localMcp?.allServers?.first().orEmpty().map {
            LocalImportItem(it.id, it.name, it.protocol.name, it.icon)
        }

    /** Check server support before reading any local credentials. */
    internal suspend fun localImportKinds(id: String): List<Boolean> {
        val profile = requireNotNull(repository.profile(id))
        if (profile.transport == "opencode") {
            return if (profile.endpoint.startsWith("https://", true)) listOf(false) else emptyList()
        }
        return withServerSession(id) { catalogImportKinds() }
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
        withServerSession(id) {
            require(supportsCatalogImport(providers))
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
                importProvider(copy)
            } else {
                val source = requireNotNull(localMcp)
                val metadata = requireNotNull(source.allServers.first().firstOrNull { it.id == localId })
                require(!metadata.isContentApiPreset())
                val server = source.resolveServer(metadata)
                require(server.auth.method != McpAuthMethod.OAUTH2)
                importMcp(server)
            }
        }
    }

    /** A null result means the server protocol has no MCP inventory operation. */
    suspend fun mcpServers(id: String, directory: String? = null): List<ServerInventoryItem>? {
        val profile = requireNotNull(repository.profile(id))
        if (profile.transport != "opencode") {
            return withServerSession(id) {
                mcpCatalog()?.map { server ->
                    ServerInventoryItem(server.getValue("id").jsonPrimitive.content,
                        server.getValue("name").jsonPrimitive.content, "unknown",
                        url = (server["url"] as? JsonPrimitive)?.contentOrNull)
                }
            }
        }
        val api = openCode(id)
        return api.mcpServers(directory).map { server ->
            val name = server.getValue("name").jsonPrimitive.content
            ServerInventoryItem(name, server["title"]?.jsonPrimitive?.content ?: name,
                server.getValue("status").jsonObject.getValue("status").jsonPrimitive.content,
                url = (server["url"] as? JsonPrimitive)?.contentOrNull)
        }
    }

    /** Null denotes a protocol that does not use OpenCode project locations. */
    suspend fun projects(id: String): List<ServerProject>? {
        val profile = requireNotNull(repository.profile(id))
        if (profile.transport != "opencode") return null
        return openCode(id).projects()
            .map { ServerProject(it.name, it.directory) }.distinctBy { it.directory }
    }

    suspend fun setMcpConnected(id: String, name: String, connected: Boolean, directory: String?) {
        openCode(id).setMcpConnected(name, connected, directory)
    }

    suspend fun addMcp(id: String, name: String, config: JsonObject, directory: String?) {
        openCode(id).addMcp(name, config, directory)
    }

    suspend fun removeMcp(id: String, name: String, directory: String?) {
        openCode(id).removeMcp(name, directory)
    }

    internal suspend fun saveConnection(draft: ServerConnectionDraft, profile: ServerConnectionProfile?, repairing: Boolean) {
        val current = profile?.let { requireNotNull(repository.profile(it.id)) }
        val needsCredentials = draft.needsServerCredentials(current, repairing)
        when {
            draft.transport == "opencode" && needsCredentials ->
                saveOpenCode(current, draft.name, draft.endpoint, draft.username, draft.password, draft.authUrl)
            current == null ->
                pair(draft.name, draft.invitation, draft.transport, draft.sshMachineId, draft.allowLocalProviders)
            needsCredentials ->
                rePair(current, draft.name, draft.invitation, draft.transport, draft.sshMachineId, draft.allowLocalProviders)
            else -> rename(current, draft.name, draft.allowLocalProviders)
        }
    }

    suspend fun pair(name: String, url: String, transport: String = "http", sshMachineId: String = "", allowLocalProviders: Boolean = false) {
        require(name.isNotBlank())
        val id = UUID.randomUUID().toString()
        if (transport == "ssh") {
            val machine = requireNotNull(remote).store.also { it.awaitInitialized() }.state.value.machines
                .firstOrNull { it.id == sshMachineId && it.type == RemoteMachineType.SSH }
                ?: error("Select a saved SSH machine")
            val identity = GenerativeServerSshTransport.pair(remote, machine.id)
            repository.save(id, name, "ssh://${machine.username}@${machine.host}:${machine.port}",
                "", identity.identity, "ssh", machine.id, allowLocalProviders)
        } else {
            require(transport == "http")
            val invitation = GenerativeServerInvitation.parse(url)
            val identity = GenerativeServerHttpTransport(invitation).pairAsync()
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
            val paired = GenerativeServerSshTransport.pair(remote, machine.id)
            require(paired.identity.serverId == profile.serverId) { "Different Generative Server; create a new connection" }
            repository.save(profile.id, name, "ssh://${machine.username}@${machine.host}:${machine.port}",
                "", paired.identity, "ssh", machine.id, allowLocalProviders)
        } else {
            require(transport == "http")
            val invitation = GenerativeServerInvitation.parse(url)
            val identity = GenerativeServerHttpTransport(invitation).pairAsync()
            require(identity.serverId == profile.serverId) { "Invitation belongs to another server; add a separate connection" }
            repository.save(profile.id, name, invitation.endpoint.toString(), invitation.pin, identity, allowLocalProviders = allowLocalProviders)
        }
        try { cache?.removeProfile(profile.id) } catch (_: Exception) { /* A cache miss cannot undo pairing. */ }
    }
    /** OpenCode uses its own HTTP API; no Generative Server command is invoked. */
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
            val connection = requireNotNull(repository.openCodeConnection(id))
            OpenCodeAuthentication(connection.profile.endpoint).verify(connection.credentials)
            return
        }
        withServerSession(id) {
            verifyCapabilities()
            catalog()
            list()
        }
    }

    private suspend fun <T> withServerSession(id: String, block: suspend GenerativeServerSessions.() -> T): T {
        val sessions = GenerativeServerSessions(clientFactory(id))
        return try {
            sessions.withConnection { sessions.block() }
        } finally {
            sessions.close()
        }
    }
}

internal fun ServerConnectionDraft.needsServerCredentials(profile: ServerConnectionProfile?, repairing: Boolean): Boolean {
    if (profile == null || transport != profile.transport) return true
    return when (transport) {
        "http" -> invitation.isNotBlank()
        "ssh" -> repairing || sshMachineId != profile.sshMachineId
        "opencode" -> repairing || password.isNotEmpty() || authUrl.isNotBlank() ||
            username != profile.clientId || endpoint != profile.endpoint
        else -> false
    }
}

data class ServerInventoryItem(
    val id: String, val name: String, val detail: String, val integrationId: String? = null,
    val providerType: ModelApiType = ModelApiType.OPENAI, val url: String? = null
)
data class ServerProject(val name: String, val directory: String)

internal data class LocalImportItem(val id: String, val name: String, val detail: String, val icon: String?)
