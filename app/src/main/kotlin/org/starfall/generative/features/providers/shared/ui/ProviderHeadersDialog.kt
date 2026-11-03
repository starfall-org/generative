package org.starfall.generative.features.providers.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

/** Manages a provider's custom HTTP headers; edits are applied to [rows] immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderHeadersDialog(
    rows: SnapshotStateList<Pair<String, String>>,
    valid: Boolean,
    onDismiss: () -> Unit
) {
    val addLabel = stringResource(R.string.common_add)
    val closeLabel = stringResource(R.string.common_close)
    Dialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.headers), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        content = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(rows) { index, row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            row.first, { rows[index] = it to rows[index].second },
                            label = { Text(stringResource(R.string.common_key)) },
                            maxLines = 2, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            row.second, { rows[index] = rows[index].first to it },
                            label = { Text(stringResource(R.string.common_value)) },
                            maxLines = 2, modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { rows.removeAt(index) }) {
                            SharedIcon(Icons.Outlined.Delete, stringResource(R.string.common_delete))
                        }
                    }
                }
                if (!valid) item {
                    Text(
                        stringResource(R.string.provider_headers_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        buttons = {
            TextButton(onClick = { rows.add("" to "") }, modifier = Modifier.semantics { contentDescription = addLabel }) {
                SharedIcon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text(addLabel)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                Text(closeLabel)
            }
        }
    )
}
