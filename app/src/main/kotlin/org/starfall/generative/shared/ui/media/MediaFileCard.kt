package org.starfall.generative.shared.ui.media

import org.starfall.generative.R
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.media.ChatFilePreview
import org.starfall.generative.shared.ui.media.MediaContent
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.windowHeightIn
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.media.isPreviewableMedia
import org.starfall.generative.shared.ui.media.mediaMimeType
import org.starfall.generative.shared.ui.media.mediaThumbnail
import org.starfall.generative.shared.ui.code.CodeBlockSurface
import org.starfall.generative.shared.code.codeLanguage

private fun File.mime() = mediaMimeType(name)
private fun String.mimeForMissingFile() = mediaMimeType(this)
private const val TEXT_PREVIEW_LIMIT = 200_000
private val textFileExtensions = setOf(
    "txt", "log", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "svg", "html", "htm", "css", "scss",
    "js", "mjs", "cjs", "jsx", "ts", "tsx", "py", "kt", "kts", "java", "c", "h", "cpp", "cc", "hpp", "cs",
    "rs", "go", "swift", "rb", "php", "sh", "bash", "zsh", "sql", "lua", "r", "yaml", "yml", "toml", "ini",
    "conf", "cfg", "properties", "gradle", "env", "tex", "rst", "diff", "patch"
)
internal fun File.isTextFile(): Boolean {
    val mime = mime()
    return extension.lowercase() in textFileExtensions || name.equals("Dockerfile", ignoreCase = true) ||
        mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" ||
        mime == "application/javascript" || mime == "application/x-yaml"
}
private fun File.codeLanguageName(): String = when {
    name.equals("Dockerfile", ignoreCase = true) -> "dockerfile"
    else -> when (val ext = extension.lowercase()) {
        "h" -> "c"
        "jsonl" -> "json"
        "conf", "cfg", "properties", "env" -> "ini"
        "gradle" -> "kotlin"
        else -> codeLanguage(ext)
    }
}

@Composable
fun MediaFileCard(
    store: ToolFiles,
    name: String,
    imageOnly: Boolean = false,
    selectedForChat: Boolean = false,
    onToggleChatImage: ((String) -> Unit)? = null,
    playVideoInline: Boolean = true,
    overlayActive: Boolean = true
) {
    val context = LocalContext.current
    val revision by ToolFiles.revision.collectAsStateWithLifecycle()
    val file = remember(name, revision) { store.resolve(name) }
    var view by remember(name, overlayActive) { mutableStateOf(false) }
    var bitmap by remember(name) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var loaded by remember(name) { mutableStateOf(false) }
    LaunchedEffect(name, revision) {
        loaded = false
        bitmap = file?.let { mediaThumbnail(context, it.path, it.mime(), 720) }
        loaded = true
    }
    if (file != null) Column {
        ChatFilePreview(file.path, name, file.mime(), bitmap, !loaded,
            selectedForChat = selectedForChat, onToggleAttachment = onToggleChatImage,
            playVideoInline = playVideoInline && overlayActive,
            overlayActive = overlayActive, onOpen = { if (overlayActive) view = true })
        if (!imageOnly) Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (imageOnly) ChatFilePreview(
        File(store.directory, name).absolutePath, name, name.mimeForMissingFile(), null, false,
        missing = true, overlayActive = overlayActive, onOpen = {}
    ) else Text(stringResource(R.string.file_deleted), style = MaterialTheme.typography.bodySmall)
    if (overlayActive && view && file != null) MediaViewer(
        store,
        file,
        selectedForChat = selectedForChat,
        onToggleAttachment = onToggleChatImage
    ) { view = false }
}

@Composable
internal fun MediaViewer(
    store: ToolFiles, file: File,
    onDelete: suspend () -> Unit = { withContext(Dispatchers.IO) { store.delete(listOf(file.name)) } },
    selectedForChat: Boolean = false,
    onToggleAttachment: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val scope = rememberCoroutineScope()
    val mime = remember(file.name) { file.mime() }
    val textual = remember(file.name) { file.isTextFile() }
    val language = remember(file.name) { file.codeLanguageName() }
    var truncated by remember(file.path) { mutableStateOf(false) }
    val text by produceState<String?>(null, file.path, file.lastModified()) {
        if (textual) value = withContext(Dispatchers.IO) {
            runCatching {
                file.reader().use { reader ->
                    val buffer = CharArray(TEXT_PREVIEW_LIMIT + 1)
                    var count = 0
                    while (count < buffer.size) {
                        val read = reader.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    truncated = count > TEXT_PREVIEW_LIMIT
                    String(buffer, 0, minOf(count, TEXT_PREVIEW_LIMIT))
                }
            }.getOrDefault(localizedResources.getString(R.string.file_deleted))
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                }
            }
            Toast.makeText(context, localizedResources.getString(if (result.isSuccess) R.string.file_download_saved else R.string.file_download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    val share: () -> Unit = {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.tool-files", file)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    localizedResources.getString(R.string.media_share_file)
                )
            )
        }.onFailure {
            Toast.makeText(context, localizedResources.getString(R.string.media_could_not_share_file), Toast.LENGTH_SHORT).show()
        }
    }
    val actions = buildList {
        onToggleAttachment?.let { toggle ->
            add(
                MediaViewerAction(
                    label = stringResource(
                        if (selectedForChat) R.string.file_remove_next_message else R.string.file_attach_next_message
                    ),
                    icon = if (selectedForChat) Icons.Outlined.CheckCircle else Icons.Outlined.AttachFile,
                    onClick = { toggle(file.path) }
                )
            )
        }
        add(
            MediaViewerAction(
                label = stringResource(R.string.media_save),
                icon = Icons.Outlined.Save,
                onClick = { save.launch(file.name) }
            )
        )
        add(MediaViewerAction(stringResource(R.string.media_share), Icons.Outlined.Share, share))
        add(
            MediaViewerAction(
                label = stringResource(R.string.common_delete),
                icon = Icons.Outlined.Delete,
                onClick = { confirmDelete = true },
                tint = MaterialTheme.colorScheme.error
            )
        )
    }

    MediaViewerCover(
        mediaKey = file.path,
        onDismiss = onDismiss,
        actions = actions,
        content = { onZoomChanged ->
            if (isPreviewableMedia(mime)) {
                MediaContent(file.path, mime, onImageZoomChanged = onZoomChanged)
            } else MediaTapShield {
                LazyColumn(Modifier.windowHeightIn(maxFraction = 0.8f)) {
                    item {
                        when {
                            textual -> text?.let { content ->
                                CodeBlockSurface(language = language, code = content)
                                if (truncated) Text(
                                    stringResource(R.string.media_preview_truncated, TEXT_PREVIEW_LIMIT),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.ui.graphics.Color.White
                                )
                            } ?: Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                LoadingIndicator()
                            }
                            else -> Text(
                                stringResource(R.string.media_preview_unsupported),
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                }
            }
        }
    )

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.dialog_delete_file_title)) },
        text = { Text(stringResource(R.string.media_delete_file_message)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    try {
                        onDelete()
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Toast.makeText(context, error.message ?: localizedResources.getString(R.string.file_deleted),
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = {
            TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_close)) }
        }
    )
}
