@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.starfall.generative.features.machines.details.ui

import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.LoadingIndicator

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.features.machines.shared.ui.RemoteDropdownField
import org.starfall.generative.features.machines.shared.ui.RemoteErrorText
import org.starfall.generative.features.machines.shared.ui.RemoteIconTile
import org.starfall.generative.features.machines.shared.ui.RemoteListRow
import org.starfall.generative.features.machines.shared.ui.RemoteMachinesTopBar
import org.starfall.generative.features.machines.shared.ui.RemoteSection
import org.starfall.generative.features.machines.shared.ui.RemoteTextField
import org.starfall.generative.features.machines.shared.ui.RemoteToggle
import org.starfall.generative.features.machines.shared.ui.remoteLiteralKeyboardOptions
import org.starfall.generative.integrations.remote.GCloudIdentityAuthorization
import org.starfall.generative.integrations.remote.GCloudIdentityOAuth
import org.starfall.generative.shared.model.GCloudOAuthClient
import org.starfall.generative.shared.model.GoogleOAuthClientType
import org.starfall.generative.shared.model.REMOTE_SFTP_HOME
import org.starfall.generative.shared.model.RemoteAuthentication
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineState
import org.starfall.generative.shared.model.RemoteMachineType
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MachineDetailsScreen(
    initial: RemoteMachine,
    state: RemoteMachineState,
    onSave: (RemoteMachine) -> Unit,
    onBack: () -> Unit,
    onRefreshIp: suspend (RemoteMachine) -> Result<RemoteMachine>,
    onFetchUsername: suspend (RemoteMachine) -> Result<String>,
    onAuthorizeOAuth: suspend (GCloudOAuthClient) -> Result<Unit>,
    onStoreAndroidOAuthToken: suspend (GCloudOAuthClient, String) -> Result<Unit>
) {
    BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onBack)
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var type by remember(initial.id) { mutableStateOf(initial.type) }
    var host by remember(initial.id) { mutableStateOf(initial.host) }
    var port by remember(initial.id) { mutableStateOf(initial.port.toString()) }
    var username by remember(initial.id) { mutableStateOf(initial.username) }
    var auth by remember(initial.id) { mutableStateOf(initial.authentication) }
    var password by remember(initial.id) { mutableStateOf(initial.password) }
    var showPassword by remember(initial.id) { mutableStateOf(false) }
    var keyboardInteractive by remember(initial.id) { mutableStateOf(initial.keyboardInteractive) }
    var identityFile by remember(initial.id) { mutableStateOf(initial.identityFile) }
    var passphrase by remember(initial.id) { mutableStateOf(initial.identityPassphrase) }
    var websocketProxy by remember(initial.id) { mutableStateOf(initial.websocketProxy) }
    var proxyMachineId by remember(initial.id) { mutableStateOf(initial.proxyMachineId) }
    var sshOptions by remember(initial.id) { mutableStateOf(initial.sshOptions) }
    var strictHostKey by remember(initial.id) { mutableStateOf(initial.strictHostKeyChecking) }
    var compression by remember(initial.id) { mutableStateOf(initial.compression) }
    var keepAlive by remember(initial.id) { mutableStateOf(initial.keepAliveSeconds.toString()) }
    var timeout by remember(initial.id) { mutableStateOf(initial.connectTimeoutSeconds.toString()) }
    var oauthClientId by remember(initial.id) { mutableStateOf(initial.oauthClientId) }
    var environment by remember(initial.id) { mutableStateOf(initial.environmentName) }
    var sftpDirectory by remember(initial.id) { mutableStateOf(initial.sftpDirectory.ifBlank { REMOTE_SFTP_HOME }) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameRequiredError = stringResource(R.string.remote_server_name_required)
    val hostRequiredError = stringResource(R.string.remote_server_host_required)
    val identityRequiredError = stringResource(R.string.remote_server_identity_required)
    var websocketDialog by remember { mutableStateOf(false) }
    var loadingIp by remember { mutableStateOf(false) }
    var authorizing by remember { mutableStateOf(false) }
    var authorizeJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val identityOAuth = remember(context) { GCloudIdentityOAuth(context) }
    var pendingAndroidOAuthClient by remember { mutableStateOf<GCloudOAuthClient?>(null) }
    val androidOAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val client = pendingAndroidOAuthClient
        pendingAndroidOAuthClient = null
        if (client != null && result.resultCode == Activity.RESULT_OK) {
            runCatching { identityOAuth.accessTokenFromIntent(result.data) }
                .onSuccess { accessToken ->
                    if (accessToken.isNullOrBlank()) {
                        error = localizedResources.getString(R.string.remote_server_google_no_access_token)
                        authorizing = false
                    } else scope.launch {
                        onStoreAndroidOAuthToken(client, accessToken).onFailure { error = it.message }
                        authorizing = false
                    }
                }
                .onFailure {
                    error = it.message
                    authorizing = false
                }
        } else {
            authorizing = false
        }
    }
    val oauthAuthorized = state.oauthTokens.containsKey(oauthClientId)
    var fetchingUsername by remember { mutableStateOf(false) }
    // Cloud Shell's SSH user comes from the Google API: fetch it whenever the account/environment changes.
    LaunchedEffect(type, oauthClientId, oauthAuthorized, environment) {
        if (type != RemoteMachineType.GCLOUD_SHELL || oauthClientId.isBlank() || !oauthAuthorized) return@LaunchedEffect
        delay(400) // debounce typing in the environment field
        fetchingUsername = true
        onFetchUsername(initial.copy(type = type, environmentName = environment, oauthClientId = oauthClientId))
            .onSuccess { username = it }
        fetchingUsername = false
    }

    fun save() {
        val machine = initial.copy(
            name = name,
            type = type,
            host = host,
            port = port.toIntOrNull() ?: 22,
            username = username,
            authentication = auth,
            password = password,
            keyboardInteractive = keyboardInteractive,
            identityFile = identityFile,
            identityPassphrase = passphrase,
            websocketProxy = websocketProxy,
            proxyMachineId = proxyMachineId,
            sshOptions = sshOptions.filterKeys { it.isNotBlank() }.filterValues { it.isNotBlank() },
            strictHostKeyChecking = strictHostKey,
            compression = compression,
            keepAliveSeconds = keepAlive.toIntOrNull() ?: 30,
            connectTimeoutSeconds = timeout.toIntOrNull() ?: 15,
            oauthClientId = oauthClientId,
            environmentName = environment,
            sftpDirectory = sftpDirectory.trim().ifBlank { REMOTE_SFTP_HOME },
            gcloudUsername = if (type == RemoteMachineType.GCLOUD_SHELL) username else initial.gcloudUsername
        )
        runCatching {
            require(name.isNotBlank()) { nameRequiredError }
            require(type != RemoteMachineType.SSH || host.isNotBlank()) { hostRequiredError }
            require(type != RemoteMachineType.SSH || auth != RemoteAuthentication.PRIVATE_KEY || identityFile.isNotBlank()) {
                identityRequiredError
            }
        }.onSuccess { onSave(machine) }.onFailure { error = it.message }
    }

    Scaffold(

        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            RemoteMachinesTopBar(
                title = stringResource(R.string.remote_server_configuration),
                onBack = onBack,
                actions = {
                    Button(onClick = ::save, modifier = Modifier.padding(end = 8.dp)) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RemoteSection(stringResource(R.string.remote_server_basic_section)) {
                RemoteTextField(name, { name = it }, stringResource(R.string.common_name))
                RemoteDropdownField(
                    label = stringResource(R.string.remote_server_type_label),
                    options = listOf(
                        RemoteMachineType.SSH to stringResource(R.string.remote_server_type_ssh),
                        RemoteMachineType.GCLOUD_SHELL to stringResource(R.string.remote_server_type_gcloud_shell)
                    ),
                    selected = type,
                    onSelect = { type = it }
                )
            }
            RemoteSection(stringResource(R.string.remote_server_connection_section)) {
                if (type == RemoteMachineType.SSH) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RemoteTextField(
                            host, { host = it }, stringResource(R.string.remote_server_host), Modifier.weight(1f),
                            keyboardOptions = remoteLiteralKeyboardOptions()
                        )
                        RemoteTextField(
                            port, { if (it.all(Char::isDigit)) port = it }, stringResource(R.string.remote_server_port), Modifier.width(100.dp),
                            keyboardOptions = remoteLiteralKeyboardOptions(KeyboardType.Number)
                        )
                    }
                } else {
                    RemoteTextField(environment, { environment = it }, stringResource(R.string.remote_server_environment), keyboardOptions = remoteLiteralKeyboardOptions())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteTextField(host, {}, stringResource(R.string.remote_server_ip), Modifier.weight(1f), readOnly = true, keyboardOptions = remoteLiteralKeyboardOptions())
                        OutlinedButton(enabled = !loadingIp, onClick = {
                            loadingIp = true
                            scope.launch {
                                onRefreshIp(
                                    initial.copy(
                                        name = name,
                                        type = type,
                                        environmentName = environment,
                                        username = username,
                                        gcloudUsername = username,
                                        authentication = auth,
                                        password = password,
                                        identityFile = identityFile,
                                        identityPassphrase = passphrase,
                                        oauthClientId = oauthClientId
                                    )
                                )
                                    .onSuccess { updated -> host = updated.host; port = updated.port.toString(); username = updated.username }
                                    .onFailure { error = it.message }
                                loadingIp = false
                            }
                        }) {
                            if (loadingIp) LoadingIndicator(Modifier.size(16.dp))
                            else SharedIcon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.remote_server_get_ip))
                        }
                    }
                }
                if (type == RemoteMachineType.GCLOUD_SHELL) {
                    // Cloud Shell reports its own SSH user, so it is filled in when the endpoint is loaded.
                    RemoteTextField(
                        username, {}, stringResource(R.string.remote_server_username),
                        readOnly = true,
                        keyboardOptions = remoteLiteralKeyboardOptions(),
                        trailingIcon = if (fetchingUsername) {
                            { LoadingIndicator(Modifier.size(18.dp)) }
                        } else null
                    )
                } else {
                    RemoteTextField(username, { username = it }, stringResource(R.string.remote_server_username), keyboardOptions = remoteLiteralKeyboardOptions())
                }
                RemoteDropdownField(
                    label = stringResource(R.string.remote_server_authentication),
                    options = listOf(
                        RemoteAuthentication.PRIVATE_KEY to stringResource(R.string.remote_server_private_key),
                        RemoteAuthentication.PASSWORD to stringResource(R.string.remote_server_password)
                    ),
                    selected = auth,
                    onSelect = { auth = it }
                )
                if (auth == RemoteAuthentication.PASSWORD) {
                    RemoteTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.remote_server_password),
                        visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = remoteLiteralKeyboardOptions(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                SharedIcon(
                                    if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = stringResource(
                                        if (showPassword) R.string.remote_server_hide_password else R.string.remote_server_show_password
                                    )
                                )
                            }
                        }
                    )
                    RemoteToggle(
                        label = stringResource(R.string.remote_server_keyboard_interactive),
                        checked = keyboardInteractive,
                        onCheckedChange = { keyboardInteractive = it }
                    )
                } else {
                    RemoteTextField(
                        identityFile,
                        { identityFile = it },
                        stringResource(R.string.remote_server_identity_file),
                        keyboardOptions = remoteLiteralKeyboardOptions()
                    )
                    var keyMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { keyMenu = true }) {
                            SharedIcon(Icons.Outlined.Key, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.remote_server_choose_key))
                        }
                        DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                            if (state.identities.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remote_server_no_uploaded_keys)) },
                                    onClick = { keyMenu = false },
                                    enabled = false
                                )
                            }
                            state.identities.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key.name) },
                                    onClick = { identityFile = key.path; keyMenu = false }
                                )
                            }
                        }
                    }
                    RemoteTextField(
                        passphrase, { passphrase = it }, stringResource(R.string.remote_server_key_passphrase),
                        visual = PasswordVisualTransformation(),
                        keyboardOptions = remoteLiteralKeyboardOptions()
                    )
                }
                RemoteListRow(
                    title = stringResource(R.string.remote_server_websocket_proxy),
                    subtitle = websocketProxy?.url?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.remote_server_not_configured),
                    leading = { RemoteIconTile(Icons.Outlined.Language) },
                    trailing = {
                        if (websocketProxy != null) {
                            IconButton(onClick = { websocketProxy = null }) {
                                SharedIcon(Icons.Default.Close, stringResource(R.string.common_remove))
                            }
                        }
                        IconButton(onClick = { websocketDialog = true }) {
                            SharedIcon(Icons.Outlined.Edit, stringResource(R.string.common_edit))
                        }
                    }
                )
                if (type == RemoteMachineType.SSH) {
                    RemoteDropdownField(
                        label = stringResource(R.string.remote_server_proxy_server),
                        options = listOf("" to stringResource(R.string.common_none)) + state.machines
                            .filter { it.id != initial.id }
                            .map { it.id to it.name },
                        selected = proxyMachineId,
                        onSelect = { proxyMachineId = it; if (it.isNotBlank()) websocketProxy = null }
                    )
                }
            }
            RemoteSection(stringResource(R.string.remote_server_sftp_section)) {
                RemoteTextField(
                    value = sftpDirectory,
                    onValueChange = { sftpDirectory = it },
                    label = stringResource(R.string.remote_server_sftp_directory),
                    keyboardOptions = remoteLiteralKeyboardOptions()
                )
            }
            RemoteSection(stringResource(R.string.remote_server_ssh_options)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RemoteTextField(
                        timeout, { if (it.all(Char::isDigit)) timeout = it }, stringResource(R.string.remote_server_timeout), Modifier.weight(1f),
                        keyboardOptions = remoteLiteralKeyboardOptions(KeyboardType.Number)
                    )
                    RemoteTextField(
                        keepAlive, { if (it.all(Char::isDigit)) keepAlive = it }, stringResource(R.string.remote_server_keep_alive), Modifier.weight(1f),
                        keyboardOptions = remoteLiteralKeyboardOptions(KeyboardType.Number)
                    )
                }
                RemoteToggle(stringResource(R.string.remote_server_strict_host_key), strictHostKey) { strictHostKey = it }
                RemoteToggle(stringResource(R.string.remote_server_compression), compression) { compression = it }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    stringResource(R.string.remote_server_advanced_ssh_options),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SshOptionsEditor(options = sshOptions, onChange = { sshOptions = it })
            }
            if (type == RemoteMachineType.GCLOUD_SHELL) {
                RemoteSection(stringResource(R.string.remote_google_cloud_oauth)) {
                    RemoteDropdownField(
                        label = stringResource(R.string.remote_server_oauth_client),
                        options = listOf("" to stringResource(R.string.remote_server_select_oauth)) +
                            state.oauthClients.map { it.id to it.name },
                        selected = oauthClientId,
                        onSelect = { oauthClientId = it }
                    )
                    val selectedOAuthClient = state.oauthClients.firstOrNull { it.id == oauthClientId }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            enabled = selectedOAuthClient != null && !authorizing,
                            onClick = {
                                val client = selectedOAuthClient ?: return@OutlinedButton
                                authorizing = true
                                if (client.type == GoogleOAuthClientType.ANDROID) {
                                    identityOAuth.authorize { result ->
                                        result.fold(
                                            onSuccess = { authorization ->
                                                when (authorization) {
                                                    is GCloudIdentityAuthorization.NeedsResolution -> {
                                                        pendingAndroidOAuthClient = client
                                                        androidOAuthLauncher.launch(
                                                            IntentSenderRequest.Builder(authorization.pendingIntent.intentSender).build()
                                                        )
                                                    }
                                                    is GCloudIdentityAuthorization.AccessToken -> scope.launch {
                                                        onStoreAndroidOAuthToken(client, authorization.value)
                                                            .onFailure { error = it.message }
                                                        authorizing = false
                                                    }
                                                    GCloudIdentityAuthorization.MissingResolution -> {
                                                        error = localizedResources.getString(R.string.remote_server_google_missing_resolution)
                                                        authorizing = false
                                                    }
                                                    GCloudIdentityAuthorization.MissingAccessToken -> {
                                                        error = localizedResources.getString(R.string.remote_server_google_no_access_token)
                                                        authorizing = false
                                                    }
                                                }
                                            },
                                            onFailure = { exception ->
                                                error = exception.message
                                                authorizing = false
                                            }
                                        )
                                    }
                                } else {
                                    authorizeJob = scope.launch {
                                        try {
                                            onAuthorizeOAuth(client).onFailure { error = it.message }
                                        } finally {
                                            authorizing = false
                                            authorizeJob = null
                                        }
                                    }
                                }
                            }
                        ) {
                            if (authorizing) LoadingIndicator(Modifier.size(16.dp))
                            else SharedIcon(Icons.Outlined.Key, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.remote_server_oauth_sign_in))
                        }
                        if (authorizing) {
                            // Cancelling stops the loopback listener and closes its port.
                            TextButton(onClick = { authorizeJob?.cancel() }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                        if (oauthAuthorized) {
                            Text(
                                stringResource(R.string.remote_server_oauth_authorized),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            error?.let { RemoteErrorText(it) }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (websocketDialog) {
        WebSocketProxyDialog(
            initial = websocketProxy,
            onDismiss = { websocketDialog = false },
            onSave = { websocketProxy = it; if (it != null) proxyMachineId = ""; websocketDialog = false }
        )
    }
}
