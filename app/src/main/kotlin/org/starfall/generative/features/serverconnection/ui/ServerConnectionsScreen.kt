package org.starfall.generative.features.serverconnection.ui

import org.starfall.generative.shared.ui.layout.fadingVerticalScroll
import org.starfall.generative.shared.ui.lists.FadingLazyVerticalGrid
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import org.starfall.generative.integrations.generativeserver.GenerativeServerInvitation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.database.repositories.ServerConnectionProfile
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.ui.lists.FadeGridListContent
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate
import org.starfall.generative.shared.ui.screens.ListScreenTemplate
import org.starfall.generative.shared.ui.screens.ScreenDestination
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.inputs.SelectionDropdown
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineType

private class ConnectionEditorPage(
    val profile: ServerConnectionProfile? = null,
    val repairing: Boolean = false,
)

@Composable
fun ServerConnectionsScreen(service: ServerConnections, onProviders: (String) -> Unit,
    onMcp: (String) -> Unit, onBack: () -> Unit) {
    val profiles by service.profiles.collectAsState(initial = emptyList())
    val machineState = service.sshMachines?.collectAsState()
    val sshMachines = machineState?.value?.machines.orEmpty().filter { it.type == RemoteMachineType.SSH }
    var editorPage by remember { mutableStateOf<ConnectionEditorPage?>(null) }
    var deleting by remember { mutableStateOf<ServerConnectionProfile?>(null) }
    var revoking by remember { mutableStateOf<ServerConnectionProfile?>(null) }
    var testing by remember { mutableStateOf<ServerConnectionProfile?>(null) }
    var result by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun launchOperation(
        failureMessage: Int = R.string.server_connection_failed,
        successMessage: Int = R.string.server_connection_success,
        block: suspend () -> Unit,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            result = null
            try { block(); result = successMessage }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { result = failureMessage }
            finally { busy = false }
        }
    }
    ScreenDestination(page = editorPage, onClosed = { editorPage = null }) { page ->
        if (page == null) return@ScreenDestination
        ServerConnectionEditor(page.profile, sshMachines, busy, page.repairing, onBack = { editorPage = null },
            onSave = { name, transport, invitation, machineId, endpoint, username, password, authUrl, allowLocalProviders ->
                launchOperation(if (transport == "opencode") R.string.server_connection_opencode_failed else R.string.server_connection_failed) {
                    val current = page.profile
                    if (transport == "opencode" && (current == null || page.repairing || current.transport != "opencode" || password.isNotBlank() || authUrl.isNotBlank())) {
                        service.saveOpenCode(current, name, endpoint, username, password, authUrl)
                    } else if (current == null) service.pair(name, invitation, transport, machineId, allowLocalProviders)
                    else if (transport == "http" && invitation.isNotBlank() ||
                        transport == "ssh" && (page.repairing || current.transport != "ssh" || current.sshMachineId != machineId)) {
                        service.rePair(current, name, invitation, transport, machineId, allowLocalProviders)
                    } else service.rename(current, name, allowLocalProviders)
                    if (editorPage === page) editorPage = null
                }
            }, error = result?.takeIf { it == R.string.server_connection_failed || it == R.string.server_connection_opencode_failed })
    }
    var search by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    val matching = profiles.filter { it.name.contains(search, true) || it.endpoint.contains(search, true) }
    ListScreenTemplate(title = stringResource(R.string.server_connection), onBack = onBack,
        searchQuery = search, onSearchQueryChange = { search = it }, isGrid = grid, onGridChange = { grid = it },
        primaryActions = { IconButton(onClick = { result = null; editorPage = ConnectionEditorPage() }) {
            SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (testing == null) result?.let { Text(stringResource(it), Modifier.padding(12.dp)) }
            if (matching.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.server_connection_empty))
            } else FadeGridListContent(isGrid = grid, modifier = Modifier.fillMaxSize()) { gridMode ->
                FadingLazyVerticalGrid(columns = GridCells.Fixed(if (gridMode) 2 else 1),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(matching, key = { it.id }) { profile ->
                        ItemCard(isGrid = gridMode, onClick = { result = null; editorPage = ConnectionEditorPage(profile) },
                            icon = { SharedIcon(Icons.Outlined.Computer, null) }, content = {
                                Column { Text(profile.name); Text("${profile.transport.uppercase()} · ${profile.endpoint}", style = MaterialTheme.typography.bodySmall)
                                    if (profile.selected) Text(stringResource(R.string.server_connection_selected), style = MaterialTheme.typography.labelSmall) }
                            }, actions = {
                                var menu by remember { mutableStateOf(false) }
                                IconButton(onClick = { menu = true }) { SharedIcon(Icons.Default.MoreVert, stringResource(R.string.common_more_options)) }
                                org.starfall.generative.shared.ui.menus.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.model_providers)) }, onClick = { menu = false; onProviders(profile.id) }, leadingIcon = { SharedIcon(Icons.Outlined.Computer, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.mcp)) }, onClick = { menu = false; onMcp(profile.id) }, leadingIcon = { SharedIcon(Icons.Outlined.Mcp, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.server_connection_test)) },
                                        enabled = !busy,
                                        onClick = {
                                            menu = false
                                            testing = profile
                                            launchOperation(R.string.server_connection_test_failed, R.string.server_connection_test_success) {
                                                service.test(profile.id)
                                            }
                                        },
                                        leadingIcon = { SharedIcon(Icons.Outlined.Refresh, null) },
                                    )
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.server_connection_repair)) }, onClick = { menu = false; result = null; editorPage = ConnectionEditorPage(profile, repairing = true) }, leadingIcon = { SharedIcon(Icons.Outlined.Edit, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.server_connection_select)) }, onClick = { menu = false; launchOperation { service.select(profile.id) } }, leadingIcon = { SharedIcon(Icons.Outlined.Check, null) })
                                    if (profile.transport != "opencode") org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.server_connection_revoke)) }, onClick = { menu = false; revoking = profile }, leadingIcon = { SharedIcon(Icons.Outlined.LinkOff, null) })
                                    org.starfall.generative.shared.ui.menus.DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; deleting = profile }, leadingIcon = { SharedIcon(Icons.Outlined.Delete, null) })
                                }
                            })
                    }
                }
            }
        }
    }
    testing?.let { profile ->
        val close = {
            testing = null
            result = null
        }
        AlertDialog(
            onDismissRequest = { if (!busy) close() },
            title = { Text(stringResource(R.string.server_connection_test)) },
            text = {
                Text(stringResource(
                    if (busy) R.string.server_connection_testing else result ?: R.string.server_connection_test_failed,
                    profile.name,
                ))
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = close) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
    revoking?.let { profile -> AlertDialog(onDismissRequest = { revoking = null },
        title = { Text(stringResource(R.string.server_connection_revoke)) },
        text = { Text(stringResource(R.string.server_connection_revoke_confirm, profile.name)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { revoking = null; launchOperation(R.string.server_connection_revoke_failed) { service.revoke(profile.id) } }) {
            Text(stringResource(R.string.server_connection_revoke), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.common_close)) } }) }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.common_delete)) },
        text = { Text(stringResource(R.string.server_connection_delete_confirm, profile.name)) },
        confirmButton = { TextButton(onClick = { deleting = null; launchOperation { service.delete(profile.id) } }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_close)) } }) }
}

@Composable
internal fun ServerConnectionEditor(profile: ServerConnectionProfile?, machines: List<RemoteMachine>,
    busy: Boolean, repairing: Boolean, onBack: () -> Unit,
    onSave: (String, String, String, String, String, String, String, String, Boolean) -> Unit, error: Int?) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var program by remember(profile?.id) { mutableStateOf(if (profile?.transport == "opencode") "opencode" else "generative") }
    var transport by remember(profile?.id) { mutableStateOf(profile?.transport?.takeIf { it == "ssh" } ?: "http") }
    var allowLocalProviders by remember(profile?.id) { mutableStateOf(profile?.allowLocalProviders ?: false) }
    val selectedTransport = if (program == "opencode") "opencode" else transport
    // Invitations contain one-time secrets and must not enter saved instance state.
    var invitation by remember(profile?.id) { mutableStateOf("") }
    var scanError by remember(profile?.id) { mutableStateOf(false) }
    var endpoint by remember(profile?.id) { mutableStateOf(profile?.takeIf { it.transport == "opencode" }?.endpoint.orEmpty()) }
    var username by remember(profile?.id) { mutableStateOf(profile?.takeIf { it.transport == "opencode" }?.clientId ?: "opencode") }
    var password by remember(profile?.id) { mutableStateOf("") }
    var authUrl by remember(profile?.id) { mutableStateOf("") }
    var authMethod by remember(profile?.id) { mutableStateOf("basic") }
    val context = LocalContext.current
    var sshMachineId by remember(profile?.id) { mutableStateOf(profile?.sshMachineId.orEmpty()) }
    val needsPairing = profile == null || selectedTransport != profile.transport ||
        (selectedTransport == "http" && invitation.isNotBlank()) ||
        (selectedTransport == "ssh" && (repairing || sshMachineId != profile.sshMachineId)) ||
        (selectedTransport == "opencode" && (repairing || password.isNotBlank() || authUrl.isNotBlank() || username != profile.clientId || endpoint != profile.endpoint))
    val valid = name.isNotBlank() && (!needsPairing || when (selectedTransport) {
        "http" -> invitation.isNotBlank()
        "ssh" -> machines.any { it.id == sshMachineId }
        "opencode" -> if (authMethod == "link") authUrl.isNotBlank() else endpoint.isNotBlank() && password.isNotEmpty()
        else -> false
    })
    EditorScreenTemplate(title = stringResource(R.string.server_connection), onBack = onBack, actions = {
        IconButton(enabled = !busy && valid, onClick = { onSave(name, selectedTransport, invitation, sshMachineId, endpoint, username, password, authUrl, allowLocalProviders) }) {
            SharedIcon(Icons.Outlined.Check, stringResource(R.string.common_save))
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).fadingVerticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error != null) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.server_connection_name)) }, singleLine = true)
            SelectionDropdown(label = stringResource(R.string.server_connection_program),
                options = listOf("generative" to stringResource(R.string.generative_server_name), "opencode" to "OpenCode"),
                selected = program, onSelect = { program = it },
                modifier = Modifier.fillMaxWidth(), enabled = !busy && profile == null)
            if (program == "generative") {
                SelectionDropdown(label = stringResource(R.string.server_connection_transport),
                    options = listOf("http" to "HTTP", "ssh" to "SSH"), selected = transport,
                    onSelect = { transport = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                SelectionDropdown(label = stringResource(R.string.server_connection_provider_source),
                    options = listOf(false to stringResource(R.string.server_connection_providers_server),
                        true to stringResource(R.string.server_connection_providers_both)),
                    selected = allowLocalProviders, onSelect = { allowLocalProviders = it },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
            if (profile != null) Text(profile.endpoint, style = MaterialTheme.typography.bodySmall)
            if (selectedTransport == "http") {
                Text(stringResource(if (profile == null || profile.transport != "http")
                    R.string.server_connection_pair_hint else R.string.server_connection_repair_hint))
                OutlinedTextField(invitation, { invitation = it; scanError = false }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_connection_invitation)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    trailingIcon = {
                        IconButton(enabled = !busy, onClick = {
                            // The invitation fragment contains a one-time secret: validate it in RAM only.
                            try {
                                val options = GmsBarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
                                GmsBarcodeScanning.getClient(context, options).startScan()
                                    .addOnSuccessListener { code ->
                                        val url = code.rawValue.orEmpty()
                                        if (runCatching { GenerativeServerInvitation.parse(url) }.isSuccess) {
                                            invitation = url; scanError = false
                                        } else scanError = true
                                    }
                                    .addOnFailureListener { scanError = true }
                            } catch (_: Exception) { scanError = true }
                        }) { SharedIcon(Icons.Outlined.CameraAlt, stringResource(R.string.server_connection_scan_qr)) }
                    })
                if (scanError) Text(stringResource(R.string.server_connection_scan_failed),
                    color = MaterialTheme.colorScheme.error)
            } else if (selectedTransport == "opencode") {
                Text(stringResource(R.string.server_connection_opencode_hint))
                SelectionDropdown(label = stringResource(R.string.server_connection_auth_method),
                    options = listOf("basic" to stringResource(R.string.server_connection_auth_password),
                        "link" to stringResource(R.string.server_connection_auth_link)),
                    selected = authMethod, onSelect = { authMethod = it; password = ""; authUrl = "" },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                if (authMethod == "basic") {
                    OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(),
                        enabled = !busy && profile?.transport != "opencode",
                        label = { Text(stringResource(R.string.server_connection_opencode_url)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        visualTransformation = VisualTransformation.None)
                    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.server_connection_opencode_username)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        visualTransformation = VisualTransformation.None)
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.server_connection_opencode_password)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation())
                } else {
                    OutlinedTextField(authUrl, { authUrl = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.server_connection_opencode_auth_url)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation())
                }
            } else {
                SelectionDropdown(label = stringResource(R.string.server_connection_ssh_machine),
                    options = machines.map { it.id to it.name }, selected = sshMachineId,
                    onSelect = { sshMachineId = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                if (machines.isEmpty()) Text(stringResource(R.string.server_connection_no_ssh_machine))
                Text(stringResource(R.string.server_connection_ssh_hint))
                if (profile?.transport == "ssh") {
                    Text(stringResource(if (repairing) R.string.server_connection_ssh_repair_hint
                        else R.string.server_connection_ssh_rename_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
