package org.starfall.generative.navigation

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.features.appdata.logic.AppDataTransferService
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.shared.model.BackupExportKind

internal val NullableFilePathSaver = Saver<File?, String>(
    save = { it?.absolutePath },
    restore = { path -> path.takeIf { it.isNotBlank() }?.let(::File) }
)

internal data class AppDataActions(
    val exportData: () -> Unit,
    val importData: () -> Unit,
    val exportInProgress: Boolean,
    val importInProgress: Boolean
)

/** Root-owned launchers survive navigation away from App Data while a document picker is open. */
@Composable
internal fun rememberAppDataActions(
    viewModel: ChatViewModel,
    appDataTransfer: AppDataTransferService
): AppDataActions {
    val context = LocalContext.current
    var pendingExport by rememberSaveable(stateSaver = NullableFilePathSaver) { mutableStateOf<File?>(null) }
    var exportPickerOpen by rememberSaveable { mutableStateOf(false) }
    var pendingImport by rememberSaveable { mutableStateOf(false) }
    val preparingExport by appDataTransfer.preparingExport.collectAsStateWithLifecycle()
    val preparedExport by appDataTransfer.preparedExport.collectAsStateWithLifecycle()
    val importInProgress by appDataTransfer.importInProgress.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val archive = pendingExport
        val wasWaiting = exportPickerOpen
        exportPickerOpen = false
        if (uri == null || !wasWaiting) {
            pendingExport = null
            archive?.delete()
            return@rememberLauncherForActivityResult
        }
        if (archive == null || !archive.isFile) {
            pendingExport = null
            Toast.makeText(context, R.string.export_write_failed, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        appDataTransfer.writeExportToUri(uri, archive)
        pendingExport = null
    }

    LaunchedEffect(preparedExport, exportPickerOpen) {
        if (preparedExport != null && !exportPickerOpen && pendingExport == null) {
            val archive = appDataTransfer.consumePreparedExport() ?: return@LaunchedEffect
            pendingExport = archive
            exportPickerOpen = true
            try {
                exportLauncher.launch(BackupExportKind.FULL_BACKUP.fileName)
            } catch (_: Exception) {
                exportPickerOpen = false
                pendingExport = null
                archive.delete()
                Toast.makeText(context, R.string.export_write_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(exportPickerOpen, pendingExport?.absolutePath, preparedExport, preparingExport) {
        if (!exportPickerOpen && pendingExport == null && preparedExport == null && !preparingExport) {
            withContext(Dispatchers.IO) {
                appDataTransfer.cleanupAbandonedExports()
            }
        }
    }

    fun exportData() {
        if (preparingExport || exportPickerOpen || pendingExport != null || importInProgress) return
        appDataTransfer.prepareExport()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val shouldImport = pendingImport
        pendingImport = false
        if (uri == null || !shouldImport || importInProgress) return@rememberLauncherForActivityResult
        appDataTransfer.importFromUri(
            uri = uri,
            beforeFullRestore = viewModel::prepareForRestore,
            afterFullRestore = viewModel::finishRestore
        )
    }

    fun importData() {
        if (pendingImport || importInProgress || preparingExport || exportPickerOpen) return
        pendingImport = true
        try {
            importLauncher.launch(arrayOf("application/zip", "application/json", "text/*"))
        } catch (_: Exception) {
            pendingImport = false
            Toast.makeText(context, R.string.export_open_file_failed, Toast.LENGTH_LONG).show()
        }
    }
    return AppDataActions(::exportData, ::importData, preparingExport, importInProgress)
}
