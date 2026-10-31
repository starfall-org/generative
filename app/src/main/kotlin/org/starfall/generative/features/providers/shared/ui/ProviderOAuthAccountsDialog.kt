package org.starfall.generative.features.providers.shared.ui

import org.starfall.generative.shared.ui.menus.DropdownMenu
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.ui.auth.DeviceCodeChallengeText
import org.starfall.generative.features.providers.shared.logic.isEmpty
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderOAuthAccount
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.text.OutlinedTextField
import org.starfall.generative.shared.ui.auth.rememberOAuthStart
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderOAuthAccountsDialog(
    providerType: ModelApiType,
    accounts: List<ProviderOAuthAccount>, selectedId: String?, canAuthorize: Boolean, canDelete: Boolean,
    onSelect: (ProviderOAuthAccount) -> Unit, onSaveLabel: (String, String) -> Unit,
    onAuthorize: suspend (String?, String) -> Result<ModelProvider>, onCancelAuthorization: () -> Unit,
    onDelete: suspend (String) -> Result<ModelProvider>, onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cancelled = stringResource(R.string.oauth_sign_in_cancelled)
    fun edit(account: ProviderOAuthAccount?) {
        editingId = account?.id; label = account?.label.orEmpty(); error = null; editing = true
    }
    fun dismiss() { if (busy) onCancelAuthorization(); onDismiss() }
    val signIn = rememberOAuthStart {
        scope.launch {
            busy = true; error = null
            try {
                onAuthorize(editingId, label).onSuccess { editing = false }.onFailure { error = it.message }
            } catch (_: CancellationException) { error = cancelled }
            finally { busy = false }
        }
    }
    val addLabel = stringResource(R.string.common_add)
    val backLabel = stringResource(R.string.common_back)
    val closeLabel = stringResource(R.string.common_close)
    val saveLabel = stringResource(R.string.common_save)
    Dialog(
        onDismissRequest = { if (editing && !busy) editing = false else dismiss() },
        title = { Text(stringResource(if (editing) {
            if (editingId == null) R.string.provider_add_account else R.string.provider_edit_account
        } else R.string.provider_manage_accounts)) },
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) DeviceCodeChallengeText(providerType)
                if (editing) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(label, { label = it }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.common_label)) },
                        modifier = Modifier.fillMaxWidth().testTag("oauth-account-label"))
                    accounts.firstOrNull { it.id == editingId }?.identity?.let { Text(it) }
                } else if (accounts.isEmpty()) Text(stringResource(R.string.provider_no_accounts))
                else LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(accounts.sortedBy { it.id != selectedId }, key = { it.id }) { account ->
                        val selected = account.id == selectedId
                        var menu by remember(account.id) { mutableStateOf(false) }
                        val itemShape = RoundedCornerShape(16.dp)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = itemShape,
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                prominentContainerColor()
                            },
                            border = if (selected) {
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                prominentContainerBorder()
                            }
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(itemShape)
                                    .selectable(selected, enabled = !busy, role = Role.RadioButton,
                                        onClick = { onSelect(account) })
                                    .padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                    Text(account.label.ifBlank { account.identity ?: stringResource(R.string.provider_account_number, accounts.indexOf(account) + 1) })
                                    if (account.label.isNotBlank() && !account.identity.isNullOrBlank()) Text(account.identity,
                                        style = MaterialTheme.typography.bodySmall)
                                    if (selected) Text(stringResource(R.string.provider_selected_account),
                                        style = MaterialTheme.typography.labelMedium)
                                }
                                if (selected) SharedIcon(Icons.Default.Check, stringResource(R.string.provider_selected_account))
                                Box {
                                    IconButton(enabled = !busy, onClick = { menu = true }) {
                                        SharedIcon(Icons.Default.MoreVert, stringResource(R.string.oauth_account_actions))
                                    }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) },
                                            onClick = { menu = false; edit(account) })
                                        DropdownMenuItem(enabled = canDelete, text = { Text(stringResource(R.string.common_delete)) },
                                            onClick = {
                                                menu = false
                                                scope.launch {
                                                    busy = true; error = null
                                                    try { onDelete(account.id).onFailure { error = it.message } }
                                                    finally { busy = false }
                                                }
                                            })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        buttons = {
            if (editing && !busy) TextButton(onClick = { editing = false },
                modifier = Modifier.semantics { contentDescription = backLabel }) { Text(backLabel) }
            if (busy && editing) TextButton(onClick = onCancelAuthorization) { Text(stringResource(R.string.common_cancel)) }
            if (!editing) TextButton(enabled = !busy && canAuthorize, onClick = { edit(null) },
                modifier = Modifier.semantics { contentDescription = addLabel }) {
                SharedIcon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text(addLabel)
            }
            if (editing && editingId != null) TextButton(enabled = !busy && canAuthorize, onClick = signIn) {
                SharedIcon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.oauth_sign_in_again))
            }
            if (editing && editingId == null) {
                Button(enabled = !busy && canAuthorize, onClick = signIn) {
                    Text(stringResource(if (busy) R.string.oauth_signing_in else R.string.oauth_sign_in))
                }
            }
            TextButton(onClick = ::dismiss, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                Text(closeLabel)
            }
            if (editing && editingId != null) {
                Button(enabled = !busy, modifier = Modifier.semantics { contentDescription = saveLabel },
                    onClick = { onSaveLabel(editingId!!, label); editing = false }) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    )
}
