package org.starfall.generative.features.storage.ui

import org.starfall.generative.shared.ui.screens.ListScreenTemplate
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.saveable.rememberSaveable
import org.starfall.generative.shared.ui.lists.EmptyState
import org.starfall.generative.shared.ui.lists.FadeGridListContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.starfall.generative.R
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.FluentUi
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.layout.nestedContainerColor
import org.starfall.generative.shared.ui.media.MediaViewer
import org.starfall.generative.shared.ui.media.mediaMimeType
import org.starfall.generative.shared.ui.media.mediaThumbnail
import org.starfall.generative.features.storage.logic.listMediaStorageFiles
import org.starfall.generative.features.storage.logic.deleteMediaStorageFiles

private fun File.mime() = mediaMimeType(name)

private data class ToolFilesSnapshot(val files: List<File>, val totalBytes: Long)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StorageScreen(store: ToolFiles, onBack: () -> Unit, onBrowseFiles: () -> Unit) {
    val revision by ToolFiles.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var isGrid by rememberSaveable { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(ToolFilesSnapshot(emptyList(), 0L)) }
    val files = snapshot.files
    var selected by remember { mutableStateOf<Set<File>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var inSelectMode by remember { mutableStateOf(false) }
    var viewingFile by remember { mutableStateOf<File?>(null) }

    val allSelected = files.isNotEmpty() && selected.size == files.size

    LaunchedEffect(revision) {
        snapshot = withContext(Dispatchers.IO) {
            val current = listMediaStorageFiles(store)
            ToolFilesSnapshot(current, current.sumOf { it.length() })
        }
        selected = selected.intersect(snapshot.files.toSet())
        if (snapshot.files.isEmpty()) {
            inSelectMode = false
            selected = emptySet()
        }
    }

    // Back exits selection first, then the screen. Viewer cover handles its own back.
    BackHandler(enabled = viewingFile != null) { viewingFile = null }

    ListScreenTemplate(
        title = stringResource(R.string.media_management),
        onBack = onBack,
        handleBack = viewingFile == null,
        snackbarHost = { SnackbarHost(snackbar) },
        isGrid = isGrid,
        onGridChange = { isGrid = it },
        bottomPadding = 8.dp,
        selectionTitle = if (inSelectMode) {
            if (selected.isEmpty()) stringResource(R.string.sidebar_select)
            else "${selected.size} ${stringResource(R.string.common_selected)}"
        } else null,
        onExitSelection = { inSelectMode = false; selected = emptySet() },
        selectionActions = {
            IconButton(
                onClick = { selected = if (allSelected) emptySet() else files.toSet() },
                enabled = files.isNotEmpty()
            ) {
                SharedIcon(
                    imageVector = if (allSelected) FluentUi.Regular.DismissCircle else FluentUi.Regular.CheckmarkCircle,
                    contentDescription = stringResource(
                        if (allSelected) R.string.storage_clear_selection else R.string.storage_select_all
                    )
                )
            }
            IconButton(
                onClick = { if (selected.isNotEmpty()) confirmDelete = true },
                enabled = selected.isNotEmpty()
            ) {
                SharedIcon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        primaryActions = {
            IconButton(onClick = onBrowseFiles) {
                SharedIcon(Icons.Outlined.OpenInBrowser, stringResource(R.string.storage_open_in_files))
            }
            if (files.isNotEmpty()) {
                IconButton(onClick = { inSelectMode = true }) {
                    SharedIcon(FluentUi.Regular.CheckmarkCircle, stringResource(R.string.sidebar_select))
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    EmptyState(Icons.Outlined.FolderOpen, stringResource(R.string.storage_empty))
                }
            } else {
                FadeGridListContent(isGrid = isGrid, modifier = Modifier.fillMaxSize()) { gridMode ->
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (gridMode) 2 else 1),
                        modifier = Modifier.testTag("storage-preview-list"),
                        contentPadding = padding,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(files, key = { it.absolutePath }) { file ->
                            val isSelected = file in selected
                            StoragePreviewTile(
                                file = file,
                                isGrid = gridMode,
                                inSelectMode = inSelectMode,
                                selected = isSelected,
                                onToggleSelect = { checked ->
                                    selected = if (checked) selected + file else selected - file
                                },
                                onEnterSelectMode = {
                                    if (!inSelectMode) {
                                        inSelectMode = true
                                        selected = setOf(file)
                                    }
                                },
                                onOpenViewer = { viewingFile = file }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dialog_delete_files_title)) },
            text = { Text(stringResource(R.string.storage_delete_message, selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    val filesToDelete = selected
                    confirmDelete = false
                    scope.launch {
                        try {
                            deleteMediaStorageFiles(store, filesToDelete)
                            selected = emptySet()
                            if (files.size == filesToDelete.size) inSelectMode = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            snackbar.showSnackbar(error.message ?: "Could not delete files")
                        }
                    }
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_close)) } }
        )
    }

    viewingFile?.let { file ->
        MediaViewer(
            store = store, file = file,
            onDelete = { deleteMediaStorageFiles(store, listOf(file)) },
            onDismiss = { viewingFile = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoragePreviewTile(
    file: File,
    isGrid: Boolean,
    inSelectMode: Boolean,
    selected: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onEnterSelectMode: () -> Unit,
    onOpenViewer: () -> Unit
) {
    val context = LocalContext.current
    val mime = remember(file.name) { file.mime() }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val previewSize = with(LocalDensity.current) {
        (if (isGrid) screenWidth / 2 else screenWidth).roundToPx()
    }
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null, file.absolutePath, file.lastModified(), previewSize
    ) {
        value = mediaThumbnail(context, file.path, mime, previewSize)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().aspectRatio(if (isGrid) 1f else 2.15f)
            .combinedClickable(
                onClick = { if (inSelectMode) onToggleSelect(!selected) else onOpenViewer() },
                onLongClick = { if (!inSelectMode) onEnterSelectMode() }
            )
            .semantics { contentDescription = file.name }
            .testTag("storage-preview:${file.absolutePath}"),
        shape = RoundedCornerShape(12.dp),
        color = nestedContainerColor(),
        border = nestedContainerBorder()
    ) {
        Box(contentAlignment = Alignment.Center) {
            thumbnail?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
            } ?: SharedIcon(
                when {
                    mime.startsWith("audio/") -> Icons.Outlined.Audiotrack
                    mime.startsWith("video/") -> Icons.Outlined.Videocam
                    mime.startsWith("image/") -> Icons.Outlined.BrokenImage
                    else -> Icons.Outlined.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
            if (inSelectMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onToggleSelect,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
        }
    }
}
