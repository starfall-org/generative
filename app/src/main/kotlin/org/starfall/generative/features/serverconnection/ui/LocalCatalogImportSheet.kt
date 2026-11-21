package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.database.repositories.ServerConnectionProfile
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.features.serverconnection.logic.LocalImportItem
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.subfeatures.icons.ui.providerInitials
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.sheets.ItemListSheet
import org.starfall.generative.shared.ui.sheets.ItemListSheetItem

@Composable
internal fun ServerConnectionCatalogImport(
    service: ServerConnections, profile: ServerConnectionProfile, busy: Boolean,
    onBusyChange: (Boolean) -> Unit
) {
    val kinds by produceState(emptyList<Boolean>(), service, profile.id, profile.endpoint, profile.transport) {
        value = try { service.localImportKinds(profile.id) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
    }
    var importing by remember(profile.id, profile.endpoint, profile.transport) { mutableStateOf<Boolean?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (kinds.isNotEmpty()) {
        HorizontalDivider()
        Text(stringResource(R.string.server_import_local), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { providers ->
                OutlinedButton(onClick = { importing = providers }, enabled = !busy) {
                    Text(stringResource(if (providers) R.string.model_providers else R.string.mcp))
                }
            }
        }
    }
    importing?.let { providers ->
        key(providers) {
            var items by remember { mutableStateOf(emptyList<LocalImportItem>()) }
            var loading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(service, providers) {
                try { items = service.localImportItems(providers) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = R.string.server_inventory_failed }
                finally { loading = false }
            }
            LocalCatalogImportSheet(
                providers = providers, items = items, loading = loading, busy = busy,
                error = error, runtimeOnly = profile.transport == "opencode",
                onDismiss = { importing = null },
                onImport = { localId ->
                    if (!busy && !sending) {
                        sending = true
                        onBusyChange(true)
                        error = null
                        scope.launch {
                            try {
                                service.importLocal(profile.id, localId, providers, directory = null)
                                importing = null
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { error = R.string.server_import_failed }
                            finally {
                                sending = false
                                onBusyChange(false)
                            }
                        }
                    }
                }
            )
        }
    }
}

/** A presentation-only picker. No credentials are loaded or held in Compose state. */
@Composable
internal fun LocalCatalogImportSheet(
    providers: Boolean,
    items: List<LocalImportItem>,
    loading: Boolean,
    busy: Boolean,
    error: Int?,
    runtimeOnly: Boolean,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = items.filter { it.name.contains(query.trim(), ignoreCase = true) }
    ItemListSheet(
        title = stringResource(R.string.server_import_local),
        onDismissRequest = { if (!busy) onDismiss() },
        searchQuery = query,
        onSearchQueryChange = { query = it },
        items = filtered.map { item ->
            ItemListSheetItem(
                key = item.id, value = item.id,
                onClick = if (!loading && !busy) ({ onImport(item.id) }) else null,
                icon = { EntityIcon(item.icon, text = providerInitials(item.name),
                    fallback = if (providers) Icons.Outlined.Hub else Icons.Outlined.Mcp, matchName = item.name) },
                content = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        },
        loading = loading || busy,
        error = error?.let { stringResource(it) },
        emptyMessage = stringResource(R.string.shared_no_items),
        footer = {
            if (!loading) Text(
                stringResource(if (runtimeOnly) R.string.server_import_runtime_credentials else R.string.server_import_credentials),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}
