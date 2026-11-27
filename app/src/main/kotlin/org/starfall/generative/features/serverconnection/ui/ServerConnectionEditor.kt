package org.starfall.generative.features.serverconnection.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.starfall.generative.R
import org.starfall.generative.database.repositories.ServerConnectionProfile
import org.starfall.generative.features.serverconnection.logic.needsServerCredentials
import org.starfall.generative.features.serverconnection.model.ServerConnectionDraft
import org.starfall.generative.integrations.generativeserver.GenerativeServerInvitation
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.inputs.SelectionDropdown
import org.starfall.generative.shared.ui.layout.FixedAppBarTitle
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.layout.fadingVerticalScroll
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate

@Composable
internal fun ServerConnectionEditor(
    profile: ServerConnectionProfile?,
    machines: List<RemoteMachine>,
    busy: Boolean,
    repairing: Boolean,
    onBack: () -> Unit,
    onSave: (ServerConnectionDraft) -> Unit,
    error: Int?,
    catalogContent: (@Composable () -> Unit)? = null,
) {
    val savedOpenCode = profile?.takeIf { it.transport == "opencode" }
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var program by remember(profile?.id) { mutableStateOf(if (savedOpenCode != null) "opencode" else "generative") }
    var transport by remember(profile?.id) { mutableStateOf(profile?.transport?.takeIf { it == "ssh" } ?: "http") }
    var allowLocalProviders by remember(profile?.id) { mutableStateOf(profile?.allowLocalProviders ?: false) }
    val selectedTransport = if (program == "opencode") "opencode" else transport
    // Invitations and credentials are kept in memory only.
    var invitation by remember(profile?.id) { mutableStateOf("") }
    var endpoint by remember(profile?.id) { mutableStateOf(savedOpenCode?.endpoint.orEmpty()) }
    var username by remember(profile?.id) { mutableStateOf(savedOpenCode?.clientId ?: "opencode") }
    var password by remember(profile?.id) { mutableStateOf("") }
    var authUrl by remember(profile?.id) { mutableStateOf("") }
    var manualAuth by remember(profile?.id) { mutableStateOf(false) }
    var sshMachineId by remember(profile?.id) { mutableStateOf(profile?.sshMachineId.orEmpty()) }
    val draft = ServerConnectionDraft(
        name, selectedTransport, invitation, sshMachineId, endpoint,
        if (manualAuth) username else savedOpenCode?.clientId ?: "opencode",
        if (manualAuth) password else "", if (manualAuth) "" else authUrl, allowLocalProviders,
    )
    val needsPairing = draft.needsServerCredentials(profile, repairing)
    val valid = name.isNotBlank() && (!needsPairing || when (selectedTransport) {
        "http" -> invitation.isNotBlank()
        "ssh" -> machines.any { it.id == sshMachineId }
        "opencode" -> if (manualAuth) endpoint.isNotBlank() && password.isNotEmpty() else authUrl.isNotBlank()
        else -> false
    })

    val generativeServer = stringResource(R.string.generative_server_name)
    val openCode = stringResource(R.string.opencode_name)
    val showCatalog = profile != null && !repairing && !needsPairing && catalogContent != null
    val scrollState = rememberScrollState()
    LaunchedEffect(error) {
        if (error != null) scrollState.animateScrollTo(0)
    }

    EditorScreenTemplate(
        title = {
            FixedAppBarTitle(stringResource(when {
                profile == null -> R.string.server_connection_add_title
                repairing -> R.string.server_connection_repair
                else -> R.string.server_connection_edit_title
            }))
        },
        onBack = onBack,
        actions = {
            if (busy) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(
                    enabled = valid,
                    onClick = { onSave(draft) },
                ) {
                    SharedIcon(Icons.Outlined.Check, stringResource(R.string.common_save))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(padding)
                .consumeWindowInsets(padding)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .fadingVerticalScroll(scrollState)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (error != null) {
                ErrorContainer(stringResource(error), expandable = false)
            }
            SettingsCard {
                if (profile != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SharedIcon(
                            Icons.Outlined.Computer,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (savedOpenCode != null) openCode else generativeServer,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            ConnectionHint(profile.endpoint)
                        }
                    }
                    HorizontalDivider()
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text(stringResource(R.string.server_connection_name)) },
                    singleLine = true,
                )
                if (profile == null) {
                    SelectionDropdown(
                        label = stringResource(R.string.server_connection_program),
                        options = listOf("generative" to generativeServer, "opencode" to openCode),
                        selected = program,
                        onSelect = { program = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }
                if (program == "generative") {
                    SelectionDropdown(
                        label = stringResource(R.string.server_connection_transport),
                        options = listOf("http" to "HTTP", "ssh" to "SSH"),
                        selected = transport,
                        onSelect = { transport = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }
            }

            SettingsCard {
                ConnectionSectionHeading(R.string.server_connection_access_section)
                when (selectedTransport) {
                    "http" -> ServerInvitationField(
                        invitation = invitation,
                        onInvitationChange = { invitation = it },
                        enabled = !busy,
                        hint = if (profile?.transport == "http") R.string.server_connection_repair_hint
                            else R.string.server_connection_pair_hint,
                    )
                    "opencode" -> {
                        ConnectionHint(stringResource(R.string.server_connection_opencode_hint))
                        if (manualAuth) {
                            if (savedOpenCode == null) {
                                ServerLiteralField(endpoint, { endpoint = it }, R.string.server_url, !busy)
                            }
                            ServerLiteralField(username, { username = it }, R.string.server_connection_opencode_username, !busy)
                            ServerLiteralField(password, { password = it }, R.string.server_connection_opencode_password, !busy, secret = true)
                        } else {
                            ServerLiteralField(authUrl, { authUrl = it }, R.string.server_connection_opencode_auth_url, !busy, secret = true)
                        }
                        TextButton(enabled = !busy, onClick = {
                            manualAuth = !manualAuth
                            password = ""
                            authUrl = ""
                        }) {
                            Text(stringResource(if (manualAuth) R.string.server_connection_auth_link
                                else R.string.server_connection_auth_password))
                        }
                    }
                    "ssh" -> {
                        SelectionDropdown(
                            label = stringResource(R.string.server_connection_ssh_machine),
                            options = machines.map { it.id to it.name },
                            selected = sshMachineId,
                            onSelect = { sshMachineId = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        )
                        ConnectionHint(stringResource(if (machines.isEmpty()) R.string.server_connection_no_ssh_machine
                            else R.string.server_connection_ssh_hint))
                        if (profile?.transport == "ssh" && profile.isPaired && machines.isNotEmpty()) {
                            ConnectionHint(stringResource(if (repairing) R.string.server_connection_ssh_repair_hint
                                else R.string.server_connection_ssh_rename_hint))
                        }
                    }
                }
            }

            if (program == "generative" || showCatalog) {
                SettingsCard {
                    ConnectionSectionHeading(R.string.server_connection_resources_section)
                    if (program == "generative") {
                        SelectionDropdown(
                            label = stringResource(R.string.server_connection_provider_source),
                            options = listOf(
                                false to stringResource(R.string.server_connection_providers_server),
                                true to stringResource(R.string.server_connection_providers_both),
                            ),
                            selected = allowLocalProviders,
                            onSelect = { allowLocalProviders = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        )
                    }
                    if (showCatalog) catalogContent()
                }
            }
        }
    }
}

@Composable
private fun ConnectionSectionHeading(@StringRes label: Int) {
    Text(
        stringResource(label),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun ConnectionHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ServerInvitationField(
    invitation: String,
    onInvitationChange: (String) -> Unit,
    enabled: Boolean,
    @StringRes hint: Int,
) {
    var scanError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    ServerLiteralField(
        value = invitation,
        onValueChange = {
            onInvitationChange(it)
            scanError = false
        },
        label = R.string.server_connection_invitation,
        enabled = enabled,
        secret = true,
        isError = scanError,
        supportingText = {
            Text(stringResource(if (scanError) R.string.server_connection_scan_failed else hint))
        },
        trailingIcon = {
            IconButton(enabled = enabled, onClick = {
                // Validate the one-time invitation in memory.
                try {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { code ->
                            val url = code.rawValue.orEmpty()
                            val valid = runCatching { GenerativeServerInvitation.parse(url) }.isSuccess
                            if (valid) onInvitationChange(url)
                            scanError = !valid
                        }
                        .addOnFailureListener { scanError = true }
                } catch (_: Exception) {
                    scanError = true
                }
            }) {
                SharedIcon(Icons.Outlined.CameraAlt, stringResource(R.string.server_connection_scan_qr))
            }
        },
    )
}

@Composable
private fun ServerLiteralField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    enabled: Boolean,
    secret: Boolean = false,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        ),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        isError = isError,
        supportingText = supportingText,
        trailingIcon = trailingIcon,
    )
}
