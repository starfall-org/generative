package org.starfall.generative.features.home.chat.ui


import org.starfall.generative.shared.ui.sheets.ContextMenuAction
import org.starfall.generative.shared.ui.sheets.ContextMenuSheet
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.ui.text.GatedSelectionContainer
import org.starfall.generative.shared.ui.text.SelectionOnlyPlaceholder
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderCacheIdentity
import kotlinx.coroutines.flow.StateFlow
import org.starfall.generative.shared.model.countedVersionIndices
import org.starfall.generative.shared.model.isEmptyResponse
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.ui.AlertSeverity
import org.starfall.generative.shared.model.generation.OUTPUT_TOKEN_LIMIT_REACHED
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
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
    // The UI must not allocate padding/toolbar for an abandoned assistant attempt.
    // Keep a message with another non-empty version navigable if one exists.
    if (!isStreaming && message.versions.all { it.isEmptyResponse() }) return
    // Each mounted message version can retain its already composed incremental blocks.
    // A newly mounted row has no live memory and reads the latest completed version directly.
    val liveTailMemory = remember(conversationId, message.id, message.activeVersionIndex) { LiveTailMemory() }
    val renderCacheIdentity = remember(conversationId, message.id, cacheEnabled) {
        conversationId?.takeIf { cacheEnabled }?.let { MarkdownRenderCacheIdentity(it, message.id) }
    }
    // Stream state is consumed by the content subtree below. The card shell keeps its stable
    // version so the toolbar, media actions and tool sheet do not invalidate on every chunk.
    val activeVersion = storedVersion
    val generatedModelName = activeVersion.modelDisplayName.ifBlank { activeVersion.modelId.ifBlank { fallbackModelName } }
    // Copy and Share use the same representation, including action/media placeholders.
    val toolbarText = remember(activeVersion) { assistantCopyText(message) }
    // A toolbar belongs to an actual response block, never to a generated empty slot.
    // Edit stays available in the More menu even when Content itself is absent.
    val hasContent = !activeVersion.isEmptyResponse()
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
            liveTailMemory = liveTailMemory,
            selectedImageAttachments = selectedImageAttachments,
            onToggleChatImage = onToggleChatImage,
            onDeleteBlock = onDeleteBlock,
            renderCacheIdentity = renderCacheIdentity,
            streamStateFlow = streamStateFlow,
            streamConversationId = conversationId,
            streamMessageId = message.id
        )
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

/** A composition-scoped handoff, not a second copy of the persisted message. */
private class LiveTailMemory {
    private var lastObserved: ActiveChatStreamState? = null
    val hasObservedStream: Boolean get() = lastObserved != null
    val lastStream: ActiveChatStreamState? get() = lastObserved

    fun observe(stream: ActiveChatStreamState) {
        lastObserved = stream
    }
}

/** Observe live chunks only for the active message; completed cards do not collect the stream flow. */
@Composable
private fun StreamingAssistantText(
    streamStateFlow: StateFlow<ActiveChatStreamState?>?,
    isStreaming: Boolean,
    conversationId: String?,
    messageId: String,
    startOffset: Int,
    fallbackContent: String,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    liveTailMemory: LiveTailMemory
) {
    val currentStream = if (isStreaming) {
        streamStateFlow?.collectAsState()?.value?.takeIf {
            it.conversationId == conversationId && it.messageId == messageId
        }
    } else null
    // Write only after a successful composition, not to a mutable array during composition.
    SideEffect { currentStream?.let(liveTailMemory::observe) }
    val stream = currentStream ?: liveTailMemory.lastStream
    // The final stored version always wins. When it exactly extends the observed stream,
    // supply its missing suffix to the SAME incremental renderer and preserve stable nodes.
    val snapshot = remember(stream?.content, isStreaming, fallbackContent) {
        if (isStreaming) stream?.content else stream?.content?.extendedTo(fallbackContent)
    }
    if (stream == null || snapshot == null) {
        val fallback = fallbackContent.substring(startOffset.coerceIn(0, fallbackContent.length)).trimEnd()
        if (fallback.isNotBlank()) SmoothStreamingMarkdownMessage(fallback, false, cacheIdentity)
        return
    }
    val streamTail = remember(snapshot, startOffset) { snapshot.suffixFrom(startOffset) }
    if (streamTail.length > 0L) {
        StreamingMarkdownRenderer(
            content = "",
            isStreaming = true,
            streamSnapshot = streamTail,
            streamGenerationId = stream.generationId,
            streamMessageId = stream.messageId,
            streamStartOffset = startOffset,
            cacheIdentity = cacheIdentity,
            streamFreezeSignal = stream.stopSignal,
            // Retain the incremental parser on completion, but release its clipping boundary.
            streamGrowthActive = isStreaming
        )
    }
}

@Composable
private fun CompletedAssistantContent(
    version: MessageVersion,
    isStreaming: Boolean,
    processingDurationMillis: Long?,
    liveTailMemory: LiveTailMemory,
    selectedImageAttachments: List<String>,
    onToggleChatImage: ((String) -> Unit)?,
    onDeleteBlock: ((Int) -> Unit)?,
    renderCacheIdentity: MarkdownRenderCacheIdentity? = null,
    streamStateFlow: StateFlow<ActiveChatStreamState?>? = null,
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

    // Keep the same content slot through completion; only the source handed to it changes.
    val useIncrementalText = (isStreaming && streamStateFlow != null) || liveTailMemory.hasObservedStream
    GatedSelectionContainer {
        Column {
            if (numberedBlocks.isEmpty() && useIncrementalText) {
                StreamingAssistantText(
                    streamStateFlow = streamStateFlow,
                    isStreaming = isStreaming,
                    conversationId = streamConversationId,
                    messageId = streamMessageId.orEmpty(),
                    startOffset = 0,
                    fallbackContent = content,
                    cacheIdentity = renderCacheIdentity,
                    liveTailMemory = liveTailMemory
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
                if (useIncrementalText) {
                    StreamingAssistantText(
                        streamStateFlow = streamStateFlow,
                        isStreaming = isStreaming,
                        conversationId = streamConversationId,
                        messageId = streamMessageId.orEmpty(),
                        startOffset = cursor,
                        fallbackContent = content,
                        cacheIdentity = renderCacheIdentity?.forBlock("tail-$cursor"),
                        liveTailMemory = liveTailMemory
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
