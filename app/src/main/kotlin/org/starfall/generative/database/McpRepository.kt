package org.starfall.generative.database

import org.starfall.generative.shared.storage.ImmediateState
import org.starfall.generative.shared.storage.upsert

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.starfall.generative.integrations.mcp.McpOAuthService
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.shared.icons.IconStore
import org.starfall.generative.shared.model.McpAuthorization
import org.starfall.generative.shared.model.McpAuthMethod
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.McpProtocol
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.database.AppDatabase
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.database.entities.McpAuthEntity
import org.starfall.generative.database.entities.McpCachedToolEntity
import org.starfall.generative.database.entities.McpServerEntity
import org.starfall.generative.database.entities.McpToolCacheEntity
import kotlinx.serialization.json.JsonObject

class McpRepository(
    private val db: AppDatabase,
    private val service: McpService,
    private val icons: IconStore? = null,
    private val oauth: McpOAuthService? = null
) {
    private val dao = db.mcpServerDao()
    private val structured = db.structuredJsonDao()

    suspend fun discoverTools(server: McpInfo) = service.discover(resolveServer(server))

    suspend fun authorizeOAuth(server: McpInfo): Result<McpInfo> = runCatching {
        val authorized = (oauth ?: error("MCP OAuth is unavailable")).authorize(resolveServer(server))
            .copy(credentialState = CredentialLoadState.LOADED)
        if (dao.getServerById(server.id) != null) saveServer(authorized)
        authorized
    }

    suspend fun clearOAuth(server: McpInfo): McpInfo {
        val resolved = resolveServer(server)
        oauth?.clear(server.id)
        val cleared = resolved.copy(auth = resolved.auth.copy(value = null, oauthAuthorized = false))
        if (dao.getServerById(server.id) != null) saveServer(cleared)
        return cleared
    }

    private val stored: Flow<List<McpInfo>> = dao.getAllServers().map { entities ->
        db.withTransaction { entities.map { entityToMetadata(it) } }
    }
    private val state = ImmediateState(stored)
    val allServers: Flow<List<McpInfo>> = state.flow

    suspend fun getById(id: String): McpInfo? = withContext(Dispatchers.IO) {
        db.withTransaction { dao.getServerById(id)?.let { selected(it) } }
    }

    suspend fun resolveServer(server: McpInfo): McpInfo = when (server.credentialState) {
        CredentialLoadState.LOADED, CredentialLoadState.NOT_CONFIGURED -> server
        CredentialLoadState.NOT_REQUESTED -> withContext(Dispatchers.IO) {
            db.withTransaction {
                val entity = dao.getServerById(server.id)
                    ?: error("MCP server ${server.id} no longer exists. Reload the server list.")
                resolveSecrets(server, entity)
            }
        }
        CredentialLoadState.ERROR, CredentialLoadState.LOADING -> error("MCP server ${server.id} credentials are ${server.credentialState}. Reload before use.")
    }

    suspend fun loadServersForExport(): List<McpInfo> = withContext(Dispatchers.IO) {
        db.withTransaction { dao.getAllServers().first().map { selected(it) } }
    }

    internal fun exportOAuthTokens(): Map<String, JsonObject> = oauth?.exportTokens().orEmpty()

    internal fun importOAuthTokens(tokens: Map<String, JsonObject>) {
        oauth?.importTokens(tokens)
    }

    /** Replaces every MCP server during a full application restore. */
    suspend fun replaceAll(servers: List<McpInfo>) {
        dao.getAllServers().first().map { it.id }.forEach { deleteServer(it) }
        servers.forEach { saveServer(it) }
    }

    suspend fun saveServer(server: McpInfo) {
        requireSavableCredentials(server.credentialState)
        state.mutate({ it.upsert(server.metadata()) { item -> item.id }.sortedBy { it.sortOrder } }) {
            db.withTransaction {
                val previous = dao.getServerById(server.id)
                val preserve = server.credentialState == CredentialLoadState.NOT_REQUESTED
                val payload = server.authPayload()
                if (preserve) {
                    check(previous?.authId != null) { "MCP server ${server.id} credentials are no longer available. Reload before saving." }
                    check(previous.protocol == server.protocol.name && !payload.hasSecrets()) {
                        "Resolve MCP server ${server.id} credentials before changing authentication, protocol, or private endpoints."
                    }
                    check(EndpointSecrets.split(previous.url).privateUrl == null) {
                        "MCP server ${server.id} has a legacy private endpoint. Open and save the selected server before editing metadata."
                    }
                }
                val authId = if (preserve) previous?.authId else if (payload.hasSecrets()) previous?.authId ?: "mcp-auth-${server.id}" else null
                if (!preserve && authId != null) {
                    val auth = McpAuthEntity(authId, SecretCipher.encrypt(json.encodeToString(payload)), 2, 1, "READY", System.currentTimeMillis())
                    if (!db.updateStorageRow("mcp_auth", auth.id, "encryptedPayload" to auth.encryptedPayload,
                            "payloadVersion" to auth.payloadVersion, "cipherVersion" to auth.cipherVersion, "state" to auth.state, "updatedAt" to auth.updatedAt)) {
                        dao.insertAuth(auth)
                    }
                }
                val entity = McpServerEntity(server.id, server.name, server.protocol.name, EndpointSecrets.split(server.url).publicUrl,
                    server.icon, server.sortOrder, authId, if (server.cachedTools == null) "NO_CACHE" else "READY")
                if (!db.updateStorageRow("mcp_servers", entity.id, "name" to entity.name, "protocol" to entity.protocol,
                        "url" to entity.url, "icon" to entity.icon, "sortOrder" to entity.sortOrder, "authId" to entity.authId, "cacheState" to entity.cacheState)) {
                    dao.insertOrUpdate(entity)
                }
                previous?.authId?.takeIf { it != authId }?.let { db.deleteUnreferencedEndpointAuth("mcp_auth", "mcp_servers", it) }
                if (!preserve) {
                    if (authId == null) structured.replaceDocument(StructuredJsonDocument("mcp", server.id, "auth_method", JsonPrimitive(server.auth.method.name)))
                    else deleteDocument("mcp", server.id, "auth_method")
                }
                replaceCachedTools(server.id, server.cachedTools)
            }
        }
    }

    suspend fun saveCachedTools(serverId: String, tools: List<ToolDefinition>) {
        state.mutate({ items -> items.map { if (it.id == serverId) it.copy(cachedTools = tools) else it } }) {
            db.withTransaction {
                if (dao.getServerById(serverId) == null) return@withTransaction
                replaceCachedTools(serverId, tools)
                db.updateStorageRow("mcp_servers", serverId, "cacheState" to "READY")
            }
        }
    }

    private suspend fun replaceCachedTools(serverId: String, tools: List<ToolDefinition>?) {
        dao.getCachedTools(serverId).forEach { deleteDocument("mcp_tool", "$serverId/${it.ordinal}", "schema") }
        dao.deleteCachedTools(serverId)
        tools.orEmpty().forEachIndexed { ordinal, tool ->
            dao.insertCachedTools(listOf(McpCachedToolEntity(serverId, ordinal, tool.name, tool.description,
                tool.serverId, tool.originalName, tool.remoteMachineId)))
            val ownerId = "$serverId/$ordinal"
            if (tool.schema.isNotEmpty()) structured.replaceDocument(StructuredJsonDocument("mcp_tool", ownerId, "schema", tool.schema))
        }
        dao.insertToolCache(McpToolCacheEntity(serverId, if (tools == null) "NO_CACHE" else "READY"))
    }

    suspend fun deleteServer(id: String) {
        state.mutate({ items -> items.filterNot { it.id == id } }) {
            oauth?.clear(id)
            db.withTransaction {
                val entity = dao.getServerById(id)
                dao.getCachedTools(id).forEach { deleteDocument("mcp_tool", "$id/${it.ordinal}", "schema") }
                deleteDocument("mcp", id, "auth_method")
                dao.deleteById(id)
                entity?.authId?.let { db.deleteUnreferencedEndpointAuth("mcp_auth", "mcp_servers", it) }
            }
        }
    }

    fun queueReorderServers(ids: List<String>) {
        val positions = ids.withIndex().associate { it.value to it.index }
        state.enqueue({ items -> items.map { item ->
            positions[item.id]?.let { item.copy(sortOrder = it) } ?: item
        }.sortedBy { it.sortOrder } }) {
            db.withTransaction { ids.forEachIndexed { index, id -> dao.updateSortOrder(id, index) } }
        }
    }

    suspend fun reorderServers(ids: List<String>) {
        state.mutate({ items -> items.map { item -> ids.indexOf(item.id).takeIf { it >= 0 }?.let { item.copy(sortOrder = it) } ?: item }.sortedBy { it.sortOrder } }) {
            db.withTransaction { ids.forEachIndexed { index, id -> dao.updateSortOrder(id, index) } }
        }
    }

    private suspend fun entityToMetadata(entity: McpServerEntity): McpInfo {
        val cacheState = dao.getToolCache(entity.id)?.state ?: entity.cacheState
        val tools = dao.getCachedTools(entity.id).sortedBy { it.ordinal }.map { tool ->
            val schema = structured.readDocument("mcp_tool", "${entity.id}/${tool.ordinal}", "schema") as? JsonObject ?: JsonObject(emptyMap())
            ToolDefinition(tool.name, tool.description, schema, tool.toolServerId, tool.originalName, tool.remoteMachineId)
        }
        return McpInfo(
            id = entity.id,
            name = entity.name,
            protocol = runCatching { McpProtocol.valueOf(entity.protocol) }.getOrDefault(McpProtocol.STREAMABLE_HTTP),
            url = EndpointSecrets.split(entity.url).publicUrl,
            auth = McpAuthorization(method = if (entity.authId == null) {
                (structured.readDocument("mcp", entity.id, "auth_method") as? JsonPrimitive)?.content
                    ?.let { McpAuthMethod.valueOf(it) } ?: McpAuthMethod.NONE
            } else McpAuthMethod.NONE),
            cachedTools = tools.takeIf { cacheState == "READY" },
            sortOrder = entity.sortOrder,
            icon = entity.icon,
            credentialState = if (entity.authId == null) CredentialLoadState.NOT_CONFIGURED else CredentialLoadState.NOT_REQUESTED
        )
    }

    private suspend fun selected(entity: McpServerEntity): McpInfo =
        entityToMetadata(entity).let { if (entity.authId == null) it else resolveSecrets(it, entity) }

    private suspend fun resolveSecrets(server: McpInfo, entity: McpServerEntity): McpInfo {
        check(server.protocol.name == entity.protocol) { "MCP server ${server.id} changed protocol. Reload before resolving credentials." }
        val authId = entity.authId ?: error("MCP server ${server.id} credentials are no longer configured. Reload the server.")
        val auth = dao.getAuth(authId)
            ?: error("MCP server ${server.id} authentication record is missing. Re-enter credentials; saved metadata has been retained.")
        val payload = decodeEndpointAuth<McpAuthPayload>("MCP server ${server.id}", auth.encryptedPayload,
            auth.payloadVersion, auth.cipherVersion, auth.state)
        val privateUrl = payload.privateUrl?.let {
            if (auth.payloadVersion == 1 && (it.startsWith("?") || it.startsWith("#"))) entity.url.orEmpty() + it else it
        } ?: EndpointSecrets.split(entity.url).privateUrl
        return server.copy(url = restoreEndpoint(server.url, privateUrl), headers = payload.headers.takeIf { it.isNotEmpty() },
            auth = payload.authorization, credentialState = CredentialLoadState.LOADED)
    }

    private suspend fun deleteDocument(type: String, id: String, field: String) {
        structured.getDocument(type, id, field)?.let { structured.deleteDocument(it.id) }
    }
}
