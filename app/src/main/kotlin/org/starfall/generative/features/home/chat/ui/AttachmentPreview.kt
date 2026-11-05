package org.starfall.generative.features.home.chat.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.subfeatures.media.ui.ChatFilePreview
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.subfeatures.media.ui.MediaPreviewCover
import org.starfall.generative.shared.subfeatures.media.ui.mediaThumbnail
import org.starfall.generative.shared.subfeatures.media.ui.mediaMimeType
import org.starfall.generative.shared.ui.theme.LocalAmoledMode
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

internal data class AttachmentPreviewData(
    val name: String,
    val mimeType: String,
    val bitmap: ImageBitmap? = null,
    val durationMs: Long? = null,
    val missing: Boolean = false
)

@Composable
fun AttachmentStrip(
    references: List<String>,
    removable: Boolean,
    onRemove: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    selectedImageAttachments: List<String> = emptyList(),
    onToggleChatImage: ((String) -> Unit)? = null,
    inlinePreview: Boolean = false
) {
    if (references.isEmpty()) return
    if (inlinePreview && !removable) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            references.forEach { reference -> key(reference) {
                AttachmentTile(reference, false, {}, false, reference in selectedImageAttachments,
                    onToggleChatImage, inlinePreview = true)
            } }
        }
        return
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 0.dp)
    ) {
        items(references, key = { it }) { reference ->
            AttachmentTile(
                reference = reference,
                removable = removable,
                onRemove = { onRemove(reference) },
                compact = compact,
                selectedForChat = reference in selectedImageAttachments,
                onToggleChatImage = onToggleChatImage
            )
        }
    }
}

@Composable
private fun AttachmentTile(
    reference: String,
    removable: Boolean,
    onRemove: () -> Unit,
    compact: Boolean,
    selectedForChat: Boolean,
    onToggleChatImage: ((String) -> Unit)?,
    inlinePreview: Boolean = false
) {
    val context = LocalContext.current
    val foreground = LocalChatForeground.current
    var showPreview by remember(reference, foreground) { mutableStateOf(false) }
    val data by produceState<AttachmentPreviewData?>(initialValue = null, reference) {
        val preview = withContext(Dispatchers.IO) {
            val cached = AttachmentPreviewCache.get(reference)
            val readable = runCatching { AttachmentResolver(context).open(reference)?.use { true } == true }
                .getOrDefault(false)
            when {
                !readable -> (cached ?: loadPreview(context, reference)).copy(bitmap = null, missing = true)
                cached != null && !cached.missing -> cached
                else -> loadPreview(context, reference)
            }
        }
        AttachmentPreviewCache.put(reference, preview)
        value = preview
    }
    // An inline preview changes the item's height when it arrives; a fixed-size tile does not.
    TrackPendingWork(inlinePreview && data == null)
    TrackReadyAssets(if (inlinePreview && data != null) listOf(AssetKey.Attachment(reference)) else emptyList())
    val width = if (compact) 116.dp else 132.dp
    val height = if (compact) 72.dp else 92.dp
    val shape = RoundedCornerShape(if (compact) 26.dp else 18.dp)
    if (inlinePreview && data != null) {
        val preview = data!!
        ChatFilePreview(reference, preview.name, preview.mimeType, preview.bitmap, false,
            missing = preview.missing,
            selectedForChat = selectedForChat, onToggleAttachment = onToggleChatImage,
            playVideoInline = foreground, overlayActive = foreground,
            onOpen = { if (foreground) showPreview = true })
        if (foreground && showPreview) MediaPreviewCover(reference, preview.name, preview.mimeType) { showPreview = false }
        return
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .testTag("attachment-tile_$reference")
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (LocalAmoledMode.current) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                } else Modifier
            )
            .clickable(enabled = data != null && data?.missing != true) { showPreview = true }
    ) {
        val preview = data
        if (preview?.missing == true) {
            Text(stringResource(R.string.attachment_reference_missing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(8.dp))
        } else if (preview?.bitmap != null) {
            Image(
                bitmap = preview.bitmap,
                contentDescription = preview.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SharedIcon(
                    if (preview?.mimeType?.startsWith("audio/") == true) Icons.Outlined.Audiotrack
                    else Icons.Outlined.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    preview?.name ?: Uri.parse(reference).lastPathSegment.orEmpty(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (preview?.missing != true && (preview?.mimeType?.startsWith("video/") == true || preview?.mimeType?.startsWith("audio/") == true)) {
            Surface(modifier = Modifier.align(Alignment.Center), shape = CircleShape,
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f)) {
                SharedIcon(Icons.Default.PlayArrow, stringResource(R.string.attachment_play_media), Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
        preview?.takeUnless { it.missing }?.durationMs?.let { duration ->
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.56f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(7.dp)
            ) {
                Text(
                    formatDuration(duration),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }

        if (removable) {
            Surface(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(30.dp),
                shape = CircleShape,
                color = prominentContainerColor(opacityBoost = 0.42f),
                border = prominentContainerBorder()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("×", style = MaterialTheme.typography.titleLarge)
                }
            }
        } else if (data?.missing != true && data?.mimeType?.startsWith("image/") == true && onToggleChatImage != null) {
            FilledTonalIconToggleButton(
                checked = selectedForChat,
                onCheckedChange = { onToggleChatImage(reference) },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
                )
            ) {
                SharedIcon(if (selectedForChat) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    if (selectedForChat) stringResource(R.string.attachment_remove_from_next) else stringResource(R.string.attachment_attach_to_next))
            }
        }
    }
    val preview = data
    if (foreground && showPreview && preview != null && !preview.missing) {
        MediaPreviewCover(reference, preview.name, preview.mimeType) { showPreview = false }
    }
}

/** Decoded thumbnails kept in evictable RAM so reopened chats show their images without decoding again. */
internal object AttachmentPreviewCache {
    private const val MAX_KILOBYTES = 24 * 1024
    private val cache = SoftLruCache<String, AttachmentPreviewData>(MAX_KILOBYTES)

    fun get(reference: String): AttachmentPreviewData? = cache.get(reference)

    fun contains(reference: String): Boolean = cache.contains(reference)

    fun put(reference: String, data: AttachmentPreviewData) {
        val kilobytes = data.bitmap?.let { it.width.toLong() * it.height * 4 / 1024 }?.toInt() ?: 1
        cache.put(reference, data, kilobytes)
    }
}

private suspend fun loadPreview(context: Context, reference: String): AttachmentPreviewData {
    val uri = Uri.parse(reference)
    val metadata = runCatching { AttachmentResolver(context).metadata(reference) }.getOrNull()
    val name = metadata?.name ?: uri.lastPathSegment ?: context.getString(R.string.attachment_fallback_name)
    val mime = metadata?.mimeType.orEmpty()
    val bitmap = try { mediaThumbnail(context, reference, mime, 480) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    var duration: Long? = null
    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
        val retriever = MediaMetadataRetriever()
        runCatching {
            if (uri.scheme == "content") retriever.setDataSource(context, uri)
            else retriever.setDataSource(uri.path ?: reference)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
        runCatching { retriever.release() }
    }

    return AttachmentPreviewData(name, mime.ifBlank { mediaMimeType(name) }, bitmap, duration)
}

private fun formatDuration(durationMs: Long): String {
    val total = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = total / 60L
    val seconds = total % 60L
    return "%02d:%02d".format(minutes, seconds)
}
