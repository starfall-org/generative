package org.starfall.generative.shared.subfeatures.explorer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerLocation
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerRepository
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.lists.EmptyState
import org.starfall.generative.shared.ui.lists.FadeGridListContent

private enum class ExplorerAction { FOLDER, RENAME, DELETE }

/**
 * Source-agnostic file explorer shared by every feature that browses files.
 *
 * The screen owns navigation, search, layout mode and dialogs in Compose memory only. Every
 * filesystem operation, file viewer and transfer goes through the injected [ExplorerRepository]
 * and host callbacks, so local, virtual and remote sources render identically.
 */
@Composable
fun ExplorerScreen(
    repository: ExplorerRepository,
    initialLocation: ExplorerLocation,
    onClose: () -> Unit,
    onOpenFile: suspend (ExplorerItem) -> Unit,
    refreshKey: Int = 0,
    onUploadFile: (suspend (ExplorerLocation, Uri) -> Unit)? = null,
    onDownloadFile: (suspend (ExplorerItem, Uri) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val operationFailed = stringResource(R.string.explorer_operation_failed)
    val titleFallback = stringResource(R.string.explorer_title)
    var location by remember(initialLocation) { mutableStateOf(initialLocation) }
    var entries by remember { mutableStateOf<List<ExplorerItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchWasFocused by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var grid by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<ExplorerAction?>(null) }
    var target by remember { mutableStateOf<ExplorerItem?>(null) }
    var name by remember { mutableStateOf("") }
    var pendingUpload by remember { mutableStateOf<ExplorerLocation?>(null) }
    var pendingDownload by remember { mutableStateOf<ExplorerItem?>(null) }

    fun closeSearch() {
        focusManager.clearFocus()
        searchExpanded = false
        searchWasFocused = false
    }

    fun back() {
        closeSearch()
        if (location == initialLocation) onClose()
        else {
            location = location.parent()
            query = ""
        }
    }
    BackHandler { if (searchExpanded) closeSearch() else back() }

    LaunchedEffect(repository, location, refresh, refreshKey) {
        loading = true
        try {
            entries = repository.list(location)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            entries = emptyList()
            snackbar.showSnackbar(error.message ?: operationFailed)
        } finally {
            loading = false
        }
    }

    fun runOperation(refreshAfter: Boolean = true, block: suspend () -> Unit) {
        scope.launch {
            loading = true
            try {
                block()
                if (refreshAfter) refresh++ else loading = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                loading = false
                if (refreshAfter) refresh++ // Some files may have completed before a batch failed.
                snackbar.showSnackbar(error.message ?: operationFailed)
            }
        }
    }

    // Android owns the document pickers; all file I/O is delegated to the host.
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val destination = pendingUpload
        pendingUpload = null
        if (destination != null && uris.isNotEmpty() && onUploadFile != null) {
            runOperation {
                uris.forEach { uri -> onUploadFile(destination, uri) }
            }
        }
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val item = pendingDownload
        pendingDownload = null
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (item != null && uri != null && onDownloadFile != null) {
            runOperation(refreshAfter = false) { onDownloadFile(item, uri) }
        }
    }

    fun startDownload(item: ExplorerItem) {
        pendingDownload = item
        val mime = item.name.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
            ?: "application/octet-stream"
        downloadPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, item.name)
        })
    }

    val filtered = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter { it.name.contains(query, ignoreCase = true) }
    }
    val title = when {
        location.isHome -> titleFallback
        location.path.isNotEmpty() -> location.path.substringAfterLast('/')
        else -> location.label.ifBlank { titleFallback }
    }
    // Breadcrumb context for nested folders; never repeat the current title as its own subtitle.
    val subtitle = when {
        location.isHome -> null
        location.path.contains('/') -> location.path.substringBeforeLast('/')
        else -> location.label.takeIf { it.isNotBlank() && it != title }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ExplorerTopBar(
                title = title,
                subtitle = subtitle,
                searching = searchExpanded,
                query = query,
                filterActive = query.isNotBlank(),
                searchFocus = searchFocus,
                onQueryChange = { query = it },
                onSearchFocusChanged = { focused ->
                    if (focused) searchWasFocused = true
                    else if (searchWasFocused) {
                        searchExpanded = false
                        searchWasFocused = false
                    }
                },
                onOpenSearch = { searchExpanded = true },
                onCloseSearch = ::closeSearch,
                onBack = ::back,
                onNewFolder = if (location.isHome) null else ({
                    target = null
                    name = ""
                    action = ExplorerAction.FOLDER
                }),
                onUpload = if (location.isHome || onUploadFile == null) null else ({
                    pendingUpload = location
                    uploadPicker.launch(arrayOf("*/*"))
                }),
                isGrid = grid,
                onToggleLayout = { grid = !grid },
                onRefresh = { refresh++ }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            FadeGridListContent(isGrid = grid, modifier = Modifier.fillMaxSize()) { gridMode ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (gridMode) 2 else 1),
                    contentPadding = scrollableScaffoldPadding(
                        padding,
                        horizontal = 16.dp,
                        top = 8.dp,
                        bottom = 8.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().testTag("explorer-list")
                ) {
                    items(filtered, key = { "${it.location.sourceId}:${it.location.path}" }) { item ->
                        ExplorerItemCard(
                            item = item,
                            isGrid = gridMode,
                            modifier = Modifier.animateItem(),
                            onOpen = {
                                if (item.directory) {
                                    closeSearch()
                                    location = item.location
                                    query = ""
                                } else {
                                    closeSearch()
                                    scope.launch {
                                        try {
                                            onOpenFile(item)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            snackbar.showSnackbar(error.message ?: operationFailed)
                                        }
                                    }
                                }
                            },
                            onRename = {
                                target = item
                                name = item.name
                                action = ExplorerAction.RENAME
                            },
                            onDelete = {
                                target = item
                                action = ExplorerAction.DELETE
                            },
                            onDownload = if (!item.directory && onDownloadFile != null) {
                                ({ startDownload(item) })
                            } else null
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = loading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                LoadingIndicator(modifier = Modifier.size(48.dp).testTag("explorer-loading"))
            }
            AnimatedVisibility(
                visible = !loading && filtered.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                EmptyState(
                    icon = if (query.isBlank()) Icons.Outlined.FolderOpen else Icons.Default.Search,
                    message = stringResource(
                        if (query.isBlank()) R.string.explorer_empty else R.string.explorer_no_matches
                    )
                )
            }
        }
    }

    action?.let { currentAction ->
        if (currentAction == ExplorerAction.DELETE) {
            target?.let { item ->
                ExplorerDeleteDialog(
                    item = item,
                    onDismiss = { action = null },
                    onConfirm = {
                        action = null
                        runOperation { repository.delete(item) }
                    }
                )
            }
        } else {
            ExplorerNameDialog(
                title = stringResource(
                    if (currentAction == ExplorerAction.FOLDER) R.string.explorer_new_folder
                    else R.string.explorer_rename
                ),
                initialName = name,
                confirmLabel = stringResource(R.string.common_save),
                onDismiss = { action = null },
                onConfirm = { enteredName ->
                    val parent = location
                    val item = target
                    action = null
                    runOperation {
                        when (currentAction) {
                            ExplorerAction.FOLDER -> repository.createFolder(parent, enteredName)
                            ExplorerAction.RENAME -> repository.rename(requireNotNull(item), enteredName)
                            ExplorerAction.DELETE -> Unit
                        }
                    }
                }
            )
        }
    }
}
