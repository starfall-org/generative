package org.starfall.generative.features.providers.list.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.features.providers.list.logic.place
import org.starfall.generative.features.providers.list.logic.reorderMembers
import org.starfall.generative.features.providers.list.logic.reorderRoot
import org.starfall.generative.features.providers.list.logic.sampleProvidersDemoData
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.text.OutlinedTextField

/** The real ProvidersScreen, wired to disposable RAM data rather than user repositories. */
@Composable
fun ProvidersDemoScreen(onBack: () -> Unit) {
    var data by remember { mutableStateOf(sampleProvidersDemoData()) }
    var grid by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var editing by remember { mutableStateOf<ModelProvider?>(null) }

    ProvidersScreen(
        providers = data.providers,
        providerGroups = data.groups,
        collapsedSectionsState = collapsed,
        onCollapsedSectionsChange = { collapsed = it },
        isGridView = grid,
        onToggleGridView = { grid = it },
        onOpenProviderEditor = { provider, _ -> editing = provider },
        onSaveGroup = { group ->
            data = data.copy(groups = data.groups.filterNot { it.id == group.id } + group)
        },
        onSaveGroupIcon = { group ->
            if (data.groups.any { it.id == group.id }) {
                data = data.copy(groups = data.groups.map {
                    if (it.id == group.id) it.copy(icon = group.icon) else it
                })
            }
        },
        onDeleteGroup = { id ->
            val rootTail = data.providers.filter { it.groupId == null }.maxOfOrNull { it.sortOrder } ?: 0
            var next = rootTail
            data = data.copy(
                groups = data.groups.filterNot { it.id == id },
                providers = data.providers.map { provider ->
                    if (provider.groupId == id) provider.copy(groupId = null, sortOrder = ++next)
                    else provider
                }
            )
        },
        onMoveProviderToGroup = { id, folder ->
            val target = folder?.takeIf { targetId -> data.groups.any { it.id == targetId } }
            val moving = data.providers.firstOrNull { it.id == id }
            if (moving != null) {
                val other = data.providers.filterNot { it.id == id }
                val order = other.filter { it.groupId == target }.maxOfOrNull { it.sortOrder } ?: -1
                data = data.copy(providers = other + moving.copy(groupId = target, sortOrder = order + 1))
            }
        },
        onPlaceProviderTracked = { placement, completed ->
            data = data.place(placement)
            completed(Result.success(Unit))
        },
        onDeleteProvider = { id ->
            data = data.copy(providers = data.providers.filterNot { it.id == id })
        },
        onReorderProviders = { ids -> data = data.reorderMembers(ids) },
        onReorderRootItemsTracked = { order, completed ->
            data = data.reorderRoot(order)
            completed(Result.success(Unit))
        },
        onBack = onBack
    )

    editing?.let { provider ->
        ProvidersDemoEditorDialog(
            provider = provider,
            onDismiss = { editing = null },
            onSave = { modified ->
                data = data.copy(providers = if (data.providers.any { it.id == modified.id }) {
                    data.providers.map { if (it.id == modified.id) modified else it }
                } else data.providers + modified.copy(
                    sortOrder = (data.providers.filter { it.groupId == modified.groupId }
                        .maxOfOrNull { it.sortOrder } ?: -1) + 1
                ))
                editing = null
            }
        )
    }
}

@Composable
private fun ProvidersDemoEditorDialog(
    provider: ModelProvider,
    onDismiss: () -> Unit,
    onSave: (ModelProvider) -> Unit
) {
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.providers_demo_editor_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.common_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmAtRight = true,
        confirmButton = {
            TextButton(onClick = { onSave(provider.copy(name = name.trim())) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
