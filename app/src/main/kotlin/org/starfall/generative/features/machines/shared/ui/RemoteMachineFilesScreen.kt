package org.starfall.generative.features.machines.shared.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.subfeatures.explorer.ui.ExplorerScreen
import org.starfall.generative.features.machines.shared.logic.RemoteMachineExplorerRepository
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.integrations.remote.RemoteSftpTransferService
import org.starfall.generative.shared.subfeatures.media.ui.MediaPreviewCover
import org.starfall.generative.shared.subfeatures.media.ui.isPreviewableMedia
import org.starfall.generative.shared.subfeatures.media.ui.mediaMimeType

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
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(machineId) {
        RemoteSftpTransferService.uploadsCompleted.collect { completedMachine ->
            if (completedMachine == machineId) refreshKey++
        }
    }
    var permissionRequested by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    fun requestNotificationPermission() {
        if (!permissionRequested && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            permissionRequested = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(Modifier.fillMaxSize()) {
        ExplorerScreen(
            repository = repository,
            initialLocation = root,
            onClose = onClose,
            refreshKey = refreshKey,
            showTransferLoading = false,
            onUploadFiles = { parent, uris ->
                repository.enqueueUploads(parent, uris)
                requestNotificationPermission()
            },
            onDownloadFile = { item, uri ->
                repository.enqueueDownload(item, uri)
                requestNotificationPermission()
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
