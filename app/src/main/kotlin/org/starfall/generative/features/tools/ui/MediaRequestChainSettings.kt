package org.starfall.generative.features.tools.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.media.*
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.lists.ReorderableCollection
import org.starfall.generative.shared.ui.lists.ItemSurface
import org.starfall.generative.shared.ui.sheets.ContextMenuAction
import org.starfall.generative.shared.ui.sheets.ContextMenuSheet

/** A reusable profile library. Selection belongs to the tool's model dropdown, not this list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaRequestChainSettings(
    provider: ModelProvider?,
    config: SystemToolConfig,
    onAutoSave: (Map<String, MediaRequestProfile>, String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onAddProfile: () -> Unit,
    onBack: () -> Unit,
    video: Boolean,
    kind: MediaKind = if (video) MediaKind.VIDEO else MediaKind.IMAGE
) {
    val music = kind == MediaKind.MUSIC
    val profiles = config.profiles(kind)
    val protectedId = if (music) "" else defaultMediaProfile(video).id
    val storedActiveId = if (music) config.activeMusicRequestProfileId else if (video) config.activeVideoRequestProfileId else config.activeImageRequestProfileId
    val activeId = storedActiveId.takeIf { it in profiles } ?: protectedId
    var deletingProfileId by remember { mutableStateOf<String?>(null) }

    fun persist(updated: Map<String, MediaRequestProfile>) {
        val normalized = if (music) updated.filterValues { it.mode == MediaGenerationMode.CUSTOM } else updated.withDefaultMediaProfile(video)
        onAutoSave(normalized, activeId.takeIf { it in normalized } ?: protectedId)
    }

    BackHandler(enabled = LocalScreenTransitionActive.current) { onBack() }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,modifier = Modifier.testTag("media-profiles-screen"), topBar = {
        TopAppBar(
            colors = edgeToEdgeTopAppBarColors(),
            title = { Text(stringResource(if (music) R.string.music_profiles else if (video) R.string.media_video_gen_profiles else R.string.media_image_gen_profiles)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                }
            },
            actions = {
                IconButton(onClick = onAddProfile) {
                    SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
                }
            }
        )
    }) { padding ->
        val ordered = profiles.values.toList()
        ReorderableCollection(
            items = ordered,
            key = { it.id },
            canReorder = { it.id != protectedId },
            columns = GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = scrollableScaffoldPadding(padding, horizontal = 16.dp, top = 12.dp, bottom = 16.dp),
            spacing = 8.dp,
            onReorder = { ids ->
                // Keep the built-in Default first. LinkedHashMap preserves the user's order.
                val reordered = linkedMapOf<String, MediaRequestProfile>()
                if (protectedId.isNotEmpty()) profiles[protectedId]?.let { reordered[protectedId] = it }
                ids.forEach { id -> profiles[id]?.let { reordered[id] = it } }
                persist(reordered)
            },
            preview = { profile ->
                ItemSurface(isGrid = false, onClick = {}) {
                    Text(profile.name, Modifier.fillMaxWidth().padding(18.dp),
                        style = MaterialTheme.typography.titleMedium)
                }
            }
        ) { profile, dragModifier ->
                val canDelete = profile.id != protectedId &&
                    (profile.mode != MediaGenerationMode.DEFAULT ||
                        profiles.values.count { it.mode == MediaGenerationMode.DEFAULT } > 1)
                ItemSurface(
                    isGrid = false,
                    onClick = { onOpenProfile(profile.id) },
                    modifier = dragModifier.testTag("media-profile-item_${profile.id}")
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SharedIcon(Icons.Outlined.FormatListBulleted, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (profile.mode == MediaGenerationMode.CUSTOM)
                                    pluralStringResource(R.plurals.media_chain_profile_steps, profile.chain.steps.size, profile.chain.steps.size)
                                else stringResource(R.string.common_default),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                SharedIcon(Icons.Default.MoreVert, stringResource(R.string.common_more_options))
                            }
                             if (menuExpanded) ContextMenuSheet(
                                 actions = buildList {
                                     add(ContextMenuAction(
                                         label = stringResource(R.string.common_edit),
                                         onClick = { onOpenProfile(profile.id) },
                                         icon = Icons.Outlined.Edit
                                     ))
                                     if (music) add(ContextMenuAction(
                                         label = stringResource(R.string.music_duplicate_profile),
                                         onClick = {
                                             val id = java.util.UUID.randomUUID().toString()
                                             persist(profiles + (id to profile.copy(id = id, name = "${profile.name} (copy)")))
                                             onOpenProfile(id)
                                         },
                                         icon = Icons.Outlined.ContentCopy
                                     ))
                                     if (canDelete) add(ContextMenuAction(
                                         label = stringResource(R.string.common_remove),
                                         onClick = { deletingProfileId = profile.id },
                                         icon = Icons.Outlined.Delete,
                                         destructive = true
                                     ))
                                 },
                                 onDismissRequest = { menuExpanded = false }
                             )
                        }
                    }
                }
        }
    }
    deletingProfileId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingProfileId = null },
            title = { Text(stringResource(R.string.dialog_remove_profile_title)) },
            text = { Text(stringResource(R.string.media_chain_remove_profile_message)) },
            confirmButton = {
                TextButton(onClick = {
                    if (music || (id != protectedId && profiles.values.count { it.mode == MediaGenerationMode.DEFAULT } >
                        (if (profiles[id]?.mode == MediaGenerationMode.DEFAULT) 1 else 0))) {
                        persist(profiles - id)
                    }
                    deletingProfileId = null
                }) { Text(stringResource(R.string.common_remove)) }
            },
            dismissButton = { TextButton(onClick = { deletingProfileId = null }) {
                Text(stringResource(R.string.common_close))
            } }
        )
    }
}
