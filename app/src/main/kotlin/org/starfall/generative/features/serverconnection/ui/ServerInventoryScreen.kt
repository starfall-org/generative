package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.generative.R
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.features.serverconnection.logic.ServerInventoryItem
import org.starfall.generative.integrations.opencode.OpenCodeProviderConfigFile
import org.starfall.generative.shared.ui.providers.ProviderEditorLayout
import org.starfall.generative.shared.ui.screens.ScreenDestination

/** Owns server requests; the feature screens receive only data and callbacks. */
@Composable
internal fun ServerInventoryScreen(
    service: ServerConnections,
    connectionId: String,
    providers: Boolean,
    onBack: () -> Unit
) = key(connectionId, providers) {
    var items by remember { mutableStateOf<List<ServerInventoryItem>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var operationFailed by remember { mutableStateOf(false) }
    var providerReloadFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf<Boolean?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importItems by remember { mutableStateOf<List<org.starfall.generative.features.serverconnection.logic.LocalImportItem>>(emptyList()) }
    var canImport by remember { mutableStateOf(false) }
    var importLoading by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val profiles by service.profiles.collectAsState(initial = emptyList())
    val profile = profiles.firstOrNull { it.id == connectionId }
    val isOpenCode = profile?.transport == "opencode"
    val insecure = profile?.endpoint?.startsWith("http://", true) == true

    LaunchedEffect(profile, reload) {
        if (profile == null || canImport) return@LaunchedEffect
        try {
            canImport = service.importSupported(connectionId, providers)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { canImport = false }
    }

    LaunchedEffect(importing) {
        if (!importing) return@LaunchedEffect
        importItems = emptyList()
        importLoading = true
        importError = null
        try {
            importItems = service.localImportItems(providers)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { importError = R.string.server_inventory_failed }
        finally { importLoading = false }
    }

    if (importing) LocalCatalogImportSheet(
        providers = providers, items = importItems, loading = importLoading,
        busy = busy, error = importError, runtimeOnly = isOpenCode,
        onDismiss = { importing = false },
        onImport = { localId ->
            if (!busy && !importLoading && canImport) {
                busy = true
                importError = null
                operationFailed = false
                scope.launch {
                    try {
                        service.importLocal(connectionId, localId, providers, directory = null)
                        importing = false
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { importError = R.string.server_import_failed }
                    finally { busy = false; reload++ }
                }
            }
        }
    )

    ScreenDestination(page = connecting, onClosed = { connecting = null; reload++ }) { integrationId ->
        if (integrationId != null) {
            var integration by remember(integrationId) { mutableStateOf<JsonObject?>(null) }
            var lookupFailed by remember(integrationId) { mutableStateOf(false) }
            LaunchedEffect(integrationId) {
                try { integration = service.integration(connectionId, integrationId, directory = null) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { lookupFailed = true }
            }
            val info = integration
            if (info != null) ProviderIntegrationScreen(info, onBack = { connecting = null; reload++ },
                connectKey = { key, answer -> service.connectIntegrationKey(connectionId, integrationId, key, answer, directory = null) },
                beginOAuth = { method, answer -> service.beginIntegrationOAuth(connectionId, integrationId, method, answer, directory = null) },
                oauthStatus = { attempt -> service.integrationOAuthStatus(connectionId, integrationId, attempt, directory = null) },
                completeOAuth = { attempt, code -> service.completeIntegrationOAuth(connectionId, integrationId, attempt, code, directory = null) },
                cancelOAuth = { attempt -> service.cancelIntegrationOAuth(connectionId, integrationId, attempt, directory = null) })
            else ProviderEditorLayout(title = { Text(stringResource(R.string.model_providers)) },
                onBack = { connecting = null }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (lookupFailed) Text(stringResource(R.string.server_inventory_failed)) else CircularProgressIndicator()
                }
            }
        }
    }

    ScreenDestination(page = adding, onClosed = { adding = null }) { page ->
        if (page != null && providers) {
            var file by remember { mutableStateOf<OpenCodeProviderConfigFile?>(null) }
            LaunchedEffect(Unit) {
                try { file = service.providerConfigFile(connectionId, directory = null) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { operationFailed = true }
            }
            RemoteProviderEditor(file, existingIds = items.orEmpty().mapTo(hashSetOf()) { it.id },
                busy = busy, failed = operationFailed, insecure = insecure, onBack = { adding = null },
                onSave = { providerId, config ->
                    val target = file
                    if (!busy && target != null) {
                        busy = true
                        operationFailed = false
                        providerReloadFailed = false
                        scope.launch {
                            try {
                                providerReloadFailed = !service.addConfiguredProvider(connectionId, target, providerId, config, directory = null)
                                adding = null
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { operationFailed = true }
                            finally { busy = false; reload++ }
                        }
                    }
                })
        } else if (page != null) McpRuntimeEditor(existingNames = items.orEmpty().mapTo(hashSetOf()) { it.id },
            busy = busy, failed = operationFailed, onBack = { adding = null }, onSave = { name, config ->
                if (!busy) {
                    busy = true
                    operationFailed = false
                    scope.launch {
                        try { service.addMcp(connectionId, name, config, directory = null); adding = null }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { operationFailed = true }
                        finally { busy = false; reload++ }
                    }
                }
            })
    }

    LaunchedEffect(reload) {
        loaded = false
        failed = false
        try {
            items = if (providers) service.providers(connectionId) else service.mcpServers(connectionId)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        loaded = true
    }

    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!providers && operationFailed) Text(stringResource(R.string.server_mcp_operation_failed), color = MaterialTheme.colorScheme.error)
            if (providers && providerReloadFailed) Text(stringResource(R.string.server_provider_reload_failed), color = MaterialTheme.colorScheme.error)
        }
    }
    val status: (@Composable ColumnScope.() -> Unit)? = when {
        !loaded -> ({ CircularProgressIndicator() })
        failed -> ({
            Text(stringResource(R.string.server_inventory_failed))
            TextButton(onClick = { reload++ }) { Text(stringResource(R.string.common_retry)) }
        })
        else -> null
    }
    val ready = status == null
    val showHeader = ready && if (providers) providerReloadFailed else operationFailed
    val canEdit = ready && isOpenCode
    val openImport: (() -> Unit)? = if (canImport) ({
        importLoading = true
        importItems = emptyList()
        importError = null
        importing = true
    }) else null
    if (providers) {
        RemoteProvidersScreen(catalog = items.orEmpty().map { ProviderCatalogEntry(it.id, it.name, it.detail, it.integrationId) },
            onBack = onBack, header = header.takeIf { showHeader }, status = status,
            onConnect = if (canEdit) ({ connecting = it }) else null,
            onAdd = if (canEdit) ({ operationFailed = false; adding = true }) else null,
            onImport = openImport, busy = busy)
    } else {
        RemoteMcpScreen(catalog = items.orEmpty().map { McpCatalogEntry(it.id, it.name, it.detail) }, busy = busy,
            onBack = onBack, header = header.takeIf { showHeader }, status = status,
            onAdd = if (canEdit) ({ operationFailed = false; adding = true }) else null,
            onImport = openImport,
            onDelete = if (canEdit) ({ deleting = it }) else null,
            onToggle = if (canEdit) ({ name, connected ->
                if (!busy) {
                    busy = true
                    operationFailed = false
                    scope.launch {
                        try { service.setMcpConnected(connectionId, name, connected, directory = null) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { operationFailed = true }
                        finally { busy = false; reload++ }
                    }
                }
            }) else null)
    }
    deleting?.let { name ->
        org.starfall.generative.shared.ui.dialogs.AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.dialog_delete_connector_title)) },
            text = { Text(stringResource(R.string.server_mcp_remove_confirm, name)) },
            confirmButton = { Button(enabled = !busy, colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ), onClick = {
                deleting = null
                busy = true
                operationFailed = false
                scope.launch {
                    try { service.removeMcp(connectionId, name, directory = null) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { operationFailed = true }
                    finally { busy = false; reload++ }
                }
            }) { Text(stringResource(R.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_close)) } })
    }
}
