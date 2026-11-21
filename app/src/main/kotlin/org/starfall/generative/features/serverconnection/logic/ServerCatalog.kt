package org.starfall.generative.features.serverconnection.logic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.R
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.provider.ModelProvider

internal class ServerCatalog(
    private val service: ServerConnections,
    private val connectionId: String,
    private val providers: Boolean,
    private val directory: String?,
    val openCode: Boolean,
    val insecure: Boolean,
    private val scope: CoroutineScope,
    private val showMessage: (Int) -> Unit,
) {
    private val operations = ServerCatalogOperations(service, connectionId, openCode, directory)
    private var items by mutableStateOf(emptyList<ServerInventoryItem>())
    private var providerOrder by mutableStateOf(emptyList<String>())
    private var mcpOrder by mutableStateOf(emptyList<String>())
    private val providerDrafts = mutableStateMapOf<String, ModelProvider>()
    private val mcpDrafts = mutableStateMapOf<String, McpInfo>()
    private var authorizationJob: Job? = null
    var reload by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var providerPage by mutableStateOf<ServerCatalogOperations.ProviderEdit?>(null)
        private set
    var mcpPage by mutableStateOf<ServerCatalogOperations.McpEdit?>(null)
        private set

    val providerRows: List<ModelProvider>
        get() = items.inCatalogOrder(providerOrder).map { item ->
            ModelProvider(item.id, item.name, item.providerType, baseUrl = item.url.orEmpty())
        }
    val mcpRows: List<McpInfo>
        get() = items.inCatalogOrder(mcpOrder).map { item ->
            mcpDrafts[item.id] ?: McpInfo(item.id, item.name, url = item.url)
        }
    val mcpSettings: ToolSettings
        get() = ToolSettings(quickMcp = items.associate { it.id to (it.detail == "connected") })

    suspend fun load() {
        try {
            items = if (providers) service.providers(connectionId) else service.mcpServers(connectionId, directory).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            showMessage(R.string.server_inventory_failed)
        }
    }

    fun openProvider(provider: ModelProvider, isNew: Boolean) {
        if (openCode && !isNew) {
            mutate(R.string.server_inventory_failed, refresh = false) {
                val integrationId = items.firstOrNull { it.id == provider.id }?.integrationId
                val page = operations.openProvider(provider, integrationId)
                if (page.authorization == null) providerDrafts[provider.id] = page.provider
                providerPage = page
            }
        } else {
            providerPage = ServerCatalogOperations.ProviderEdit(provider, isNew)
        }
    }

    fun closeProvider() { providerPage = null }

    fun reorderProviders(ids: List<String>) { providerOrder = ids }

    fun rememberProviderDraft(draft: ModelProvider) { providerDrafts[draft.id] = draft }

    fun saveProvider(
        page: ServerCatalogOperations.ProviderEdit,
        draft: ModelProvider,
        keyAnswers: () -> JsonObject? = { null },
    ) {
        mutate(R.string.server_provider_config_failed) {
            val saved = operations.saveProvider(page, draft, keyAnswers())
            if (!saved.reloaded) showMessage(R.string.server_provider_reload_failed)
            if (saved.updateDraft) rememberProviderDraft(draft)
            if (saved.closeEditor && providerPage === page) closeProvider()
        }
    }

    fun updateProviderDraft(
        page: ServerCatalogOperations.ProviderEdit,
        id: String,
        transform: (ModelProvider) -> ModelProvider,
    ) {
        if (page.authorization != null) return
        saveProvider(page, transform(providerDrafts[id] ?: page.provider))
    }

    fun deleteProvider(id: String) {
        val page = providerPage
        mutate(R.string.server_provider_config_failed, refresh = false) {
            val reloaded = operations.deleteProvider(id, page) ?: return@mutate
            if (!reloaded) showMessage(R.string.server_provider_reload_failed)
            reload++
            providerDrafts.remove(id)
            if (providerPage?.provider?.id == id) closeProvider()
        }
    }

    suspend fun authorizeProvider(
        authorization: OpenCodeProviderAuthorization,
        draft: ModelProvider,
        method: () -> OpenCodeAuthorizationMethod?,
        answers: () -> JsonObject?,
        presentChallenge: suspend (OpenCodeAuthorizationChallenge) -> String?,
    ): Result<ModelProvider> {
        val job = currentCoroutineContext().job
        authorizationJob = job
        return try {
            Result.success(authorization.authorize(draft, requireNotNull(method()), answers(), presentChallenge))
                .also { reload++ }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (authorizationJob === job) authorizationJob = null
        }
    }

    fun cancelProviderAuthorization() { authorizationJob?.cancel() }

    fun openMcp(server: McpInfo, isNew: Boolean) {
        if (openCode && !isNew && server.id !in mcpDrafts) {
            mutate(R.string.server_inventory_failed, refresh = false) { mcpPage = operations.openMcp(server) }
        } else {
            mcpPage = ServerCatalogOperations.McpEdit(server, isNew, runtimeDraft = server.id in mcpDrafts)
        }
    }

    fun closeMcp() { mcpPage = null }

    fun reorderMcp(ids: List<String>) { mcpOrder = ids }

    fun saveMcp(page: ServerCatalogOperations.McpEdit, draft: McpInfo) {
        mutate(R.string.server_mcp_operation_failed) {
            val saved = operations.saveMcp(page, draft)
            if (!saved.reloaded) showMessage(R.string.server_mcp_reload_failed)
            if (saved.draft != null) mcpDrafts[saved.draft.id] = saved.draft
            else mcpDrafts.remove(page.server.id)
            if (mcpPage === page) closeMcp()
        }
    }

    fun setMcpConnected(id: String, connected: Boolean) {
        mutate(R.string.server_mcp_operation_failed) { service.setMcpConnected(connectionId, id, connected, directory) }
    }

    fun deleteMcp(id: String) {
        mutate(R.string.server_mcp_operation_failed) {
            service.removeMcp(connectionId, id, directory)
            mcpDrafts.remove(id)
        }
    }

    private fun mutate(error: Int, refresh: Boolean = true, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showMessage(error)
            } finally {
                busy = false
                if (refresh) reload++
            }
        }
    }
}

private fun List<ServerInventoryItem>.inCatalogOrder(ids: List<String>): List<ServerInventoryItem> {
    val order = ids.withIndex().associate { it.value to it.index }
    return sortedBy { order[it.id] ?: Int.MAX_VALUE }
}
