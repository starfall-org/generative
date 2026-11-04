package org.starfall.generative.shared.ui.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import org.starfall.generative.shared.ui.text.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.shared.files.AttachmentResolver
import org.starfall.generative.shared.theme.LocalAmoledMode
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.layout.windowHeightIn

@Composable
internal fun BoundedMediaFrame(ratio: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val frameShape = RoundedCornerShape(16.dp)
    BoxWithConstraints(modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.5f), contentAlignment = Alignment.Center) {
        val safeRatio = ratio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
        val frameHeight = minOf(maxWidth / safeRatio, maxHeight)
        Box(
            Modifier
                .fillMaxWidth()
                .height(frameHeight)
                .clip(frameShape)
                .background(Color.Black)
                .then(
                    if (LocalAmoledMode.current) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, frameShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

/** One presentation for attachments, generated media and files explicitly sent by tools. */
@Composable
fun ChatFilePreview(
    reference: String,
    name: String,
    mime: String,
    thumbnail: ImageBitmap?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    missing: Boolean = false,
    selectedForChat: Boolean = false,
    onToggleAttachment: ((String) -> Unit)? = null,
    playVideoInline: Boolean = true,
    overlayActive: Boolean = true,
    onOpen: () -> Unit
) {
    if (missing) {
        BoundedMediaFrame(if (mime.startsWith("image/")) 1f else 16f / 9f, modifier) {
            Text(stringResource(R.string.attachment_reference_missing),
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(12.dp))
        }
        return
    }
    val toggle = onToggleAttachment
    val selected = selectedForChat
    val actions: @Composable BoxScope.() -> Unit = {
        FileCornerActions(reference, name, mime, selected, toggle,
            Modifier.align(Alignment.TopEnd).padding(6.dp), overlayActive)
    }
    when {
        mime.startsWith("image/") -> BoundedMediaFrame(thumbnail?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f, modifier) {
            Box(Modifier.fillMaxSize().clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
                thumbnail?.let { Image(it, stringResource(R.string.media_generated_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (loading) LoadingIndicator(Modifier.size(24.dp))
                else if (thumbnail == null) Text(stringResource(R.string.media_image_preview_unavailable), color = Color.White)
            }
            actions()
        }
        mime.startsWith("video/") && playVideoInline -> InlineVideoPreview(reference, thumbnail, modifier, overlay = actions)
        mime.startsWith("video/") -> BoundedMediaFrame(
            thumbnail?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 9f), modifier
        ) {
            Box(Modifier.fillMaxSize().clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
                thumbnail?.let {
                    Image(it, stringResource(R.string.media_video_thumbnail), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = Color.Black.copy(alpha = 0.68f),
                    modifier = Modifier.size(44.dp)
                ) {
                    SharedIcon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = stringResource(R.string.media_open_video),
                        tint = Color.White,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            actions()
        }
        else -> Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = prominentContainerColor(),
            border = prominentContainerBorder()
        ) {
            Box {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 48.dp, bottom = 10.dp)) {
                    if (mime.startsWith("audio/")) InlineAudioPreview(reference)
                    else Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpen)
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SharedIcon(Icons.Outlined.InsertDriveFile, null)
                        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                actions()
            }
        }
    }
}

@Composable
private fun FileCornerActions(reference: String, name: String, mime: String, selected: Boolean,
    onToggle: ((String) -> Unit)?, modifier: Modifier, overlayActive: Boolean) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val scope = rememberCoroutineScope()
    var downloading by remember(reference) { mutableStateOf(false) }
    var saveError by remember(reference, overlayActive) { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) scope.launch {
            downloading = true
            try {
                withContext(Dispatchers.IO) {
                    val input = AttachmentResolver(context).open(reference) ?: error(localizedResources.getString(R.string.attachment_file_unavailable))
                    input.use { source ->
                        (context.contentResolver.openOutputStream(uri) ?: error(localizedResources.getString(R.string.attachment_cannot_save_file))).use { output ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                Toast.makeText(context, R.string.file_download_saved, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { saveError = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.toString()
            } finally { downloading = false }
        }
    }
    val image = mime.startsWith("image/")
    val selectionLabel = if (image) {
        if (selected) stringResource(R.string.attachment_remove_from_next) else stringResource(R.string.attachment_attach_to_next)
    } else stringResource(if (selected) R.string.file_remove_next_message else R.string.file_attach_next_message)
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.62f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = !downloading, onClick = { save.launch(name) }, modifier = Modifier.size(40.dp)) {
                SharedIcon(Icons.Outlined.Download, stringResource(R.string.file_download), tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Checkbox(selected, onCheckedChange = { onToggle?.invoke(reference) }, enabled = onToggle != null,
                modifier = Modifier.size(40.dp).semantics { contentDescription = selectionLabel },
                colors = CheckboxDefaults.colors(checkedColor = Color.White, uncheckedColor = Color.White,
                    checkmarkColor = Color.Black, disabledUncheckedColor = Color.Gray))
        }
    }
    saveError?.takeIf { overlayActive }?.let { error ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.file_download_failed)) },
            scrollableContent = false,
            text = {
                Column(Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.6f).verticalScroll(rememberScrollState())) {
                    Text(name, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer { Text(error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(localizedResources.getString(R.string.file_download_failed), error))
                    saveError = null
                }) { Text(stringResource(R.string.common_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { saveError = null }) { Text(stringResource(R.string.common_close)) }
            }
        )
    }
}
