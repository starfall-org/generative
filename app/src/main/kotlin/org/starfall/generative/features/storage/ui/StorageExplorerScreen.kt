package org.starfall.generative.features.storage.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerLocation
import org.starfall.generative.shared.subfeatures.explorer.ui.ExplorerScreen
import org.starfall.generative.features.storage.logic.MediaStorageExplorerRepository
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.files.explorerImport
import org.starfall.generative.shared.files.explorerExport
import org.starfall.generative.shared.ui.media.MediaViewer
import org.starfall.generative.shared.ui.media.isPreviewableMedia
import org.starfall.generative.shared.ui.media.isTextFile
import org.starfall.generative.shared.ui.media.mediaMimeType

/** The Storage feature owns its browsing and viewer lifetime, not the explorer extension. */
@Composable
fun StorageExplorerScreen(
    onClose: () -> Unit,
    onOpenExternal: (Uri, String) -> Unit
) {
    val context = LocalContext.current
    val repository = remember(context) { MediaStorageExplorerRepository(context) }
    val store = remember(context) { ToolFiles(context) }
    var viewing by remember { mutableStateOf<ExplorerItem?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        ExplorerScreen(
            repository = repository,
            initialLocation = ExplorerLocation(),
            onClose = onClose,
            refreshKey = refreshKey,
            onUploadFile = { parent, uri ->
                context.contentResolver.explorerImport(uri) { name, input ->
                    repository.upload(parent, name, input)
                }
            },
            onDownloadFile = { item, uri ->
                context.contentResolver.explorerExport(uri) { output ->
                    repository.download(item, output)
                }
            },
            onOpenFile = { item ->
                val file = repository.fileFor(item)
                val mime = mediaMimeType(item.name)
                if (isPreviewableMedia(mime) || file.isTextFile()) viewing = item
                else onOpenExternal(repository.documentUri(item), mime)
            }
        )
        viewing?.let { item ->
            MediaViewer(
                store = store,
                file = repository.fileFor(item),
                onDelete = { repository.delete(item); viewing = null; refreshKey++ },
                onDismiss = { viewing = null }
            )
        }
    }
}
