package org.starfall.generative.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.features.home.shared.ui.RuntimeMcpConnector
import org.starfall.generative.features.home.shared.ui.RuntimeMcpConnectorsSheetContent
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.features.serverconnection.logic.ServerInventoryItem
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionActive

@Composable
internal fun RuntimeMcpConnectors(
    service: ServerConnections,
    connectionId: String,
    directory: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) = key(service, connectionId, directory) {
    var items by remember { mutableStateOf<List<ServerInventoryItem>?>(null) }
    var readError by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var changing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pageActive = LocalScreenTransitionActive.current

    LaunchedEffect(reload) {
        try {
            items = service.mcpServers(connectionId, directory)
            readError = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            items = null
            readError = true
        } finally {
            loading = false
            changing = false
        }
    }

    fun setConnected(id: String, connected: Boolean) {
        if (!pageActive || changing || loading || directory == null) return
        changing = true
        scope.launch {
            try {
                service.setMcpConnected(connectionId, id, connected, directory)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, R.string.server_mcp_operation_failed, Toast.LENGTH_SHORT).show()
            }
            reload++
        }
    }

    RuntimeMcpConnectorsSheetContent(
        connectors = items.orEmpty().map { item ->
            RuntimeMcpConnector(
                name = item.name,
                address = item.url,
                statusLabel = if (directory == null) null else when (item.detail) {
                    "connected" -> R.string.server_mcp_connected
                    "disabled" -> R.string.server_mcp_disabled
                    "pending" -> R.string.server_mcp_pending
                    "failed" -> R.string.server_mcp_failed
                    "needs_auth", "needs_client_registration" -> R.string.server_mcp_needs_auth
                    else -> R.string.server_mcp_unknown
                },
                connected = item.detail == "connected",
                onSetConnected = if (directory != null) {
                    { enabled -> setConnected(item.id, enabled) }
                } else null,
            )
        },
        message = when {
            loading -> R.string.server_remote_loading
            readError -> R.string.server_inventory_failed
            items == null -> R.string.server_remote_unsupported
            items.orEmpty().isEmpty() -> R.string.server_inventory_empty
            directory == null -> R.string.server_inventory_read_only
            else -> null
        },
        switchesEnabled = pageActive && !changing && !loading,
        onBack = onBack,
        onOpenSettings = onOpenSettings.takeIf { pageActive && !changing && items != null },
        onRetry = if (pageActive && readError && !loading) {
            { loading = true; reload++ }
        } else null,
    )
}
