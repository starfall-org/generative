package org.starfall.generative.features.home.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.starfall.generative.features.home.chat.logic.service.AssistantActionBlock
import org.starfall.generative.features.home.chat.logic.service.AssistantTimelineItem
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.subfeatures.media.ui.MediaFileCard
import org.starfall.generative.shared.subfeatures.media.ui.mediaMimeType
import org.starfall.generative.shared.ui.text.SelectionOnlyPlaceholder

internal sealed interface MediaPreviewItem {
    data class File(val name: String) : MediaPreviewItem
    data class Placeholder(val activity: ToolActivity) : MediaPreviewItem
}

/** One media block occupies its own position in the response layout. */
@Composable
internal fun AssistantMediaBlock(
    block: AssistantActionBlock?,
    files: List<String>,
    number: Int,
    isStreaming: Boolean,
    selectedImageAttachments: List<String>,
    onToggleChatImage: ((String) -> Unit)?,
    onDelete: (() -> Unit)?,
    mediaItems: List<MediaPreviewItem>
) {
    val foreground = LocalChatForeground.current
    if (block == null) {
        if (files.isEmpty()) return
        val context = LocalContext.current
        val store = remember(context) { ToolFiles(context) }
        SelectionOnlyPlaceholder("[MediaBlock$number]")
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { reference ->
                    if (reference.startsWith("tool-file:")) {
                        val name = reference.removePrefix("tool-file:")
                        MediaFileCard(store, name, imageOnly = true,
                            selectedForChat = store.resolve(name)?.path in selectedImageAttachments,
                            onToggleChatImage = onToggleChatImage,
                            overlayActive = foreground)
                    } else {
                        AttachmentStrip(listOf(reference), removable = false,
                            selectedImageAttachments = selectedImageAttachments,
                            onToggleChatImage = onToggleChatImage, inlinePreview = true)
                    }
                }
            }
            if (!isStreaming && onDelete != null) {
                Box(Modifier.matchParentSize()) {
                    BlockContextPadding(null, onDelete, Modifier.testTag("media-block-padding-$number"))
                }
            }
        }
        return
    }
    val context = LocalContext.current
    val store = remember(context) { ToolFiles(context) }
    val visibleFiles = mediaItems.filterIsInstance<MediaPreviewItem.File>()
    if (mediaItems.isNotEmpty()) {
        if (visibleFiles.isNotEmpty()) SelectionOnlyPlaceholder("[MediaBlock$number]")
        Box(Modifier.fillMaxWidth()) {
            DisableSelection {
                val columns = when {
                    mediaItems.size == 1 -> 1
                    mediaItems.size <= 6 -> 2
                    else -> 3
                }
                Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    mediaItems.chunked(columns).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { item ->
                                Box(Modifier.weight(1f)) {
                                    when (item) {
                                        is MediaPreviewItem.File -> key(item.name) {
                                            MediaFileCard(store, item.name, imageOnly = true,
                                                selectedForChat = store.resolve(item.name)?.path in selectedImageAttachments,
                                                onToggleChatImage = onToggleChatImage,
                                                playVideoInline = columns == 1,
                                                overlayActive = foreground)
                                        }
                                        is MediaPreviewItem.Placeholder -> key(item.activity.id) {
                                            GeneratedMediaPlaceholder(item.activity)
                                        }
                                    }
                                }
                            }
                            repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (!isStreaming && onDelete != null) {
                Box(Modifier.matchParentSize()) {
                    BlockContextPadding(null, onDelete,
                        Modifier.testTag("media-block-padding-$number"))
                }
            }
        }
    }
}

/** Pure media selection; callers remove duplicates once before composing blocks. */
internal fun assistantMediaPreviewItems(
    block: AssistantActionBlock,
    isStreaming: Boolean
): List<MediaPreviewItem> {
    return block.items.filterIsInstance<AssistantTimelineItem.Tool>()
        .flatMap { item ->
            val activity = item.activity
            val activityFiles = if (activity.name == "send_file" && activity.inlineMedia) {
                activity.files
            } else if (activity.inlineMedia || activity.responsePreviewLimit == 0 &&
                activity.name in listOf("generate_image", "generate_video")) {
                activity.files.filter { file ->
                    val mime = mediaMimeType(file.removePrefix("tool-file:"))
                    mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")
                }
            } else if (activity.responsePreviewLimit > 0) emptyList()
            else activity.files.filterNot { it.endsWith(".json", ignoreCase = true) }
            val files = activityFiles.map { it.removePrefix("tool-file:") }
            // Preserve each call's position when its pending tile becomes a file or failure.
            when {
                activityFiles.isNotEmpty() -> files.map { MediaPreviewItem.File(it) }
                activity.name in setOf("generate_image", "generate_video") &&
                    (activity.status == "error" || isStreaming && activity.status == "running") ->
                    listOf(MediaPreviewItem.Placeholder(activity))
                else -> emptyList()
            }
        }
}
