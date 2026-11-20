package org.starfall.generative.features.serverconnection.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.serverconnection.logic.LocalImportItem
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.subfeatures.icons.ui.providerInitials
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.sheets.ItemListSheet
import org.starfall.generative.shared.ui.sheets.ItemListSheetItem

/** A presentation-only picker. No credentials are loaded or held in Compose state. */
@Composable
internal fun LocalCatalogImportSheet(
    providers: Boolean,
    items: List<LocalImportItem>,
    loading: Boolean,
    busy: Boolean,
    error: Int?,
    runtimeOnly: Boolean,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = items.filter { it.name.contains(query.trim(), ignoreCase = true) }
    ItemListSheet(
        title = stringResource(R.string.server_import_local),
        onDismissRequest = { if (!busy) onDismiss() },
        searchQuery = query,
        onSearchQueryChange = { query = it },
        items = filtered.map { item ->
            ItemListSheetItem(
                key = item.id, value = item.id,
                onClick = if (!loading && !busy) ({ onImport(item.id) }) else null,
                icon = { EntityIcon(item.icon, text = providerInitials(item.name),
                    fallback = if (providers) Icons.Outlined.Hub else Icons.Outlined.Mcp, matchName = item.name) },
                content = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        },
        loading = loading || busy,
        error = error?.let { stringResource(it) },
        emptyMessage = stringResource(R.string.shared_no_items),
        footer = {
            if (!loading) Text(
                stringResource(if (runtimeOnly) R.string.server_import_runtime_credentials else R.string.server_import_credentials),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}
