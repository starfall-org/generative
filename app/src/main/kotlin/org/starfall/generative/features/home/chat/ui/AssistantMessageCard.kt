package org.starfall.generative.features.home.chat.ui


import org.starfall.generative.shared.ui.sheets.ContextMenuAction
import org.starfall.generative.shared.ui.sheets.ContextMenuSheet
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.ui.text.GatedSelectionContainer
import org.starfall.generative.shared.ui.text.SelectionOnlyPlaceholder
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.features.home.chat.logic.service.AssistantTimelineItem
import org.starfall.generative.features.home.chat.logic.service.buildAssistantActionBlocks
import org.starfall.generative.features.home.chat.logic.service.AssistantBlockKind
import org.starfall.generative.features.home.chat.logic.service.assistantVisibleBlocks
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState
import org.starfall.generative.features.home.chat.model.ChatStreamStatus
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderCacheIdentity
import kotlinx.coroutines.flow.StateFlow
import org.starfall.generative.shared.model.countedVersionIndices
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.ui.AlertSeverity
import org.starfall.generative.shared.model.generation.OUTPUT_TOKEN_LIMIT_REACHED
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.nestedContainerColor
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.features.home.chat.ui.markdown.MarkdownRenderer
import org.starfall.generative.features.home.chat.ui.markdown.StreamingMarkdownRenderer
import org.starfall.generative.features.home.shared.ui.LocalToolSheetState
import org.starfall.generative.features.home.shared.ui.ToolSheetState
import org.starfall.generative.features.home.shared.ui.ToolDetailsSheetHost

@Composable
fun AssistantMessageCard(
    message: StoredMessage,
    isStreaming: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onRetryError: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDeleteBlock: ((Int) -> Unit)? = null,
    onRead: () -> Unit,
    onSwitchVersion: (Int) -> Unit,
    selectedImageAttachments: List<String> = emptyList(),
    onToggleChatImage: ((String) -> Unit)? = null,
    isReading: Boolean = false,
    fallbackModelName: String = "",
    modifier: Modifier = Modifier,
    conversationId: String? = null,
    streamStateFlow: StateFlow<ActiveChatStreamState?>? = null,
    cacheEnabled: Boolean = true
) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    SideEffect { ChatRenderMetrics.recordMessageCardRecomposition() }
    val foreground = LocalChatForeground.current
    var showMoreMenu by remember(foreground) { mutableStateOf(false) }
    val storedVersion = message.activeVersion
    // A card that streamed here keeps its incremental Markdown tree through Stop and natural
    // completion. Reopening the chat instead uses saved text, never a stale/frozen parser.
    val streamedInThisComposition = remember(message.id, message.activeVersionIndex) { booleanArrayOf(false) }
    // Shared by the text subtrees so adding a tool block or clearing the published stream
    // cannot discard the last incremental snapshot and remount all the Markdown at completion.
    val retainedStream = remember(message.id, message.activeVersionIndex) { arrayOfNulls<ActiveChatStreamState>(1) }
    if (isStreaming) streamedInThisComposition[0] = true
    val keepStreamRendererMounted = streamedInThisComposition[0]
    val renderCacheIdentity = remember(conversationId, message.id, cacheEnabled) {
        conversationId?.takeIf { cacheEnabled }?.let { MarkdownRenderCacheIdentity(it, message.id) }
    }
    // Stream state is consumed by the content subtree below. The card shell keeps its stable
    // version so the toolbar, media actions and tool sheet do not invalidate on every chunk.
    val activeVersion = storedVersion
    val generatedModelName = activeVersion.modelDisplayName.ifBlank { activeVersion.modelId.ifBlank { fallbackModelName } }
    val generationError = activeVersion.generationError.orEmpty()
    // Copy and Share use the same representation, including action/media placeholders.
    val toolbarText = remember(activeVersion) { assistantCopyText(message) }
    val hasContent = activeVersion.content.isNotBlank() ||
        !activeVersion.reasoningContent.isNullOrBlank() ||
        activeVersion.toolActivity.isNotEmpty() || activeVersion.files.isNotEmpty() ||
        generationError.isNotBlank()
    val versionIndices = remember(message.versions, message.activeVersionIndex) {
        val counted = message.countedVersionIndices()
        if (message.activeVersionIndex in counted) counted
        else listOf(message.activeVersionIndex) + counted
    }
    val displayedVersionIndex = versionIndices.indexOf(message.activeVersionIndex).coerceAtLeast(0)
    val processingDurationMillis = activeVersion.processingFinishedAt?.let { finishedAt ->
        activeVersion.timestamp.toLongOrNull()?.let { startedAt -> (finishedAt - startedAt).coerceAtLeast(0L) }
    }

    val toolSheetState = remember(message.id, foreground) { ToolSheetState() }
    if (foreground) ToolDetailsSheetHost(toolSheetState, activeVersion.toolActivity)

    CompositionLocalProvider(LocalToolSheetState provides toolSheetState) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // One call site for streaming and finished states. Switching between two composables here
        // (e.g. when a tool's image arrives before any text) recreated the processing dropdown,
        // resetting its expanded/visibility state and making it snap closed and animate open again.
        CompletedAssistantContent(
            version = activeVersion,
            isStreaming = isStreaming,
            processingDurationMillis = processingDurationMillis.takeUnless { isStreaming },
            animateStreamingContent = keepStreamRendererMounted,
            selectedImageAttachments = selectedImageAttachments,
            onToggleChatImage = onToggleChatImage,
            onDeleteBlock = onDeleteBlock,
            renderCacheIdentity = renderCacheIdentity,
            streamStateFlow = streamStateFlow?.takeIf { keepStreamRendererMounted },
            retainedStream = retainedStream,
            streamConversationId = conversationId,
            streamMessageId = message.id
        )
        // A response containing only thinking, tool actions or attachments still needs an
        // editable answer slot. Keep it separate from the actions timeline and toolbar.
        if (!isStreaming && hasContent && activeVersion.content.isBlank()) {
            Surface(
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    .heightIn(min = 32.dp).testTag("assistant-empty-content"),
                shape = RoundedCornerShape(10.dp),
                color = nestedContainerColor(),
                border = nestedContainerBorder()
            ) {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.CenterStart) {
                    SharedIcon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp))
                }
            }
        }
        if (isStreaming) {
            GenerationLoadingIndicator(
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        activeVersion.generationError?.takeIf { it.isNotBlank() }?.let { error ->
            // Token exhaustion is an API-imposed limit, not a transport failure.
            // Keep the persisted block format unchanged for editing, retry and old conversations.
            val isWarning = error == OUTPUT_TOKEN_LIMIT_REACHED
            GatedSelectionContainer { SelectionOnlyPlaceholder("[ErrorBlock]") }
            val errorFontSize = (LocalCodeRenderingPreferences.current.effectiveMessageFontSize * 0.78f).sp
            ErrorContainer(
                message = error,
                severity = if (isWarning) AlertSeverity.WARNING else AlertSeverity.ERROR,
                title = stringResource(
                    if (isWarning) R.string.message_generation_warning else R.string.message_generation_error
                ),
                onRetry = onRetryError,
                expandable = !isWarning,
                messageStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = errorFontSize,
                    lineHeight = errorFontSize * 1.44f
                ),
                modifier = Modifier.padding(top = 8.dp)
            )
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
                    SharedIcon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.message_previous_version),
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
                    SharedIcon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.message_next_version),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Toolbar visibility depends on message data, not on its copy representation.
        if (!isStreaming && hasContent) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MessageActionButton(onClick = onRegenerate, contentDescription = stringResource(R.string.message_regenerate)) {
                    SharedIcon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                MessageActionButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                         putExtra(Intent.EXTRA_TEXT, toolbarText)
                        }
                        context.startActivity(Intent.createChooser(share, localizedResources.getString(R.string.message_share_response)))
                    },
                    contentDescription = stringResource(R.string.common_share)
                ) {
                    SharedIcon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                MessageActionButton(onClick = onCopy,
                    contentDescription = stringResource(R.string.common_copy)) {
                    SharedIcon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                }

                Box {
                    MessageActionButton(
                        onClick = { showMoreMenu = true },
                        contentDescription = stringResource(R.string.common_more)
                    ) {
                        SharedIcon(Icons.Default.MoreHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    if (foreground && showMoreMenu) ContextMenuSheet(
                        actions = listOf(
                            ContextMenuAction(stringResource(R.string.common_edit), onEdit, Icons.Outlined.Edit),
                            ContextMenuAction(stringResource(R.string.common_delete), onDelete, Icons.Outlined.Delete, destructive = true)
                        ),
                        onDismissRequest = { showMoreMenu = false }
                    )
                }

                if (generatedModelName.isNotBlank()) {
                    Text(generatedModelName,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else Spacer(modifier = Modifier.weight(1f))

                MessageActionButton(onClick = onRead, contentDescription = stringResource(if (isReading) R.string.message_stop_reading else R.string.message_read_aloud)) {
                    SharedIcon(if (isReading) Icons.Outlined.StopCircle else Icons.Outlined.VolumeUp,
                        contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = if (isReading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    }
}

@Composable
private fun SmoothStreamingMarkdownMessage(
    content: String,
    isStreaming: Boolean,
    cacheIdentity: MarkdownRenderCacheIdentity? = null
) {
    StreamingMarkdownRenderer(content = content, isStreaming = isStreaming, cacheIdentity = cacheIdentity)
}

/** Collects live text below the assistant-card shell so static actions/media do not recompose per commit. */
@Composable
private fun StreamingAssistantText(
    streamStateFlow: StateFlow<ActiveChatStreamState?>,
    conversationId: String?,
    messageId: String,
    startOffset: Int,
    fallbackContent: String,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    retainedStream: Array<ActiveChatStreamState?>
) {
    val currentStream = streamStateFlow.collectAsState().value?.takeIf {
        it.conversationId == conversationId && it.messageId == messageId
    }
    if (currentStream != null) retainedStream[0] = currentStream
    val stream = currentStream ?: retainedStream[0]
    if (stream == null) {
        val fallback = fallbackContent.substring(startOffset.coerceIn(0, fallbackContent.length)).trimEnd()
        if (fallback.isNotBlank()) SmoothStreamingMarkdownMessage(fallback, false, cacheIdentity)
        return
    }
    // Completion clears the shared stream, but final persistence can include an extra batch
    // that its conflated UI collector never saw. Append only that suffix to the same parser.
    val fullSnapshot = remember(stream.content, fallbackContent, currentStream) {
        if (currentStream != null || stream.stopSignal.isFrozen || stream.status == ChatStreamStatus.CANCELLED) {
            stream.content
        } else {
            val seenLength = stream.content.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val boundaryStart = (seenLength - 64).coerceAtLeast(0)
            val boundary = stream.content.slice(boundaryStart, seenLength)
            val prefix = stream.content.prefix(minOf(64, seenLength))
            if (fallbackContent.length > seenLength && fallbackContent.startsWith(prefix) &&
                fallbackContent.regionMatches(boundaryStart, boundary, 0, boundary.length)
            ) {
                stream.content.append(fallbackContent.substring(seenLength), stream.revision + 1)
            } else stream.content
        }
    }
    val streamTail = remember(fullSnapshot, startOffset) {
        fullSnapshot.suffixFrom(startOffset)
    }
    if (streamTail.length > 0L) {
        StreamingMarkdownRenderer(
            // The snapshot is the source of truth on this path; avoid copying the complete tail to a String
            // on every stream publication just to satisfy the compatibility renderer's parameter.
            content = "",
            isStreaming = true,
            // The conflated collector can drain every queued segment in order without dropping chunks.
            streamSnapshot = streamTail,
            streamGenerationId = stream.generationId,
            streamMessageId = stream.messageId,
            streamStartOffset = startOffset,
            cacheIdentity = cacheIdentity,
            streamFreezeSignal = stream.stopSignal
        )
    }
}

@Composable
private fun CompletedAssistantContent(
    version: MessageVersion,
    isStreaming: Boolean,
    processingDurationMillis: Long?,
    animateStreamingContent: Boolean,
    selectedImageAttachments: List<String>,
    onToggleChatImage: ((String) -> Unit)?,
    onDeleteBlock: ((Int) -> Unit)?,
    renderCacheIdentity: MarkdownRenderCacheIdentity? = null,
    streamStateFlow: StateFlow<ActiveChatStreamState?>? = null,
    retainedStream: Array<ActiveChatStreamState?>,
    streamConversationId: String? = null,
    streamMessageId: String? = null
) {
    // Unfinished reasoning lives in the stream, not in database snapshots. Read it directly
    // so the actions block can appear before any tool boundary or final save.
    val liveReasoning = rememberLiveStreamReasoning(
        streamStateFlow, streamConversationId, streamMessageId.orEmpty(), enabled = isStreaming
    )
    val timelineVersion = remember(version, liveReasoning) {
        if (liveReasoning != null && liveReasoning.length > version.reasoningContent.orEmpty().length) {
            version.copy(reasoningContent = liveReasoning)
        } else version
    }
    val blocks = remember(timelineVersion.content, timelineVersion.reasoningContent, timelineVersion.toolActivity) {
        buildAssistantActionBlocks(timelineVersion)
    }
    val numberedBlocks = remember(timelineVersion, isStreaming) {
        assistantVisibleBlocks(timelineVersion, isStreaming)
    }
    val lastBlock = blocks.lastOrNull()
    // Answer text streamed after the last block means its actions are done, even before the stored
    // content catches up with the stream.
    val streamAnswerStarted = rememberStreamAnswerStarted(
        streamStateFlow, streamConversationId, streamMessageId.orEmpty(),
        offset = lastBlock?.offset ?: 0, enabled = isStreaming && lastBlock != null
    )
    // Tool details have their own sheet. Keep the answer text canonical here; redacting matching tool
    // responses made the rendered message look truncated while copy/edit still showed the full value.
    val content = version.content
    // Compute deduplication from immutable block data, not by mutating a set while
    // composing. Recomposition must never make an already-visible media file disappear.
    val mediaItemsByNumber = remember(blocks, numberedBlocks, isStreaming) {
        val seenFiles = mutableSetOf<String>()
        numberedBlocks.filter { it.kind == AssistantBlockKind.MEDIA }.associate { visual ->
            val items = visual.actionIndex?.let(blocks::getOrNull)
                ?.let { assistantMediaPreviewItems(it, isStreaming) }
                .orEmpty().filter { item ->
                    item !is MediaPreviewItem.File || seenFiles.add(item.name)
                }
            visual.number to items
        }
    }

    GatedSelectionContainer {
        Column {
            if (numberedBlocks.isEmpty() && animateStreamingContent && streamStateFlow != null) {
                StreamingAssistantText(
                    streamStateFlow = streamStateFlow,
                    conversationId = streamConversationId,
                    messageId = streamMessageId.orEmpty(),
                    startOffset = 0,
                    fallbackContent = content,
                    cacheIdentity = renderCacheIdentity,
                    retainedStream = retainedStream
                )
            } else {
                var cursor = 0
                var nextItemNumber = 1
                numberedBlocks.forEach { visual ->
                    val offset = visual.offset.coerceIn(cursor, content.length)
                    if (offset > cursor) {
                        val textBefore = content.substring(cursor, offset).trimEnd()
                        if (textBefore.isNotBlank()) {
                            FormattedMarkdownMessage(textBefore,
                                renderCacheIdentity?.forBlock("before-${visual.number}"))
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    val block = visual.actionIndex?.let { blocks.getOrNull(it) }
                    when (visual.kind) {
                        AssistantBlockKind.ACTIONS -> {
                            if (block != null) {
                                val answerStarted = content.substring(offset).isNotBlank() ||
                                    (block === lastBlock && streamAnswerStarted)
                                val running = block.items.any {
                                    it is AssistantTimelineItem.Tool && it.activity.status == "running"
                                }
                                val live = isStreaming && block === lastBlock && (!answerStarted || running)
                                SelectionOnlyPlaceholder(visual.placeholder)
                                key(visual.actionIndex, block.items.firstOrNull()?.let { item ->
                                    when (item) {
                                        is AssistantTimelineItem.Thinking -> "thinking"
                                        is AssistantTimelineItem.Tool -> item.activity.id
                                    }
                                }) {
                                    DisableSelection {
                                        ProcessingDropdown(
                                            items = block.items.map { item -> when (item) {
                                                is AssistantTimelineItem.Thinking -> ProcessingDropdownItem.Thinking(item.reasoning)
                                                is AssistantTimelineItem.Tool -> ProcessingDropdownItem.Tool(item.activity)
                                            } },
                                            durationMillis = processingDurationMillis.takeIf { blocks.size == 1 },
                                            startNumber = nextItemNumber,
                                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                                            isStreaming = live,
                                            onDelete = if (!isStreaming && onDeleteBlock != null)
                                                ({ onDeleteBlock(visual.number) }) else null
                                        )
                                    }
                                }
                                nextItemNumber += block.items.count {
                                    it !is AssistantTimelineItem.Tool || it.activity.name != "send_file"
                                }
                            }
                        }
                        // The error container is drawn by the card after its content timeline.
                        AssistantBlockKind.ERROR -> Unit
                        AssistantBlockKind.MEDIA -> AssistantMediaBlock(
                            block = block,
                            files = version.files,
                            number = visual.number,
                            isStreaming = isStreaming,
                            selectedImageAttachments = selectedImageAttachments,
                            onToggleChatImage = onToggleChatImage,
                            onDelete = if (!isStreaming && onDeleteBlock != null)
                                ({ onDeleteBlock(visual.number) }) else null,
                            mediaItems = mediaItemsByNumber[visual.number].orEmpty()
                        )
                    }
                    cursor = offset
                }
                if (animateStreamingContent && streamStateFlow != null) {
                    StreamingAssistantText(
                        streamStateFlow = streamStateFlow,
                        conversationId = streamConversationId,
                        messageId = streamMessageId.orEmpty(),
                        startOffset = cursor,
                        fallbackContent = content,
                        cacheIdentity = renderCacheIdentity?.forBlock("tail-$cursor"),
                        retainedStream = retainedStream
                    )
                } else if (cursor < content.length) {
                    val remaining = content.substring(cursor)
                    if (remaining.isNotBlank()) SmoothStreamingMarkdownMessage(
                        remaining.trimEnd(), false, renderCacheIdentity?.forBlock("tail-$cursor")
                    )
                }
            }
        }
    }
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
fun FormattedMarkdownMessage(
    content: String,
    cacheIdentity: MarkdownRenderCacheIdentity? = null
) {
    MarkdownRenderer(content = content, cacheIdentity = cacheIdentity)
}

/** Copy representation of one assistant message; visual action/media blocks have stable text tokens. */
internal fun assistantCopyText(message: StoredMessage): String {
    val version = message.activeVersion
    val content = version.content
    val result = StringBuilder()
    var cursor = 0
    assistantVisibleBlocks(version).forEach { block ->
        val offset = block.offset.coerceIn(cursor, content.length)
        result.append(content, cursor, offset).append(block.placeholder)
        cursor = offset
    }
    result.append(content, cursor, content.length)
    return result.toString().ifBlank { version.generationError.orEmpty() }
}
