@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.starfall.generative.features.machines.details.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.features.machines.shared.ui.RemoteErrorText
import org.starfall.generative.features.machines.shared.ui.RemoteIconTile
import org.starfall.generative.features.machines.shared.ui.RemoteListRow
import org.starfall.generative.features.machines.shared.ui.RemoteMachinesTopBar
import org.starfall.generative.shared.model.GCloudOAuthClient
import org.starfall.generative.shared.model.GoogleOAuthClientType
import org.starfall.generative.shared.model.RemoteMachineState
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.layout.AppSwipeTabs

@Composable
internal fun RemoteMachineSettings(
    state: RemoteMachineState,
    viewModel: RemoteMachinesViewModel,
    onBack: () -> Unit
) {
    BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onBack)
    var clientEditor by remember { mutableStateOf<GCloudOAuthClient?>(null) }
    var identityError by remember { mutableStateOf<String?>(null) }
    var oauthError by remember { mutableStateOf<String?>(null) }
    val localizedResources = LocalResources.current
    val scope = rememberCoroutineScope()
    val identityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            viewModel.importIdentityKey(uri)
                .onSuccess { identityError = null }
                .onFailure { identityError = it.message }
        }
    }
    var publicKeyTarget by remember { mutableStateOf<String?>(null) }
    val publicKeyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val keyId = publicKeyTarget
        publicKeyTarget = null
        if (uri == null || keyId == null) return@rememberLauncherForActivityResult
        scope.launch {
            viewModel.importPublicKey(keyId, uri)
                .onSuccess { identityError = null }
                .onFailure { identityError = it.message }
        }
    }
    val oauthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            viewModel.importOAuthClientJson(uri)
                .onSuccess { client ->
                    oauthError = null
                    clientEditor = client
                }
                .onFailure { oauthError = it.message }
        }
    }

    val pager = rememberPagerState(pageCount = { 2 })
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            RemoteMachinesTopBar(
                title = stringResource(R.string.remote_ssh_google_oauth),
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        ) {
            AppSwipeTabs(
                tabs = listOf(
                    stringResource(R.string.remote_server_ssh_keys),
                    stringResource(R.string.remote_server_google_oauth)
                ),
                pagerState = pager,
                modifier = Modifier.fillMaxSize(),
                tabRowModifier = Modifier.padding(horizontal = 16.dp),
                tabTag = { if (it == 0) "remote-settings-ssh-tab" else "remote-settings-oauth-tab" },
                spacing = 12.dp
            ) { page ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (page) {
                        0 -> {
                            SettingsCard {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    IconButton(onClick = {
                                        identityLauncher.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
                                    }) {
                                        SharedIcon(Icons.Outlined.UploadFile, stringResource(R.string.remote_server_upload_ssh_key))
                                    }
                                }
                                identityError?.let { RemoteErrorText(it) }
                                if (state.identities.isEmpty()) {
                                    Text(
                                        stringResource(R.string.remote_server_no_uploaded_keys),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    state.identities.forEach { key ->
                                        val users = state.machines.count { it.identityFile == key.path || it.identityFile.substringAfterLast('/') == key.name }
                                        RemoteListRow(
                                            title = key.name,
                                            subtitle = buildString {
                                                append(key.path)
                                                append("\n")
                                                append(
                                                    localizedResources.getString(
                                                        if (key.publicKeyPath.isNotBlank()) R.string.remote_server_public_key_uploaded
                                                        else R.string.remote_server_public_key_missing
                                                    )
                                                )
                                                if (users > 0) {
                                                    append("\n")
                                                    append(localizedResources.getString(R.string.remote_server_ssh_key_used_by, users))
                                                }
                                            },
                                            subtitleMaxLines = 3,
                                            leading = { RemoteIconTile(Icons.Outlined.Key) },
                                            trailing = {
                                                IconButton(onClick = {
                                                    publicKeyTarget = key.id
                                                    publicKeyLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                                                }) {
                                                    SharedIcon(Icons.Outlined.UploadFile, stringResource(R.string.remote_server_upload_public_key))
                                                }
                                                IconButton(enabled = users == 0, onClick = { viewModel.deleteIdentityKey(key.id) }) {
                                                    SharedIcon(
                                                        Icons.Outlined.Delete,
                                                        stringResource(R.string.common_delete),
                                                        tint = if (users == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        1 -> {
                            SettingsCard {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    IconButton(onClick = { oauthLauncher.launch(arrayOf("application/json", "text/json", "*/*")) }) {
                                        SharedIcon(Icons.Outlined.UploadFile, stringResource(R.string.remote_server_import_oauth_json))
                                    }
                                    IconButton(onClick = { clientEditor = viewModel.newOAuthClient() }) {
                                        SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
                                    }
                                }
                                oauthError?.let { RemoteErrorText(it) }
                                if (state.oauthClients.isEmpty()) {
                                    Text(
                                        stringResource(R.string.remote_server_no_oauth_clients),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    state.oauthClients.forEach { client ->
                                        val type = stringResource(
                                            if (client.type == GoogleOAuthClientType.ANDROID) R.string.remote_server_oauth_type_android
                                            else R.string.remote_server_oauth_type_desktop
                                        )
                                        RemoteListRow(
                                            title = client.name,
                                            subtitle = "$type · ${client.clientId}",
                                            leading = { RemoteIconTile(Icons.Outlined.Key) },
                                            trailing = {
                                                IconButton(onClick = { clientEditor = client }) {
                                                    SharedIcon(Icons.Outlined.Edit, stringResource(R.string.common_edit))
                                                }
                                                IconButton(onClick = { viewModel.deleteOAuthClient(client.id) }) {
                                                    SharedIcon(
                                                        Icons.Outlined.Delete,
                                                        stringResource(R.string.common_delete),
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    clientEditor?.let { initial ->
        OAuthClientDialog(
            initial = initial,
            onDismiss = { clientEditor = null },
            onSave = { client ->
                viewModel.saveOAuthClient(client)
                clientEditor = null
            }
        )
    }
}
