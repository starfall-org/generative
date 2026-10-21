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
import org.starfall.generative.features.machines.shared.ui.BinaryTestNotice
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.integrations.remote.ExecutableBinary
import org.starfall.generative.features.machines.shared.ui.RemoteSection
import org.starfall.generative.features.machines.shared.ui.RemoteTextField
import org.starfall.generative.integrations.remote.SshConfigParser
import org.starfall.generative.integrations.remote.binaryLauncher
import org.starfall.generative.integrations.remote.remoteMachineSshConfigFile
import org.starfall.generative.shared.model.GCloudOAuthClient
import org.starfall.generative.shared.model.GoogleOAuthClientType
import org.starfall.generative.shared.model.RemoteMachineState
import org.starfall.generative.shared.ui.dialogs.AppAlertDialog
import org.starfall.generative.shared.ui.icons.FluentIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.text.SelectableOutlinedTextField
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.layout.SlideScreenContent

@Composable
internal fun RemoteMachineSettings(
    state: RemoteMachineState,
    binaries: List<ExecutableBinary>,
    viewModel: RemoteMachinesViewModel,
    onBack: () -> Unit
) {
    BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onBack)
    var sshConfig by remember(state.sshConfig) { mutableStateOf(state.sshConfig) }
    var clientEditor by remember { mutableStateOf<GCloudOAuthClient?>(null) }
    var binaryError by remember { mutableStateOf<String?>(null) }
    var pendingBinaryUri by remember { mutableStateOf<Uri?>(null) }
    var testingBinaryId by remember { mutableStateOf<String?>(null) }
    var binaryToTest by remember { mutableStateOf<ExecutableBinary?>(null) }
    var binaryTestArguments by remember { mutableStateOf("") }
    var binaryTestNotice by remember { mutableStateOf<BinaryTestNotice?>(null) }
    var identityError by remember { mutableStateOf<String?>(null) }
    var oauthError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sshConfigPath = remember(context) { remoteMachineSshConfigFile(context).absolutePath }
    val configEditorFocusRequester = remember { FocusRequester() }
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
    val binaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingBinaryUri = uri
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_machine_settings)) },
                navigationIcon = { IconButton(onClick = onBack) { FluentIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = { TextButton(onClick = { viewModel.saveSshConfig(sshConfig); onBack() }) { Text(stringResource(R.string.common_save)) } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteSection(stringResource(R.string.remote_machine_ssh_config)) {
                Text(stringResource(R.string.remote_machine_ssh_config_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RemoteTextField(
                    value = sshConfigPath,
                    onValueChange = {},
                    label = stringResource(R.string.remote_machine_ssh_config_path),
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            configEditorFocusRequester.requestFocus()
                        }) {
                            FluentIcon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit))
                        }
                    }
                )
                RemoteTextField(
                    value = sshConfig,
                    onValueChange = { sshConfig = it },
                    label = stringResource(R.string.remote_machine_ssh_config),
                    modifier = Modifier.focusRequester(configEditorFocusRequester),
                    minLines = 10,
                    maxLines = 20,
                    font = FontFamily.Monospace,
                    visual = SshConfigVisualTransformation(
                        SshHighlightColors(
                            comment = MaterialTheme.colorScheme.outline,
                            keyword = MaterialTheme.colorScheme.primary,
                            value = MaterialTheme.colorScheme.onSurface
                        )
                    )
                )
                TextButton(onClick = {
                    val aliases = SshConfigParser.parse(sshConfig)
                        .flatMap { it.aliases }
                        .filterNot { it.startsWith('!') || '*' in it || '?' in it }
                        .distinct()
                    if (aliases.isNotEmpty()) {
                        // Import only if aliases are not already represented; the full config stays editable above.
                        scope.launch {
                            aliases.mapNotNull { alias ->
                                SshConfigParser.find(sshConfig, alias)?.let { SshConfigParser.toMachine(it) }
                            }.forEach { machine ->
                                if (state.machines.none { it.name == machine.name }) viewModel.saveMachine(machine)
                            }
                        }
                    }
                }) { Text(stringResource(R.string.remote_machine_import_hosts)) }
            }
            RemoteSection(stringResource(R.string.remote_machine_oauth_clients)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { oauthLauncher.launch(arrayOf("application/json", "text/json", "*/*")) }) {
                        FluentIcon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.remote_machine_import_oauth_json))
                    }
                    OutlinedButton(onClick = { clientEditor = viewModel.newOAuthClient() }) {
                        FluentIcon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.remote_machine_add_oauth))
                    }
                }
                state.oauthClients.forEach { client ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FluentIcon(Icons.Outlined.Key, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(client.name, style = MaterialTheme.typography.titleSmall)
                            Text(client.clientId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { clientEditor = client }) { Text(stringResource(R.string.common_edit)) }
                        IconButton(onClick = { viewModel.deleteOAuthClient(client.id) }) { FluentIcon(Icons.Outlined.Lock, stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error) }
                    }
                }
                oauthError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            RemoteSection(stringResource(R.string.remote_machine_ssh_keys)) {
                Text(stringResource(R.string.remote_machine_ssh_keys_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.identities.forEach { key ->
                    val users = state.machines.count { it.identityFile == key.path || it.identityFile.substringAfterLast('/') == key.name }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FluentIcon(Icons.Outlined.Key, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(key.name, style = MaterialTheme.typography.titleSmall)
                            Text(key.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text(
                                stringResource(
                                    if (key.publicKeyPath.isNotBlank()) R.string.remote_machine_public_key_uploaded
                                    else R.string.remote_machine_public_key_missing
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (key.publicKeyPath.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (users > 0) Text(stringResource(R.string.remote_machine_ssh_key_used_by, users), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            publicKeyTarget = key.id
                            publicKeyLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                        }) {
                            FluentIcon(Icons.Outlined.UploadFile, stringResource(R.string.remote_machine_upload_public_key))
                        }
                        IconButton(enabled = users == 0, onClick = { viewModel.deleteIdentityKey(key.id) }) {
                            FluentIcon(Icons.Outlined.Lock, stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                OutlinedButton(onClick = { identityLauncher.launch(arrayOf("application/octet-stream", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    FluentIcon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.remote_machine_upload_ssh_key))
                }
                identityError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            RemoteSection(stringResource(R.string.remote_machine_binaries)) {
                Text(stringResource(R.string.remote_machine_binaries_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                binaries.forEach { binary ->
                    val users = state.machines.count { machine ->
                        listOf(machine.proxyCommand, machine.localCommand).any { command ->
                            Regex("(^|\\s)${Regex.escape(binary.name)}(?=\\s|$)").containsMatchIn(command)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FluentIcon(Icons.Outlined.Code, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(binary.name, style = MaterialTheme.typography.titleSmall)
                            Text(binary.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            if (users > 0) Text(stringResource(R.string.remote_machine_binary_used_by, users), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            enabled = testingBinaryId == null,
                            onClick = {
                                binaryToTest = binary
                                binaryTestArguments = ""
                            }
                        ) {
                            if (testingBinaryId == binary.id) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                FluentIcon(Icons.Outlined.PlayArrow, stringResource(R.string.remote_machine_test_binary))
                            }
                        }
                        IconButton(enabled = users == 0, onClick = { viewModel.deleteBinary(binary.id) }) {
                            FluentIcon(Icons.Outlined.Lock, stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                OutlinedButton(onClick = { binaryLauncher.launch(arrayOf("application/octet-stream", "application/x-executable", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    FluentIcon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.remote_machine_add_binary))
                }
                binaryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    binaryToTest?.let { binary ->
        AlertDialog(
            onDismissRequest = { if (testingBinaryId == null) binaryToTest = null },
            title = { Text(stringResource(R.string.remote_machine_binary_test_parameters_title, binary.name)) },
            text = {
                OutlinedTextField(
                    value = binaryTestArguments,
                    onValueChange = { binaryTestArguments = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.remote_machine_binary_test_arguments)) },
                    placeholder = { Text(stringResource(R.string.remote_machine_binary_test_arguments_hint)) },
                    supportingText = { Text(stringResource(R.string.remote_machine_binary_test_arguments_help)) },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    enabled = testingBinaryId == null
                )
            },
            confirmButton = {
                TextButton(
                    enabled = testingBinaryId == null,
                    onClick = {
                        val args = binaryTestArguments.trim().takeIf { it.isNotEmpty() }
                            ?.split(Regex("\\s+")) ?: emptyList()
                        testingBinaryId = binary.id
                        binaryToTest = null
                        scope.launch {
                            viewModel.testBinary(binary.id, args).fold(
                                onSuccess = { result ->
                                    val status = if (result.timedOut) {
                                        context.getString(R.string.remote_machine_binary_test_timeout)
                                    } else {
                                        context.getString(R.string.remote_machine_binary_test_exit, result.exitCode)
                                    }
                                    binaryTestNotice = BinaryTestNotice(
                                        binaryName = binary.name,
                                        status = status,
                                        output = result.output.ifBlank {
                                            context.getString(R.string.remote_machine_binary_test_no_output)
                                        }
                                    )
                                },
                                onFailure = { error ->
                                    binaryTestNotice = BinaryTestNotice(
                                        binaryName = binary.name,
                                        status = context.getString(R.string.remote_machine_binary_test_failed),
                                        output = error.message ?: error.toString()
                                    )
                                }
                            )
                            testingBinaryId = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.remote_machine_test_binary))
                }
            },
            dismissButton = {
                TextButton(enabled = testingBinaryId == null, onClick = { binaryToTest = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    pendingBinaryUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingBinaryUri = null },
            title = { Text(stringResource(R.string.remote_machine_binary_upload_warning_title)) },
            text = { Text(stringResource(R.string.remote_machine_binary_upload_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingBinaryUri = null
                    binaryError = null
                    scope.launch {
                        viewModel.importBinary(uri).onFailure { binaryError = it.message }
                    }
                }) {
                    Text(stringResource(R.string.remote_machine_binary_upload_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBinaryUri = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    binaryTestNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { binaryTestNotice = null },
            title = { Text(stringResource(R.string.remote_machine_binary_test_title, notice.binaryName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(notice.status, style = MaterialTheme.typography.labelMedium)
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            notice.output,
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { binaryTestNotice = null }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
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

@Composable
internal fun OAuthClientDialog(
    initial: GCloudOAuthClient,
    onDismiss: () -> Unit,
    onSave: (GCloudOAuthClient) -> Unit
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var clientId by remember(initial.id) { mutableStateOf(initial.clientId) }
    var secret by remember(initial.id) { mutableStateOf(initial.clientSecret) }
    var clientType by remember(initial.id) { mutableStateOf(initial.type) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_machine_oauth_client)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RemoteTextField(name, { name = it }, stringResource(R.string.common_name))
                RemoteTextField(clientId, { clientId = it }, stringResource(R.string.remote_machine_client_id))
                ExposedDropdownMenuBox(typeMenuExpanded, { typeMenuExpanded = !typeMenuExpanded }) {
                    SelectableOutlinedTextField(
                        value = stringResource(if (clientType == GoogleOAuthClientType.ANDROID) R.string.remote_machine_oauth_type_android else R.string.remote_machine_oauth_type_desktop),
                        onValueChange = {}, readOnly = true,
                        label = { Text(stringResource(R.string.remote_machine_oauth_client_type)) },
                        trailingIcon = { TrailingIcon(typeMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    )
                    ExposedDropdownMenu(typeMenuExpanded, { typeMenuExpanded = false }) {
                        GoogleOAuthClientType.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(stringResource(if (option == GoogleOAuthClientType.ANDROID) R.string.remote_machine_oauth_type_android else R.string.remote_machine_oauth_type_desktop)) }, onClick = { clientType = option; typeMenuExpanded = false })
                        }
                    }
                }
                if (clientType == GoogleOAuthClientType.DESKTOP) {
                    RemoteTextField(secret, { secret = it }, stringResource(R.string.remote_machine_client_secret), visual = PasswordVisualTransformation())
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(GCloudOAuthClient(initial.id, name, clientId, secret, clientType)) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

private data class SshHighlightColors(
    val comment: Color,
    val keyword: Color,
    val value: Color
)

private class SshConfigVisualTransformation(
    private val colors: SshHighlightColors
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        highlightSshConfig(text.text, colors),
        OffsetMapping.Identity
    )
}

private fun highlightSshConfig(
    text: String,
    colors: SshHighlightColors
): AnnotatedString = buildAnnotatedString {
    text.lineSequence().forEachIndexed { index, line ->
        val content = line.trimStart()
        when {
            content.startsWith("#") -> withStyle(SpanStyle(color = colors.comment)) { append(line) }
            content.substringBeforeAnyWhitespace().lowercase() in SSH_CONFIG_KEYWORDS -> {
                val leading = line.take(line.length - content.length)
                val split = content.indexOfFirst { it == ' ' || it == '\t' }
                append(leading)
                if (split < 0) {
                    withStyle(SpanStyle(color = colors.keyword)) { append(content) }
                } else {
                    withStyle(SpanStyle(color = colors.keyword)) { append(content.substring(0, split)) }
                    withStyle(SpanStyle(color = colors.value)) { append(content.substring(split)) }
                }
            }
            else -> withStyle(SpanStyle(color = colors.value)) { append(line) }
        }
        if (index < text.lineSequence().count() - 1) append('\n')
    }
}

private val SSH_CONFIG_KEYWORDS = setOf(
    "host", "hostname", "user", "port", "proxycommand", "localcommand", "identityfile",
    "stricthostkeychecking", "compression", "serveraliveinterval"
)

private fun String.substringBeforeAnyWhitespace(): String =
    substringBeforeAny { it == ' ' || it == '\t' }

private inline fun String.substringBeforeAny(predicate: (Char) -> Boolean): String {
    val index = indexOfFirst(predicate)
    return if (index < 0) this else substring(0, index)
}
