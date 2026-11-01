package org.starfall.generative.features.machines.details.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.machines.shared.ui.RemoteErrorText
import org.starfall.generative.features.machines.shared.ui.RemoteTextField
import org.starfall.generative.features.machines.shared.ui.RemoteToggle
import org.starfall.generative.features.machines.shared.ui.remoteLiteralKeyboardOptions
import org.starfall.generative.shared.model.WebSocketProxyConfig
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

/**
 * Editor for the extra JSch session options. Options stay hidden until the user adds one, so the
 * machine editor only shows the options that are actually set.
 */
@Composable
internal fun SshOptionsEditor(
    options: Map<String, String>,
    onChange: (Map<String, String>) -> Unit
) {
    var draftKey by remember { mutableStateOf("") }
    var draftValue by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.entries.forEach { (key, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteTextField(
                        key,
                        {},
                        stringResource(R.string.remote_server_ssh_option_name),
                        Modifier.weight(1f),
                        readOnly = true,
                        keyboardOptions = remoteLiteralKeyboardOptions()
                    )
                    IconButton(onClick = { onChange(options - key) }) {
                        SharedIcon(Icons.Outlined.Delete, stringResource(R.string.common_remove), tint = MaterialTheme.colorScheme.error)
                    }
                }
                RemoteTextField(
                    value,
                    { newValue -> onChange(options + (key to newValue)) },
                    stringResource(R.string.remote_server_ssh_option_value),
                    keyboardOptions = remoteLiteralKeyboardOptions()
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteTextField(
                draftKey,
                { draftKey = it },
                stringResource(R.string.remote_server_ssh_option_name),
                keyboardOptions = remoteLiteralKeyboardOptions()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteTextField(
                    draftValue,
                    { draftValue = it },
                    stringResource(R.string.remote_server_ssh_option_value),
                    Modifier.weight(1f),
                    keyboardOptions = remoteLiteralKeyboardOptions()
                )
                IconButton(
                    enabled = draftKey.isNotBlank() && draftValue.isNotBlank() && draftKey.trim() !in options,
                    onClick = {
                        onChange(options + (draftKey.trim() to draftValue.trim()))
                        draftKey = ""
                        draftValue = ""
                    }
                ) {
                    SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
                }
            }
        }
    }
}

/**
 * WebSocket Proxy configuration. The shared [Dialog] keeps advanced settings in an
 * expandable section within the content area.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebSocketProxyDialog(
    initial: WebSocketProxyConfig?,
    onDismiss: () -> Unit,
    onSave: (WebSocketProxyConfig?) -> Unit
) {
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var headersText by remember(initial) {
        mutableStateOf(initial?.headers.orEmpty().entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    var connectTimeout by remember(initial) { mutableStateOf((initial?.connectTimeoutSeconds ?: 15).toString()) }
    var skipCertificates by remember(initial) { mutableStateOf(initial?.skipCertificateVerification ?: false) }
    var caCertificate by remember(initial) { mutableStateOf(initial?.caCertificatePem.orEmpty()) }
    var handshakeTimeout by remember(initial) { mutableStateOf((initial?.handshakeTimeoutSeconds ?: 15).toString()) }
    var readTimeout by remember(initial) { mutableStateOf((initial?.readTimeoutSeconds ?: 0).toString()) }
    var writeTimeout by remember(initial) { mutableStateOf((initial?.writeTimeoutSeconds ?: 0).toString()) }
    var closeTimeout by remember(initial) { mutableStateOf((initial?.closeTimeoutSeconds ?: 5).toString()) }
    var subProtocols by remember(initial) { mutableStateOf(initial?.subProtocols.orEmpty().joinToString(", ")) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun numeric(update: (String) -> Unit): (String) -> Unit =
        { value -> if (value.all(Char::isDigit)) update(value) }

    Dialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_server_websocket_proxy)) },
        content = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RemoteTextField(
                    url,
                    { url = it; error = null },
                    stringResource(R.string.remote_server_websocket_url),
                    keyboardOptions = remoteLiteralKeyboardOptions()
                )
                RemoteTextField(
                    headersText,
                    { headersText = it },
                    stringResource(R.string.remote_server_websocket_headers),
                    keyboardOptions = remoteLiteralKeyboardOptions(),
                    minLines = 3,
                    maxLines = 6
                )
                RemoteTextField(
                    connectTimeout,
                    numeric { connectTimeout = it },
                    stringResource(R.string.remote_server_websocket_connect_timeout),
                    keyboardOptions = remoteLiteralKeyboardOptions(KeyboardType.Number)
                )
                RemoteToggle(
                    label = stringResource(R.string.remote_server_websocket_skip_certificates),
                    checked = skipCertificates,
                    onCheckedChange = { skipCertificates = it }
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(
                            if (advancedExpanded) R.string.remote_server_websocket_hide_advanced
                            else R.string.remote_server_websocket_show_advanced
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    SharedIcon(
                        if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                AnimatedVisibility(visible = advancedExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RemoteTextField(
                            caCertificate,
                            { caCertificate = it },
                            stringResource(R.string.remote_server_websocket_ca_certificate),
                            keyboardOptions = remoteLiteralKeyboardOptions(),
                            minLines = 3,
                            maxLines = 8
                        )
                        RemoteTextField(
                            handshakeTimeout,
                            numeric { handshakeTimeout = it },
                            stringResource(R.string.remote_server_websocket_handshake_timeout),
                            keyboardOptions = remoteLiteralKeyboardOptions(KeyboardType.Number)
                        )
                        RemoteTextField(
                            readTimeout,
                            numeric { readTimeout = it },
                            stringResource(R.string.remote_server_websocket_read_timeout),
                            keyboardOptions = remoteLiteralKeyboardOptions(KeyboardType.Number)
                        )
                        RemoteTextField(
                            writeTimeout,
                            numeric { writeTimeout = it },
                            stringResource(R.string.remote_server_websocket_write_timeout),
                            keyboardOptions = remoteLiteralKeyboardOptions(KeyboardType.Number)
                        )
                        RemoteTextField(
                            closeTimeout,
                            numeric { closeTimeout = it },
                            stringResource(R.string.remote_server_websocket_close_timeout),
                            keyboardOptions = remoteLiteralKeyboardOptions(KeyboardType.Number)
                        )
                        RemoteTextField(
                            subProtocols,
                            { subProtocols = it },
                            stringResource(R.string.remote_server_websocket_subprotocols),
                            keyboardOptions = remoteLiteralKeyboardOptions()
                        )
                    }
                }
                error?.let { RemoteErrorText(it) }
            }
        },
        buttons = {
            if (initial != null) {
                TextButton(onClick = { onSave(null) }) {
                    SharedIcon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            Button(onClick = {
                runCatching {
                    val headers = headersText.lineSequence().mapNotNull { line ->
                        line.substringBefore(':', "").trim().takeIf(String::isNotBlank)?.let { key ->
                            key to line.substringAfter(':', "").trim()
                        }
                    }.toMap()
                    WebSocketProxyConfig(
                        url = url,
                        headers = headers,
                        connectTimeoutSeconds = connectTimeout.toIntOrNull() ?: 15,
                        skipCertificateVerification = skipCertificates,
                        caCertificatePem = caCertificate,
                        handshakeTimeoutSeconds = handshakeTimeout.toIntOrNull() ?: 15,
                        readTimeoutSeconds = readTimeout.toIntOrNull() ?: 0,
                        writeTimeoutSeconds = writeTimeout.toIntOrNull() ?: 0,
                        closeTimeoutSeconds = closeTimeout.toIntOrNull() ?: 5,
                        subProtocols = subProtocols.split(',').map(String::trim).filter(String::isNotBlank)
                    ).normalized()
                }
                    .onSuccess { config -> onSave(config.takeIf { it.isConfigured }) }
                    .onFailure { failure -> error = failure.message }
            }) { Text(stringResource(R.string.common_save)) }
        }
    )
}
