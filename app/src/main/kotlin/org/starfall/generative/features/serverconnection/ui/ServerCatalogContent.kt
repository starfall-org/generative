package org.starfall.generative.features.serverconnection.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import org.starfall.generative.features.serverconnection.logic.ServerCatalog
import org.starfall.generative.features.serverconnection.logic.ServerConnections

@Composable
internal fun ServerCatalogContent(
    service: ServerConnections,
    connectionId: String,
    providers: Boolean,
    directory: String?,
    content: @Composable (ServerCatalog) -> Unit,
) {
    val profiles by service.profiles.collectAsState(initial = emptyList())
    val profile = profiles.firstOrNull { it.id == connectionId }
    val context = LocalContext.current
    val resources = LocalResources.current
    val showMessage by rememberUpdatedState<(Int) -> Unit> { id ->
        Toast.makeText(context, resources.getString(id), Toast.LENGTH_LONG).show()
    }
    key(connectionId, providers, directory, profile?.credentialRevision) {
        val scope = rememberCoroutineScope()
        val catalog = remember(service, profile != null) {
            ServerCatalog(
                service = service,
                connectionId = connectionId,
                providers = providers,
                directory = directory,
                openCode = profile?.transport == "opencode",
                insecure = profile?.endpoint?.startsWith("http://", ignoreCase = true) == true,
                scope = scope,
                showMessage = { showMessage(it) },
            )
        }
        LaunchedEffect(catalog, catalog.reload) {
            if (profile != null) catalog.load()
        }
        content(catalog)
    }
}
