package org.starfall.generative.home.chat.ui


import org.starfall.generative.shared.ui.text.GatedSelectionContainer
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.starfall.generative.shared.ui.media.MediaFileCard
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.countedVersionIndices
import org.starfall.generative.shared.ui.icons.FluentIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.RoundedDropdownMenuItem as DropdownMenuItem
import org.starfall.generative.shared.ui.media.mediaMimeType
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.home.chat.ui.markdown.MarkdownRenderer
import org.starfall.generative.home.chat.ui.markdown.StreamingMarkdownRenderer
import org.starfall.generative.home.shared.ui.LocalToolSheetState
import org.starfall.generative.home.shared.ui.ToolSheetState
import org.starfall.generative.home.shared.ui.ToolDetailsSheetHost

@Composable
fun AssistantMessageCard(
    message: StoredMessage,
    isStreaming: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onRetryError: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRead: () -> Unit,
    onSwitchVersion: (Int) -> Unit,
    selectedImageAttachments: List<String> = emptyList(),
    onToggleChatImage: ((String) -> Unit)? = null,
    isReading: Boolean = false,
    fallbackModelName: String = "",
    modelIcon: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showMoreMenu by remember { mutableStateOf(false) }
    val activeVersion = message.activeVersion
    val generatedModelName = activeVersion.modelDisplayName.ifBlank { activeVersion.modelId.ifBlank { fallbackModelName } }
    val generationError = activeVersion.generationError.orEmpty()
    val toolbarText = message.content.ifBlank { generationError }
    val versionIndices = remember(message.versions, message.activeVersionIndex) {
        val counted = message.countedVersionIndices()
        if (message.activeVersionIndex in counted) counted
        else listOf(message.activeVersionIndex) + counted
    }
    val displayedVersionIndex = versionIndices.indexOf(message.activeVersionIndex).coerceAtLeast(0)
    val processingDurationMillis = activeVersion.processingFinishedAt?.let { finishedAt ->
        activeVersion.timestamp.toLongOrNull()?.let { startedAt -> (finishedAt - startedAt).coerceAtLeast(0L) }
    }

    val toolSheetState = remember(message.id) { ToolSheetState() }
    ToolDetailsSheetHost(toolSheetState, activeVersion.toolActivity)

    CompositionLocalProvider(LocalToolSheetState provides toolSheetState) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (isStreaming && message.content.isBlank() && activeVersion.toolActivity.none { it.files.isNotEmpty() }) {
            StreamingProcessingPreview(activeVersion)
        } else {
            CompletedAssistantContent(
                version = activeVersion,
                processingDurationMillis = processingDurationMillis.takeUnless { isStreaming },
                animateStreamingContent = isStreaming,
                selectedImageAttachments = selectedImageAttachments,
                onToggleChatImage = onToggleChatImage
            )
        }
        if (isStreaming) {
            GenerationLoadingIndicator(
                modelName = generatedModelName,
                modelId = activeVersion.modelId,
                modelIcon = modelIcon,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        activeVersion.generationError?.let { error ->
            GenerationErrorBlock(
                error = error,
                onRetry = onRetryError,
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Error", error))
                    Toast.makeText(context, "Error copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (activeVersion.files.isNotEmpty()) {
            AttachmentStrip(activeVersion.files, removable = false, modifier = Modifier.padding(top = 8.dp),
                selectedImageAttachments = selectedImageAttachments, onToggleChatImage = onToggleChatImage,
                inlinePreview = true)
        }

        if (!isStreaming && versionIndices.size > 1) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (displayedVersionIndex > 0) {
                            onSwitchVersion(versionIndices[displayedVersionIndex - 1])
                        }
                    },
                    enabled = displayedVersionIndex > 0,
                    modifier = Modifier.size(30.dp)
                ) {
                    FluentIcon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous version",
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = "${displayedVersionIndex + 1}/${versionIndices.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = {
                        if (displayedVersionIndex < versionIndices.lastIndex) {
                            onSwitchVersion(versionIndices[displayedVersionIndex + 1])
                        }
                    },
                    enabled = displayedVersionIndex < versionIndices.lastIndex,
                    modifier = Modifier.size(30.dp)
                ) {
                    FluentIcon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next version",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // A failed response can have no normal assistant content and therefore is not treated as
        // a response version by the message actions. It still needs the same toolbar so users can
        // copy/share/delete/edit the error instead of being left with only the error card.
        if (!isStreaming && toolbarText.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MessageActionButton(onClick = onRegenerate, contentDescription = "Regenerate") {
                    FluentIcon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                MessageActionButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                         putExtra(Intent.EXTRA_TEXT, toolbarText)
                        }
                        context.startActivity(Intent.createChooser(share, "Share response"))
                    },
                    contentDescription = "Share"
                ) {
                    FluentIcon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                MessageActionButton(onClick = {
                    if (message.content.isNotBlank()) {
                        onCopy()
                    } else {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Error", toolbarText))
                        Toast.makeText(context, "Error copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                }, contentDescription = "Copy") {
                    FluentIcon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                }

                Box {
                    MessageActionButton(
                        onClick = { showMoreMenu = true },
                        contentDescription = "More"
                    ) {
                        FluentIcon(Icons.Default.MoreHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { FluentIcon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { FluentIcon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                onDelete()
                            }
                        )
                    }
                }

                if (generatedModelName.isNotBlank()) {
                    Text(generatedModelName,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else Spacer(modifier = Modifier.weight(1f))

                MessageActionButton(onClick = onRead, contentDescription = if (isReading) "Stop reading" else "Read aloud") {
                    FluentIcon(if (isReading) Icons.Outlined.StopCircle else Icons.Outlined.VolumeUp,
                        contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = if (isReading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    }
}

@Composable
private fun GenerationErrorBlock(
    error: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    var expanded by remember(error) { mutableStateOf(false) }
    val errorFontSize = (LocalCodeRenderingPreferences.current.effectiveMessageFontSize * 0.78f).sp

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                FluentIcon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Generation error",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.weight(1f))
                if (onRetry != null) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                        FluentIcon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Retry from error",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    FluentIcon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy error",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                FluentIcon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse error" else "Expand error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            GatedSelectionContainer {
                Text(
                    text = error,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = errorFontSize,
                    lineHeight = errorFontSize * 1.44f
                ),
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private sealed interface ProcessingTimelineItem {
    val contentOffset: Int

    data class Thinking(
        override val contentOffset: Int,
        val reasoning: String
    ) : ProcessingTimelineItem

    data class Tool(val activity: ToolActivity) : ProcessingTimelineItem {
        override val contentOffset: Int get() = activity.contentOffset
    }
}

private fun buildProcessingTimeline(version: MessageVersion): List<ProcessingTimelineItem> {
    val activities = version.toolActivity.filterNot { it.name.endsWith(": connect") }
    val reasoning = version.reasoningContent.orEmpty()

    if (activities.isNotEmpty() && activities.all { it.reasoningOffset == null }) {
        return buildList {
            if (reasoning.isNotBlank()) add(ProcessingTimelineItem.Thinking(0, reasoning))
            activities.forEach { add(ProcessingTimelineItem.Tool(it)) }
        }
    }

    val timeline = mutableListOf<ProcessingTimelineItem>()
    var reasoningCursor = 0
    activities.forEach { activity ->
        val reasoningEnd = (activity.reasoningOffset ?: reasoningCursor)
            .coerceIn(reasoningCursor, reasoning.length)
        if (reasoningEnd > reasoningCursor) {
            timeline += ProcessingTimelineItem.Thinking(
                activity.contentOffset.coerceIn(0, version.content.length),
                reasoning.substring(reasoningCursor, reasoningEnd)
            )
        }
        timeline += ProcessingTimelineItem.Tool(activity)
        reasoningCursor = reasoningEnd
    }
    if (reasoningCursor < reasoning.length) {
        timeline += ProcessingTimelineItem.Thinking(
            activities.lastOrNull()?.contentOffset?.coerceIn(0, version.content.length) ?: 0,
            reasoning.substring(reasoningCursor)
        )
    }
    return timeline
}

private fun suppressToolResponseEchoes(text: String, activities: List<ToolActivity>): String {
    if (text.isEmpty()) return text
    val candidates = activities.asSequence()
        .filter { it.responsePreviewLimit > 0 }
        .flatMap { activity ->
            sequence {
                val response = runCatching { Json.parseToJsonElement(activity.response) }.getOrNull()
                if (response != null) {
                    yieldAll(longJsonStrings(response))
                } else {
                    yield(activity.response)
                }
            }
        }
        .filter { it.length >= 256 }
        .distinct()
        .sortedByDescending(String::length)
        .take(32)
        .toList()
    if (candidates.isEmpty()) return text

    val redacted = text.toCharArray()
    for (candidate in candidates) {
        var start = text.indexOf(candidate)
        while (start >= 0) {
            val end = start + candidate.length
            for (index in start until end) {
                if (redacted[index] != '\n' && redacted[index] != '\r') redacted[index] = ' '
            }
            start = text.indexOf(candidate, end)
        }
    }
    return String(redacted)
}

private fun longJsonStrings(element: JsonElement): Sequence<String> = sequence {
    when (element) {
        is JsonObject -> element.values.forEach { yieldAll(longJsonStrings(it)) }
        is JsonArray -> element.forEach { yieldAll(longJsonStrings(it)) }
        is JsonPrimitive -> if (element.isString && element.content.length >= 256) yield(element.content)
    }
}

@Composable
private fun StreamingProcessingPreview(version: MessageVersion) {
    val items = remember(version.reasoningContent, version.toolActivity, version.content.length) {
        buildProcessingTimeline(version).map { it.toDropdownItem() }
    }
    // Reuse the finished-message dropdown so its expand/collapse header is available while the model
    // is still running (before any answer text) and nothing jumps when the first text arrives.
    // ProcessingDropdown keeps the newest items and any running tool visible while collapsed.
    ProcessingDropdown(
        items = items,
        durationMillis = null,
        startNumber = 1,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        isStreaming = true,
        previewCount = 2
    )
}

@Composable
private fun SmoothStreamingMarkdownMessage(content: String, isStreaming: Boolean) {
    StreamingMarkdownRenderer(content = content, isStreaming = isStreaming)
}

private data class ProcessingBlock(
    val offset: Int,
    val items: List<ProcessingDropdownItem>
)

@Composable
private fun CompletedAssistantContent(
    version: MessageVersion,
    processingDurationMillis: Long?,
    animateStreamingContent: Boolean,
    selectedImageAttachments: List<String>,
    onToggleChatImage: ((String) -> Unit)?
) {
    val context = LocalContext.current
    val store = remember(context) { ToolFiles(context) }
    val blocks = remember(version.content, version.reasoningContent, version.toolActivity) {
        buildProcessingBlocks(version)
    }
    val content = remember(version.content, version.toolActivity) {
        suppressToolResponseEchoes(version.content, version.toolActivity)
    }
    val shownFiles = mutableSetOf<String>()

    SelectionContainer {
        Column {
            if (blocks.isEmpty()) {
                if (content.isNotBlank()) {
                    SmoothStreamingMarkdownMessage(content, isStreaming = animateStreamingContent)
                }
            } else {
                var cursor = 0
                var nextItemNumber = 1
                blocks.forEach { block ->
                    val offset = block.offset.coerceIn(cursor, content.length)
                    if (offset > cursor) {
                        val textBefore = content.substring(cursor, offset).trimEnd()
                        if (textBefore.isNotBlank()) {
                            FormattedMarkdownMessage(content = textBefore)
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    val visibleProcessingItems = block.items.filterNot { item ->
                        item is ProcessingDropdownItem.Tool && item.activity.responsePreviewLimit == 0 &&
                            item.activity.name in listOf("generate_image", "generate_video") &&
                            item.activity.status == "success" && item.activity.files.any {
                                mediaMimeType(it.removePrefix("tool-file:")).startsWith(
                                    if (item.activity.name == "generate_video") "video/" else "image/"
                                )
                            }
                    }
                    key(block.offset, block.items.map { item ->
                        when (item) {
                            is ProcessingDropdownItem.Thinking -> "thinking:${item.reasoning.hashCode()}"
                            is ProcessingDropdownItem.Tool -> "tool:${item.activity.id}"
                        }
                    }) {
                        ProcessingDropdown(
                            items = visibleProcessingItems,
                            durationMillis = processingDurationMillis.takeIf { blocks.size == 1 },
                            startNumber = nextItemNumber,
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                            previewCount = if (animateStreamingContent && block === blocks.last()) 2 else 0
                        )
                    }
                    val files = block.items.filterIsInstance<ProcessingDropdownItem.Tool>()
                        .flatMap { item ->
                            val activity = item.activity
                            if (activity.name == "send_file" && activity.inlineMedia) {
                                activity.files
                            } else if (activity.inlineMedia || activity.responsePreviewLimit == 0 &&
                                activity.name in listOf("generate_image", "generate_video")) {
                                activity.files.filter { file ->
                                    val mime = mediaMimeType(file.removePrefix("tool-file:"))
                                    mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")
                                }
                            } else if (activity.responsePreviewLimit > 0) emptyList()
                            else activity.files.filterNot { it.endsWith(".json", ignoreCase = true) }
                        }
                        .map { it.removePrefix("tool-file:") }
                        .filter { shownFiles.add(it) }
                    if (files.isNotEmpty()) {
                        val columns = when {
                            files.size == 1 -> 1
                            files.size <= 6 -> 2
                            else -> 3
                        }
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            files.chunked(columns).forEach { rowFiles ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowFiles.forEach { name ->
                                        Box(Modifier.weight(1f)) {
                                            key(name) {
                                                MediaFileCard(store, name, imageOnly = true,
                                                    selectedForChat = store.resolve(name)?.path in selectedImageAttachments,
                                                    onToggleChatImage = onToggleChatImage,
                                                    playVideoInline = columns == 1)
                                            }
                                        }
                                    }
                                    repeat(columns - rowFiles.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    nextItemNumber += block.items.count { it !is ProcessingDropdownItem.Tool || it.activity.name != "send_file" }
                    cursor = offset
                }

                if (cursor < content.length) {
                    val remaining = content.substring(cursor)
                    if (remaining.isNotBlank()) {
                        SmoothStreamingMarkdownMessage(remaining, isStreaming = animateStreamingContent)
                    }
                }
            }
        }
    }
}

private fun ProcessingTimelineItem.toDropdownItem(): ProcessingDropdownItem = when (this) {
    is ProcessingTimelineItem.Thinking -> ProcessingDropdownItem.Thinking(reasoning)
    is ProcessingTimelineItem.Tool -> ProcessingDropdownItem.Tool(activity)
}

private fun buildProcessingBlocks(version: MessageVersion): List<ProcessingBlock> {
    val timeline = buildProcessingTimeline(version)
    if (timeline.isEmpty()) return emptyList()

    val content = version.content
    val blocks = mutableListOf<ProcessingBlock>()
    var groupOffset = timeline.first().contentOffset.coerceIn(0, content.length)
    var previousOffset = groupOffset
    var groupItems = mutableListOf(timeline.first().toDropdownItem())

    timeline.drop(1).forEach { item ->
        val itemOffset = item.contentOffset.coerceIn(previousOffset, content.length)
        val hasVisibleContentBetween = itemOffset > previousOffset &&
            content.substring(previousOffset, itemOffset).isNotBlank()

        if (hasVisibleContentBetween) {
            blocks += ProcessingBlock(
                offset = groupOffset,
                items = groupItems.toList()
            )
            groupOffset = itemOffset
            groupItems = mutableListOf()
        }

        groupItems += item.toDropdownItem()
        previousOffset = itemOffset
    }

    blocks += ProcessingBlock(
        offset = groupOffset,
        items = groupItems.toList()
    )
    return blocks
}

@Composable
private fun MessageActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp).semantics { this.contentDescription = contentDescription },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun FormattedMarkdownMessage(content: String) {
    MarkdownRenderer(content = content)
}
