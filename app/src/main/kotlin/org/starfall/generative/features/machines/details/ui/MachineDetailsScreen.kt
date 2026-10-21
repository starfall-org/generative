@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.starfall.generative.features.machines.details.ui

import android.app.Activity
import android.content.ClipData
import android.net.Uri
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.ByteArrayOutputStream
import org.starfall.generative.R
import org.starfall.generative.integrations.remote.ExecutableBinary
import org.starfall.generative.features.machines.shared.ui.RemoteDropdownField
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.features.machines.shared.ui.RemoteSection
import org.starfall.generative.features.machines.shared.ui.RemoteTextField
import org.starfall.generative.features.machines.shared.ui.RemoteToggle
import org.starfall.generative.integrations.remote.endpoint
import org.starfall.generative.shared.model.GCloudOAuthClient
import org.starfall.generative.shared.model.GoogleOAuthClientType
import org.starfall.generative.shared.model.REMOTE_TOOL_DISCONNECT
import org.starfall.generative.shared.model.REMOTE_TOOL_START_MACHINE
import org.starfall.generative.shared.model.REMOTE_TOOL_UPDATE_IP
import org.starfall.generative.shared.model.RemoteAuthentication
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineState
import org.starfall.generative.shared.model.RemoteMachineType
import org.starfall.generative.shared.model.remoteMachineToolNames
import org.starfall.generative.shared.ui.dialogs.AppAlertDialog
import org.starfall.generative.shared.ui.icons.FluentIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.text.SelectableOutlinedTextField
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.layout.SlideScreenContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MachineDetailsScreen(
    initial: RemoteMachine,
    isNew: Boolean,
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
    var proxyCommand by remember(initial.id) { mutableStateOf(initial.proxyCommand) }
    var strictHostKey by remember(initial.id) { mutableStateOf(initial.strictHostKeyChecking) }
    var compression by remember(initial.id) { mutableStateOf(initial.compression) }
    var keepAlive by remember(initial.id) { mutableStateOf(initial.keepAliveSeconds.toString()) }
    var timeout by remember(initial.id) { mutableStateOf(initial.connectTimeoutSeconds.toString()) }
    var oauthClientId by remember(initial.id) { mutableStateOf(initial.oauthClientId) }
    var environment by remember(initial.id) { mutableStateOf(initial.environmentName) }
    var aiTools by remember(initial.id) {
        mutableStateOf(
            initial.aiTools.ifEmpty {
                if (initial.aiToolsEnabled) emptyMap()
                else initial.supportedToolNames().associateWith { false }
            }
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var loadingIp by remember { mutableStateOf(false) }
    var authorizing by remember { mutableStateOf(false) }
    var authorizeJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingAndroidOAuthClient by remember { mutableStateOf<GCloudOAuthClient?>(null) }
    val androidOAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val client = pendingAndroidOAuthClient
        pendingAndroidOAuthClient = null
        if (client != null && result.resultCode == Activity.RESULT_OK) {
            runCatching {
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data)
            }.onSuccess { authResult ->
                val accessToken = authResult.accessToken
                if (accessToken.isNullOrBlank()) {
                    error = context.getString(R.string.remote_machine_google_no_access_token)
                    authorizing = false
                }
                else scope.launch {
                    onStoreAndroidOAuthToken(client, accessToken).onFailure { error = it.message }
                    authorizing = false
                }
            }.onFailure {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.remote_machine_add_title else R.string.remote_machine_edit_title)) },
                navigationIcon = { IconButton(onClick = onBack) { FluentIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = { TextButton(onClick = {
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
                        proxyCommand = proxyCommand,
                        strictHostKeyChecking = strictHostKey,
                        compression = compression,
                        keepAliveSeconds = keepAlive.toIntOrNull() ?: 30,
                        connectTimeoutSeconds = timeout.toIntOrNull() ?: 15,
                        oauthClientId = oauthClientId,
                        environmentName = environment,
                        gcloudUsername = if (type == RemoteMachineType.GCLOUD_SHELL) username else initial.gcloudUsername,
                        aiToolsEnabled = true,
                        aiTools = aiTools
                    )
                    runCatching {
                        require(name.isNotBlank()) { "Machine name is required." }
                        require(type != RemoteMachineType.SSH || host.isNotBlank()) { "Host is required for SSH machines." }
                        require(type != RemoteMachineType.SSH || auth != RemoteAuthentication.PRIVATE_KEY || identityFile.isNotBlank()) {
                            "IdentityFile is required for private-key authentication."
                        }
                    }.onSuccess { onSave(machine) }.onFailure { error = it.message }
                }) { Text(stringResource(R.string.common_save)) } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            RemoteSection(stringResource(R.string.remote_machine_basic_section)) {
                RemoteTextField(name, { name = it }, stringResource(R.string.common_name))
                RemoteDropdownField(
                    label = stringResource(R.string.remote_machine_type_label),
                    options = listOf(
                        RemoteMachineType.SSH to stringResource(R.string.remote_machine_type_ssh),
                        RemoteMachineType.GCLOUD_SHELL to stringResource(R.string.remote_machine_type_gcloud)
                    ),
                    selected = type,
                    onSelect = { type = it }
                )
            }
            RemoteSection(stringResource(R.string.remote_machine_connection_section)) {
                if (type == RemoteMachineType.SSH) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RemoteTextField(host, { host = it }, stringResource(R.string.remote_machine_host), Modifier.weight(1f))
                        RemoteTextField(port, { if (it.all(Char::isDigit)) port = it }, stringResource(R.string.remote_machine_port), Modifier.width(100.dp))
                    }
                } else {
                    RemoteTextField(environment, { environment = it }, stringResource(R.string.remote_machine_environment))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteTextField(host, {}, stringResource(R.string.remote_machine_ip), Modifier.weight(1f), readOnly = true)
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
                            if (loadingIp) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else FluentIcon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.remote_machine_get_ip))
                        }
                    }
                }
                if (type == RemoteMachineType.GCLOUD_SHELL) {
                    // Cloud Shell reports its own SSH user, so it is filled in when the endpoint is loaded.
                    RemoteTextField(
                        username, {}, stringResource(R.string.remote_machine_username),
                        readOnly = true,
                        supporting = stringResource(R.string.remote_machine_username_auto),
                        trailingIcon = if (fetchingUsername) {
                            { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                        } else null
                    )
                } else {
                    RemoteTextField(username, { username = it }, stringResource(R.string.remote_machine_username))
                }
                RemoteDropdownField(
                    label = stringResource(R.string.remote_machine_authentication),
                    options = listOf(
                        RemoteAuthentication.PRIVATE_KEY to stringResource(R.string.remote_machine_private_key),
                        RemoteAuthentication.PASSWORD to stringResource(R.string.remote_machine_password)
                    ),
                    selected = auth,
                    onSelect = { auth = it }
                )
                if (auth == RemoteAuthentication.PASSWORD) {
                    RemoteTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.remote_machine_password),
                        supporting = stringResource(R.string.remote_machine_password_help),
                        visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                FluentIcon(
                                    if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = stringResource(
                                        if (showPassword) R.string.remote_machine_hide_password else R.string.remote_machine_show_password
                                    )
                                )
                            }
                        }
                    )
                    RemoteToggle(
                        stringResource(R.string.remote_machine_keyboard_interactive),
                        keyboardInteractive
                    ) { keyboardInteractive = it }
                    Text(
                        stringResource(R.string.remote_machine_keyboard_interactive_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    RemoteTextField(
                        identityFile,
                        { identityFile = it },
                        stringResource(R.string.remote_machine_identity_file),
                        supporting = stringResource(R.string.remote_machine_identity_file_help)
                    )
                    var keyMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { keyMenu = true }) {
                            FluentIcon(Icons.Outlined.Key, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.remote_machine_choose_key))
                        }
                        DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                            if (state.identities.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remote_machine_no_uploaded_keys)) },
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
                    RemoteTextField(passphrase, { passphrase = it }, stringResource(R.string.remote_machine_key_passphrase), visual = PasswordVisualTransformation())
                }
            }
            RemoteSection(stringResource(R.string.remote_machine_ssh_options)) {
                RemoteTextField(proxyCommand, { proxyCommand = it }, stringResource(R.string.remote_machine_proxy_command), supporting = stringResource(R.string.remote_machine_proxy_command_help))
                RemoteTextField(timeout, { if (it.all(Char::isDigit)) timeout = it }, stringResource(R.string.remote_machine_timeout))
                RemoteTextField(keepAlive, { if (it.all(Char::isDigit)) keepAlive = it }, stringResource(R.string.remote_machine_keep_alive))
                RemoteToggle(stringResource(R.string.remote_machine_strict_host_key), strictHostKey) { strictHostKey = it }
                RemoteToggle(stringResource(R.string.remote_machine_compression), compression) { compression = it }
            }
            if (type == RemoteMachineType.GCLOUD_SHELL) {
                RemoteSection(stringResource(R.string.remote_machine_google_section)) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                        SelectableOutlinedTextField(
                            value = state.oauthClients.firstOrNull { it.id == oauthClientId }?.name
                                ?: stringResource(R.string.remote_machine_select_oauth),
                            onValueChange = {}, readOnly = true,
                            label = { Text(stringResource(R.string.remote_machine_oauth_client)) },
                            trailingIcon = { TrailingIcon(expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                        )
                        ExposedDropdownMenu(expanded, { expanded = false }) {
                            state.oauthClients.forEach { client ->
                                DropdownMenuItem(text = { Text(client.name) }, onClick = { oauthClientId = client.id; expanded = false })
                            }
                        }
                    }
                    if (state.oauthClients.isEmpty()) Text(stringResource(R.string.remote_machine_add_oauth_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    val selectedOAuthClient = state.oauthClients.firstOrNull { it.id == oauthClientId }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            enabled = selectedOAuthClient != null && !authorizing,
                            onClick = {
                                val client = selectedOAuthClient ?: return@OutlinedButton
                                authorizing = true
                                if (client.type == GoogleOAuthClientType.ANDROID) {
                                    val request = AuthorizationRequest.builder()
                                        .setRequestedScopes(listOf(Scope(RemoteMachineService.GOOGLE_SCOPE)))
                                        .build()
                                    Identity.getAuthorizationClient(context).authorize(request)
                                        .addOnSuccessListener { result ->
                                            if (result.hasResolution()) {
                                                val pendingIntent = result.pendingIntent
                                                if (pendingIntent == null) {
                                                    error = context.getString(R.string.remote_machine_google_missing_resolution)
                                                    authorizing = false
                                                } else {
                                                    pendingAndroidOAuthClient = client
                                                    androidOAuthLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                                                }
                                            } else {
                                                val accessToken = result.accessToken
                                                if (accessToken.isNullOrBlank()) {
                                                    error = context.getString(R.string.remote_machine_google_no_access_token)
                                                    authorizing = false
                                                } else scope.launch {
                                                    onStoreAndroidOAuthToken(client, accessToken).onFailure { error = it.message }
                                                    authorizing = false
                                                }
                                            }
                                        }
                                        .addOnFailureListener { exception ->
                                            error = exception.message
                                            authorizing = false
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
                            if (authorizing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else FluentIcon(Icons.Outlined.Key, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.remote_machine_oauth_sign_in))
                        }
                        if (authorizing) {
                            // Cancelling stops the loopback listener and closes its port.
                            TextButton(onClick = { authorizeJob?.cancel() }) {
                                Text(stringResource(R.string.remote_machine_oauth_cancel))
                            }
                        }
                        if (state.oauthTokens.containsKey(oauthClientId)) {
                            Text(
                                stringResource(R.string.remote_machine_oauth_authorized),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            RemoteSection(stringResource(R.string.remote_machine_tools_section)) {
                Text(
                    stringResource(R.string.remote_machine_tools_choose),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                remoteMachineToolNames(type).forEach { toolName ->
                    RemoteToggle(
                        label = stringResource(
                            when (toolName) {
                                REMOTE_TOOL_UPDATE_IP -> R.string.remote_machine_update_ip
                                REMOTE_TOOL_DISCONNECT -> R.string.remote_machine_tool_disconnect
                                else -> R.string.remote_machine_tool_execute
                            }
                        ),
                        checked = aiTools[toolName]
                            ?: if (toolName == REMOTE_TOOL_UPDATE_IP) aiTools[REMOTE_TOOL_START_MACHINE] != false else true
                    ) { enabled ->
                        aiTools = aiTools + (toolName to enabled)
                    }
                    if (toolName == REMOTE_TOOL_UPDATE_IP) {
                        Text(
                            stringResource(R.string.remote_machine_tool_update_ip_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
