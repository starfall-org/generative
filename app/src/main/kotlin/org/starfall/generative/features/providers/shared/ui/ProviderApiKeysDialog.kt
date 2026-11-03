package org.starfall.generative.features.providers.shared.ui

import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.features.providers.shared.model.maskedProviderApiKey

import org.starfall.generative.features.providers.shared.model.ProviderApiKeysState

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.providers.shared.logic.isEmpty
import org.starfall.generative.shared.ui.text.ApiKeyMask
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderApiKeysDialog(state: ProviderApiKeysState, identity: (String) -> String, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyRequiredError = stringResource(R.string.provider_api_key_required)
    val keyDuplicateError = stringResource(R.string.provider_api_key_duplicate)
    fun edit(id: String?) {
        val entry = state.entries.firstOrNull { it.id == id }
        editingId = id; label = entry?.label.orEmpty(); value = entry?.value.orEmpty()
        revealed = false; error = null; editing = true
    }
    val addLabel = stringResource(R.string.common_add)
    val backLabel = stringResource(R.string.common_back)
    val closeLabel = stringResource(R.string.common_close)
    val saveKeyLabel = stringResource(R.string.provider_save_api_key)
    Dialog(
        onDismissRequest = { if (editing) editing = false else onDismiss() },
        title = {
            Text(stringResource(if (editing) R.string.provider_edit_api_key else R.string.provider_manage_api_keys),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        content = {
            if (editing) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.common_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("provider_api_key_label"))
                OutlinedTextField(value, { value = it; error = null }, label = { Text(stringResource(R.string.provider_api_key_value)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    visualTransformation = if (revealed) VisualTransformation.None else ApiKeyMask,
                    trailingIcon = { IconButton(onClick = { revealed = !revealed }) {
                        SharedIcon(if (revealed) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            stringResource(if (revealed) R.string.provider_hide_api_key else R.string.provider_show_api_key))
                    } }, maxLines = 3, isError = error != null, modifier = Modifier.fillMaxWidth().testTag("provider_managed_api_key"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } else {
                val selectedId = state.selectedId(identity)
                val ordered = state.entries.sortedBy { it.id != selectedId }
                if (ordered.isEmpty()) Text(stringResource(R.string.provider_no_api_keys),
                    style = MaterialTheme.typography.bodyMedium)
                else LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ordered, key = { it.id }) { entry ->
                        val selected = entry.id == selectedId
                        var menu by remember(entry.id) { mutableStateOf(false) }
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
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                        state.currentKey = entry.value; onDismiss()
                                    })
                                    .padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                    Text(entry.label.ifBlank { maskedProviderApiKey(entry.value) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (selected) Text(stringResource(R.string.common_selected), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                if (selected) SharedIcon(Icons.Default.Check, stringResource(R.string.common_selected))
                                Box {
                                    IconButton(onClick = { menu = true }) { SharedIcon(Icons.Default.MoreVert,
                                        stringResource(R.string.provider_api_key_actions, entry.label.ifBlank { maskedProviderApiKey(entry.value) })) }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) },
                                            leadingIcon = { SharedIcon(Icons.Outlined.Edit, null) }, onClick = { menu = false; edit(entry.id) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) },
                                            leadingIcon = { SharedIcon(Icons.Outlined.Delete, null) }, onClick = { menu = false; state.delete(entry.id, identity) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        buttons = {
            if (editing) TextButton(onClick = { editing = false },
                modifier = Modifier.semantics { contentDescription = backLabel }) { Text(backLabel) }
            if (!editing) TextButton(onClick = { edit(null) }, modifier = Modifier.semantics { contentDescription = addLabel }) {
                SharedIcon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text(addLabel)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                Text(closeLabel)
            }
            if (editing) {
                Button(enabled = value.isNotBlank(), modifier = Modifier.semantics { contentDescription = saveKeyLabel }, onClick = {
                    runCatching {
                        state.save(editingId, label, value, identity,
                            blankError = keyRequiredError, duplicateError = keyDuplicateError)
                    }.onSuccess { editing = false }.onFailure { error = it.message }
                }) { Text(stringResource(R.string.common_save)) }
            }
        }
    )
}
