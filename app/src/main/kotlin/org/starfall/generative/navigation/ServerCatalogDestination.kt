package org.starfall.generative.navigation

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.R
import org.starfall.generative.features.mcp.editor.ui.ServerEditor
import org.starfall.generative.features.mcp.list.ui.ServerListScreen
import org.starfall.generative.features.providers.editor.ui.ProviderEditor
import org.starfall.generative.features.providers.list.ui.ProvidersScreen
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.features.serverconnection.logic.ServerInventoryItem
import org.starfall.generative.features.serverconnection.ui.ProviderIntegrationScreen
import org.starfall.generative.integrations.opencode.OpenCodeCatalogEntry
import org.starfall.generative.integrations.opencode.openCodeMcpImportConfig
import org.starfall.generative.integrations.opencode.toMcp
import org.starfall.generative.integrations.opencode.toProvider
import org.starfall.generative.integrations.opencode.withMcp
import org.starfall.generative.integrations.opencode.withProvider
import org.starfall.generative.integrations.opencode.openCodeProviderConfig
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.state.preferences.ModelConfigurationMemoryScope
import org.starfall.generative.shared.ui.screens.ScreenDestination
import java.security.MessageDigest

@Composable
internal fun ServerCatalogDestination(
    service: ServerConnections,
    connectionId: String,
    providers: Boolean,
    onBack: () -> Unit
) {
    val profiles by service.profiles.collectAsState(initial = emptyList())
    val profile = profiles.firstOrNull { it.id == connectionId }
    key(connectionId, providers, profile?.endpoint, profile?.transport, profile?.clientId, profile?.pin) {
        ServerCatalog(
            service, connectionId, providers, profile?.transport == "opencode", onBack
        )
    }
}

@Composable
private fun ServerCatalog(
    service: ServerConnections, connectionId: String, providers: Boolean, openCode: Boolean, onBack: () -> Unit
) {
    var items by remember { mutableStateOf(emptyList<ServerInventoryItem>()) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var providerPage by remember { mutableStateOf<Pair<ModelProvider, Boolean>?>(null) }
    var mcpPage by remember { mutableStateOf<Pair<McpInfo, Boolean>?>(null) }
    var providerEntry by remember { mutableStateOf<OpenCodeCatalogEntry?>(null) }
    var mcpEntry by remember { mutableStateOf<OpenCodeCatalogEntry?>(null) }
    var integrationId by remember { mutableStateOf<String?>(null) }
    var providerOrder by remember { mutableStateOf(emptyList<String>()) }
    var mcpOrder by remember { mutableStateOf(emptyList<String>()) }
    val providerDrafts = remember { mutableStateMapOf<String, ModelProvider>() }
    val mcpDrafts = remember { mutableStateMapOf<String, McpInfo>() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val editorContext = remember(context, connectionId) { ServerCatalogEditorContext(context, connectionId) }

    fun message(id: Int) { Toast.makeText(context, resources.getString(id), Toast.LENGTH_LONG).show() }
    fun mutate(error: Int, refresh: Boolean = true, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { operation() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message(error) }
            finally { busy = false; if (refresh) reload++ }
        }
    }

    LaunchedEffect(reload) {
        try {
            items = if (providers) service.providers(connectionId) else service.mcpServers(connectionId).orEmpty()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message(R.string.server_inventory_failed) }
    }

    fun saveProvider(provider: ModelProvider, isNew: Boolean) {
        mutate(R.string.server_provider_config_failed) {
            if (openCode && !isNew) {
                val entry = requireNotNull(providerEntry)
                val initial = entry.toProvider(requireNotNull(providerPage).first)
                val updated = entry.withProvider(initial, provider)
                if (!service.changeCatalogConfigEntry(connectionId, entry, updated)) {
                    message(R.string.server_provider_reload_failed)
                }
                providerEntry = entry.copy(value = updated)
                providerDrafts[provider.id] = provider
                return@mutate
            }
            if (openCode) {
                require(provider.auth.method in setOf(AuthMethod.PLATFORM_DEFAULT, AuthMethod.NONE))
                val endpoint = provider.baseUrl.ifBlank {
                    if (provider.type == ModelApiType.OPENAI) ModelApiType.OPENAI_RESPONSES.defaultBaseUrl
                    else provider.type.defaultBaseUrl
                }
                val config = requireNotNull(openCodeProviderConfig(
                    provider.name, provider.type, endpoint, provider.auth.token,
                    provider.config.modelIds.orEmpty(), provider.config.modelConfigs.mapValues { it.value.displayName }
                ))
                val target = service.providerConfigFile(connectionId, directory = null)
                val reloaded = service.addConfiguredProvider(connectionId, target, provider.id, config, directory = null)
                if (!reloaded) message(R.string.server_provider_reload_failed)
            } else service.saveImportedProvider(connectionId, provider)
            providerPage = null
        }
    }

    CompositionLocalProvider(LocalContext provides editorContext) {
        ScreenDestination(providerPage, onClosed = { providerPage = null }) { page ->
            if (page != null) {
                val (provider, isNew) = page
                ProviderEditor(
                    initialProvider = provider, isNew = isNew,
                    onDismiss = { if (!busy) providerPage = null },
                    onSave = { saveProvider(it, isNew) },
                    onSaveIcon = { providerDrafts[it.id] = it },
                    onSaveModels = { id, models ->
                        val current = providerDrafts[id] ?: provider
                        saveProvider(current.copy(config = current.config.copy(
                            modelIds = models.keys.toList(), modelConfigs = models
                        )), isNew)
                    },
                    onReorderModels = { id, order ->
                        val current = providerDrafts[id] ?: provider
                        saveProvider(current.copy(config = current.config.copy(modelIds = order)), isNew)
                    },
                    onDelete = if (openCode && !isNew) ({ id ->
                        val entry = providerEntry
                        if (entry != null && entry.path.last() == id) {
                            mutate(R.string.server_provider_config_failed) {
                                if (!service.changeCatalogConfigEntry(connectionId, entry, null)) {
                                    message(R.string.server_provider_reload_failed)
                                }
                                providerDrafts.remove(id)
                            }
                        }
                    }) else null
                )
            }
        }
    }

    val mcpSettings = ToolSettings(quickMcp = items.associate { it.id to (it.detail == "connected") })
    ScreenDestination(mcpPage, onClosed = { mcpPage = null }) { page ->
        if (page != null) {
            val (server, isNew) = page
            ServerEditor(
                initialServer = server, isNew = isNew,
                onRefreshTools = { Result.success(it.cachedTools.orEmpty()) },
                onAuthorizeOAuth = { Result.failure(IllegalStateException()) },
                onClearOAuth = { it.copy(auth = it.auth.copy(oauthAuthorized = false)) },
                cachedTools = server.cachedTools, cachedError = null, cachedLoading = false, toolSettings = mcpSettings,
                onSetToolEnabled = { _, _, _ -> }, onSetToolParameter = { _, _, _, _ -> },
                onDismiss = { if (!busy) mcpPage = null },
                onSave = { edited ->
                    mutate(R.string.server_mcp_operation_failed) {
                        if (openCode) {
                            val entry = mcpEntry
                            if (entry != null && !isNew) {
                                val newKey = edited.name.takeUnless { it == server.id }
                                if (newKey != null) require(service.mcpServers(connectionId).orEmpty().none { it.id == newKey })
                                if (!service.changeCatalogConfigEntry(connectionId, entry, entry.withMcp(server, edited), newKey)) {
                                    message(R.string.server_provider_reload_failed)
                                }
                                mcpDrafts.remove(server.id)
                                mcpPage = null
                                return@mutate
                            }
                            if (isNew) require(service.mcpServers(connectionId).orEmpty().none { it.id == edited.name })
                            else require(edited.name == server.id && mcpDrafts.containsKey(server.id))
                            service.addMcp(connectionId, edited.name, openCodeMcpImportConfig(edited), directory = null)
                        } else service.saveImportedMcp(connectionId, edited)
                        val id = if (openCode) edited.name else edited.id
                        mcpDrafts[id] = edited.copy(id = id)
                        mcpPage = null
                    }
                }
            )
        }
    }

    ScreenDestination(integrationId, onClosed = { integrationId = null; reload++ }) { id ->
        if (id != null) {
            var integration by remember(id) { mutableStateOf<JsonObject?>(null) }
            LaunchedEffect(id) {
                try { integration = service.integration(connectionId, id, directory = null) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { message(R.string.server_inventory_failed); integrationId = null }
            }
            integration?.let { info ->
                ProviderIntegrationScreen(
                    info, onBack = { integrationId = null; reload++ },
                    connectKey = { key, answer -> service.connectIntegrationKey(connectionId, id, key, answer, directory = null) },
                    beginOAuth = { method, answer -> service.beginIntegrationOAuth(connectionId, id, method, answer, directory = null) },
                    oauthStatus = { attempt -> service.integrationOAuthStatus(connectionId, id, attempt, directory = null) },
                    completeOAuth = { attempt, code -> service.completeIntegrationOAuth(connectionId, id, attempt, code, directory = null) },
                    cancelOAuth = { attempt -> service.cancelIntegrationOAuth(connectionId, id, attempt, directory = null) }
                )
            }
        }
    }

    if (providers) {
        val rows = items.inCatalogOrder(providerOrder).map { item ->
            ModelProvider(item.id, item.name, item.providerType, baseUrl = item.url.orEmpty())
        }
        ProvidersScreen(
            providers = rows, isGridView = grid, onToggleGridView = { grid = it },
            onOpenProviderEditor = { provider, isNew ->
                val id = if (openCode && !isNew) items.firstOrNull { it.id == provider.id }?.integrationId else null
                if (id != null) integrationId = id
                else if (openCode && !isNew) mutate(R.string.server_inventory_failed, refresh = false) {
                    val entry = service.providerConfigEntry(connectionId, provider.id)
                    val editable = entry.toProvider(provider)
                    providerEntry = entry
                    providerDrafts[provider.id] = editable
                    providerPage = editable to false
                } else {
                    providerEntry = null
                    providerPage = provider to isNew
                }
            },
            onDeleteProvider = { id ->
                mutate(R.string.server_provider_config_failed) {
                    require(openCode)
                    val entry = service.providerConfigEntry(connectionId, id)
                    if (!service.changeCatalogConfigEntry(connectionId, entry, null)) {
                        message(R.string.server_provider_reload_failed)
                    }
                    providerDrafts.remove(id)
                }
            },
            onReorderProviders = { providerOrder = it },
            onReorderRootItems = { providerOrder = it.map { item -> item.id } },
            onBack = onBack
        )
    } else {
        ServerListScreen(
            mcpServers = items.inCatalogOrder(mcpOrder).map { item ->
                mcpDrafts[item.id] ?: McpInfo(item.id, item.name, url = item.url)
            },
            isGridView = grid, onToggleGridView = { grid = it },
            toolsCache = emptyMap(), toolErrors = emptyMap(), toolsLoading = emptySet(), toolSettings = mcpSettings,
            onSetToolEnabled = { _, _, _ -> },
            onSetMcpEnabled = { id, enabled ->
                mutate(R.string.server_mcp_operation_failed) {
                    service.setMcpConnected(connectionId, id, enabled, directory = null)
                }
            },
            onDeleteMcpServer = { id ->
                mutate(R.string.server_mcp_operation_failed) {
                    service.removeMcp(connectionId, id, directory = null)
                    mcpDrafts.remove(id)
                }
            },
            onReorderMcpServers = { mcpOrder = it },
            onOpenEditor = { server, isNew ->
                if (openCode && !isNew && server.id !in mcpDrafts) mutate(R.string.server_inventory_failed, refresh = false) {
                    val entry = service.mcpConfigEntry(connectionId, server.id)
                    val editable = entry.toMcp()
                    mcpEntry = entry
                    mcpPage = editable to false
                } else {
                    mcpEntry = null
                    mcpPage = server to isNew
                }
            },
            onBack = onBack
        )
    }
}

private fun List<ServerInventoryItem>.inCatalogOrder(ids: List<String>): List<ServerInventoryItem> {
    val order = ids.withIndex().associate { it.value to it.index }
    return sortedBy { order[it.id] ?: Int.MAX_VALUE }
}

private class ServerCatalogEditorContext(context: Context, connectionId: String) :
    ContextWrapper(context), ModelConfigurationMemoryScope {
    override val modelConfigurationNamespace = "server-model-configuration-" +
        MessageDigest.getInstance("SHA-256").digest(connectionId.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
