package org.starfall.generative.features.machines.shared.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.subfeatures.explorer.ui.ExplorerScreen
import org.starfall.generative.features.machines.shared.logic.RemoteMachineExplorerRepository
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.shared.files.explorerImport
import org.starfall.generative.shared.files.explorerExport
import org.starfall.generative.shared.ui.media.MediaPreviewCover
import org.starfall.generative.shared.ui.media.isPreviewableMedia
import org.starfall.generative.shared.ui.media.mediaMimeType

/** Remote Machines owns SFTP browsing. No dependency on features/storage. */
@Composable
fun RemoteMachineFilesScreen(
    service: RemoteMachineService,
    machineId: String,
    onClose: () -> Unit,
    onOpenExternal: (Uri, String) -> Unit
) {
    val context = LocalContext.current
    val repository = remember(context, service, machineId) {
        RemoteMachineExplorerRepository(context, service, machineId)
    }
    val root = remember(repository) { repository.root }
    var viewing by remember { mutableStateOf<ExplorerItem?>(null) }

    Box(Modifier.fillMaxSize()) {
        ExplorerScreen(
            repository = repository,
            initialLocation = root,
            onClose = onClose,
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
                val mime = mediaMimeType(item.name)
                if (isPreviewableMedia(mime)) {
                    repository.documentUri(item)
                    viewing = item
                } else onOpenExternal(repository.documentUri(item), mime)
            }
        )
        viewing?.let { item ->
            MediaPreviewCover(
                reference = repository.documentUri(item).toString(),
                name = item.name,
                mime = mediaMimeType(item.name),
                onDismiss = { viewing = null }
            )
        }
    }
}
