package org.starfall.generative.features.home.chat.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderCacheIdentity
import org.starfall.generative.shared.ui.text.GatedSelectionContainer
import org.starfall.generative.features.home.chat.logic.markdown.parseLibraryMarkdown
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderWork
import org.starfall.generative.features.home.chat.model.StreamTextSnapshot
import org.starfall.generative.features.home.chat.model.StreamFreezeSignal
import org.starfall.generative.features.home.chat.ui.MarkdownParseCache
import org.starfall.generative.features.home.chat.ui.TrackPendingWork
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.shared.ui.text.LocalStreamingTextFade
import org.starfall.generative.shared.ui.text.STREAM_FADE_DURATION_MS
import org.starfall.generative.shared.ui.text.messageMarkdownTypography

@Composable
internal fun StreamingMarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = true,
    streamSnapshot: StreamTextSnapshot? = null,
    streamGenerationId: Long? = null,
    streamMessageId: String? = null,
    streamStartOffset: Int = 0,
    cacheIdentity: MarkdownRenderCacheIdentity? = null,
    streamFreezeSignal: StreamFreezeSignal? = null
) {
    val truncationLabel = stringResource(R.string.chat_response_truncated)
    if (isStreaming && streamSnapshot != null && streamGenerationId != null && streamMessageId != null) {
        // Freeze protects an already visible row. A row first opened AFTER Stop can render its
        // immutable source normally and reuse the caches prepared while it was off screen.
        val openedFrozen = remember(streamGenerationId, streamMessageId, streamStartOffset) {
            streamFreezeSignal?.isFrozen == true
        }
        if (openedFrozen) {
            val frozenText = remember(streamGenerationId, streamMessageId, streamStartOffset) {
                streamSnapshot.materialize()
            }
            MarkdownRenderer(frozenText, modifier, isStreaming = false, cacheIdentity = cacheIdentity)
            return
        }
        IncrementalStreamingMarkdown(
            streamSnapshot = streamSnapshot,
            generationId = streamGenerationId,
            messageId = streamMessageId,
            sourceStartOffset = streamStartOffset,
            modifier = modifier,
            cacheIdentity = cacheIdentity,
            streamFreezeSignal = streamFreezeSignal
        )
        return
    }

    val latestContent = rememberUpdatedState(content)
    val displayContent = remember(streamGenerationId, streamMessageId, cacheIdentity) { mutableStateOf(content) }
    val oversizedPreview = remember(streamGenerationId, streamMessageId, cacheIdentity) { mutableStateOf<String?>(null) }
    val finishingFade = remember { mutableStateOf(isStreaming) }
    val settled = remember { mutableStateOf(!isStreaming) }
    ObserveOversizedStreamingPreview(
        latestContent = latestContent,
        truncationLabel = truncationLabel,
        streamGenerationId = streamGenerationId,
        streamMessageId = streamMessageId,
        cacheIdentity = cacheIdentity,
        onPreview = { oversizedPreview.value = it }
    )
    ObserveStreamingMarkdownLifecycle(
        isStreaming = isStreaming,
        content = content,
        latestContent = latestContent,
        displayContent = displayContent,
        finishingFade = finishingFade,
        settled = settled,
        cacheIdentity = cacheIdentity
    )
    RenderStreamingMarkdown(
        displayContent = displayContent.value,
        oversizedPreview = oversizedPreview.value,
        truncationLabel = truncationLabel,
        modifier = modifier,
        isStreaming = isStreaming,
        finishingFade = finishingFade.value,
        settled = settled.value,
        cacheIdentity = cacheIdentity,
        streamGenerationId = streamGenerationId
    )
}

@Composable
private fun ObserveOversizedStreamingPreview(
    latestContent: androidx.compose.runtime.State<String>,
    truncationLabel: String,
    streamGenerationId: Long?,
    streamMessageId: String?,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    onPreview: (String) -> Unit
) {
    LaunchedEffect(streamGenerationId, streamMessageId, cacheIdentity) {
        var previewCreated = false
        snapshotFlow { latestContent.value }.conflate().collect { input ->
            if (input.length > MAX_MARKDOWN_RENDER_CHARS) {
                previewCreated = true
                delay(80)
                val latest = latestContent.value
                if (latest.length > MAX_MARKDOWN_RENDER_CHARS) onPreview(latest)
            } else if (!previewCreated) {
                onPreview("")
            }
        }
    }
}

@Composable
private fun ObserveStreamingMarkdownLifecycle(
    isStreaming: Boolean,
    content: String,
    latestContent: androidx.compose.runtime.State<String>,
    displayContent: MutableState<String>,
    finishingFade: MutableState<Boolean>,
    settled: MutableState<Boolean>,
    cacheIdentity: MarkdownRenderCacheIdentity?
) {
    LaunchedEffect(isStreaming, if (isStreaming) null else content) {
        if (isStreaming) {
            settled.value = false
            finishingFade.value = true
            collectStreamingMarkdownChunks(latestContent, displayContent)
        } else {
            displayContent.value = latestContent.value
            if (finishingFade.value) {
                delay(STREAM_FADE_DURATION_MS + 32)
                finishingFade.value = false
            }
            if (!settled.value) {
                val finalText = latestContent.value
                cacheFinalMarkdownIfNeeded(finalText, cacheIdentity)
                displayContent.value = finalText
                settled.value = true
            }
        }
    }
}

private suspend fun collectStreamingMarkdownChunks(
    latestContent: androidx.compose.runtime.State<String>,
    displayContent: MutableState<String>
) {
    snapshotFlow { latestContent.value }.conflate().collect {
        val latest = latestContent.value
        if (latest.length <= MAX_MARKDOWN_RENDER_CHARS) displayContent.value = latest
    }
}

private suspend fun cacheFinalMarkdownIfNeeded(
    finalText: String,
    cacheIdentity: MarkdownRenderCacheIdentity?
) {
    if (finalText.length > MAX_MARKDOWN_RENDER_CHARS || MarkdownParseCache.get(finalText, cacheIdentity) != null) return
    val (state, fingerprint) = withContext(Dispatchers.Default) {
        val startedAt = System.nanoTime()
        val parsedState = try {
            parseLibraryMarkdown(finalText)
        } finally {
            MarkdownParseCache.recordFullParse(finalText.length, System.nanoTime() - startedAt)
        }
        parsedState to if (parsedState is State.Success) markdownContentFingerprint(finalText) else null
    }
    MarkdownParseCache.put(finalText, state, cacheIdentity, fingerprint)
}

@Composable
private fun RenderStreamingMarkdown(
    displayContent: String,
    oversizedPreview: String?,
    truncationLabel: String,
    modifier: Modifier,
    isStreaming: Boolean,
    finishingFade: Boolean,
    settled: Boolean,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    streamGenerationId: Long?
) {
    CompositionLocalProvider(LocalStreamingTextFade provides (isStreaming || finishingFade)) {
        when {
            // This is only a live-stream safety preview. Once generation stops, let the complete
            // source reach MarkdownRenderer, which falls back to bounded plain-text nodes if needed.
            isStreaming && !oversizedPreview.isNullOrEmpty() -> StreamingMarkdownPreview(oversizedPreview, modifier)
            isStreaming && displayContent.length > MAX_MARKDOWN_RENDER_CHARS -> StreamingMarkdownPreview(displayContent, modifier)
            else -> RenderStreamingMarkdownDocument(
                displayContent = displayContent,
                modifier = modifier,
                isStreaming = isStreaming,
                settled = settled,
                cacheIdentity = cacheIdentity,
                streamGenerationId = streamGenerationId
            )
        }
    }
}

@Composable
private fun StreamingMarkdownPreview(text: String, modifier: Modifier) {
    GatedSelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
            modifier = modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RenderStreamingMarkdownDocument(
    displayContent: String,
    modifier: Modifier,
    isStreaming: Boolean,
    settled: Boolean,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    streamGenerationId: Long?
) {
    if (settled && !isStreaming) {
        MarkdownRenderer(
            content = displayContent,
            modifier = modifier,
            isStreaming = isStreaming,
            cacheIdentity = cacheIdentity
        )
    } else {
        ChunkedStreamingMarkdown(
            content = displayContent,
            modifier = modifier,
            cacheIdentity = cacheIdentity,
            streamGenerationId = streamGenerationId
        )
    }
}

@Composable
private fun IncrementalStreamingMarkdown(
    streamSnapshot: StreamTextSnapshot,
    generationId: Long,
    messageId: String,
    sourceStartOffset: Int,
    modifier: Modifier,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    streamFreezeSignal: StreamFreezeSignal?
) {
    val initialBaseText = streamSnapshot.baseText
    val truncationLabel = stringResource(R.string.chat_response_truncated)
    key(generationId, messageId, sourceStartOffset, initialBaseText) {
        val cancellationToken = remember { MarkdownParserCancellationToken() }
        val incrementalParser = remember { IncrementalMarkdownParser(cancellationToken) }
        val latestSnapshot = rememberUpdatedState(streamSnapshot)
        // Network publications must not invalidate the whole measured Markdown tree.
        // Only an accepted parser/overflow commit changes the source seen by the renderer.
        val renderedSource = remember { mutableStateOf(streamSnapshot) }
        val renderedOverflow = remember { mutableStateOf<String?>(null) }
        val renderSnapshot = remember { mutableStateOf(incrementalParser.currentSnapshot()) }
        val parsing = remember { mutableStateOf(false) }
        val listScrolling = LocalChatListScrolling.current
        TrackPendingWork(parsing.value)
        CollectIncrementalMarkdownStream(
            initialBaseText = initialBaseText,
            latestSnapshot = latestSnapshot,
            renderedSource = renderedSource,
            renderedOverflow = renderedOverflow,
            parser = incrementalParser,
            cancellationToken = cancellationToken,
            renderSnapshot = renderSnapshot,
            parsing = parsing,
            streamFreezeSignal = streamFreezeSignal,
            listScrolling = listScrolling
        )
        val frozen = streamFreezeSignal?.frozen?.collectAsState()?.value == true
        // Stop cancels in-flight parsing. Keep all already rendered Markdown nodes, but expose
        // any committed text the parser had not processed as a plain-text overflow instead of
        // leaving part (or all) of the response invisible.
        val visibleSnapshot = renderSnapshot.value.let { parsed ->
            if (frozen && parsed.overflowStart == null && streamSnapshot.length > parsed.parsedLength) {
                // The overflow renderer replaces unstable nodes; include their source too.
                parsed.copy(overflowStart = parsed.unstableNodes.firstOrNull()?.startOffset ?: parsed.parsedLength)
            } else parsed
        }
        RenderIncrementalMarkdown(
            parser = incrementalParser,
            renderSnapshot = visibleSnapshot,
            renderedSource = if (frozen) streamSnapshot else renderedSource.value,
            renderedOverflow = if (frozen) null else renderedOverflow.value,
            truncationLabel = truncationLabel,
            modifier = modifier,
            cacheIdentity = cacheIdentity
        )
    }
}

@Composable
private fun CollectIncrementalMarkdownStream(
    initialBaseText: String,
    latestSnapshot: androidx.compose.runtime.State<StreamTextSnapshot>,
    renderedSource: MutableState<StreamTextSnapshot>,
    renderedOverflow: MutableState<String?>,
    parser: IncrementalMarkdownParser,
    cancellationToken: MarkdownParserCancellationToken,
    renderSnapshot: MutableState<IncrementalMarkdownSnapshot>,
    parsing: MutableState<Boolean>,
    streamFreezeSignal: StreamFreezeSignal?,
    listScrolling: androidx.compose.runtime.State<Boolean>
) {
    LaunchedEffect(Unit) {
        val parserJob = launch {
            cancellationToken.ownerJob = currentCoroutineContext()[Job]
            try {
                if (streamFreezeSignal?.isFrozen != true) {
                    appendInitialMarkdownBase(
                        initialBaseText, parser, renderSnapshot, renderedSource,
                        renderedOverflow, parsing, streamFreezeSignal
                    )
                    if (streamFreezeSignal?.isFrozen != true) {
                        collectIncrementalMarkdownUpdates(
                            latestSnapshot = latestSnapshot,
                            renderedSource = renderedSource,
                            renderedOverflow = renderedOverflow,
                            parser = parser,
                            renderSnapshot = renderSnapshot,
                            parsing = parsing,
                            streamFreezeSignal = streamFreezeSignal,
                            listScrolling = listScrolling
                        )
                    }
                }
            } finally {
                cancellationToken.ownerJob = null
            }
        }
        val freezeJob = streamFreezeSignal?.let { signal ->
            launch {
                signal.frozen.first { frozen -> frozen }
                parserJob.cancelAndJoin()
            }
        }
        try {
            parserJob.join()
        } finally {
            freezeJob?.cancelAndJoin()
        }
    }
}

private suspend fun appendInitialMarkdownBase(
    initialBaseText: String,
    parser: IncrementalMarkdownParser,
    renderSnapshot: MutableState<IncrementalMarkdownSnapshot>,
    renderedSource: MutableState<StreamTextSnapshot>,
    renderedOverflow: MutableState<String?>,
    parsing: MutableState<Boolean>,
    streamFreezeSignal: StreamFreezeSignal?
) {
    if (streamFreezeSignal?.isFrozen == true) return
    val initialContent = initialBaseText.take(MAX_MARKDOWN_RENDER_CHARS)
    if (initialContent.isEmpty()) return
    appendIncrementalMarkdown(
        parser, initialContent, StreamTextSnapshot.of(initialBaseText), renderSnapshot,
        renderedSource, renderedOverflow, parsing, streamFreezeSignal
    )
}

private suspend fun collectIncrementalMarkdownUpdates(
    latestSnapshot: androidx.compose.runtime.State<StreamTextSnapshot>,
    renderedSource: MutableState<StreamTextSnapshot>,
    renderedOverflow: MutableState<String?>,
    parser: IncrementalMarkdownParser,
    renderSnapshot: MutableState<IncrementalMarkdownSnapshot>,
    parsing: MutableState<Boolean>,
    streamFreezeSignal: StreamFreezeSignal?,
    listScrolling: androidx.compose.runtime.State<Boolean>
) {
    snapshotFlow { latestSnapshot.value }.conflate().collect {
        if (streamFreezeSignal?.isFrozen == true) return@collect
        val activeOverflowStart = renderSnapshot.value.overflowStart
        if (activeOverflowStart != null) {
            // The single selectable fallback Text may grow past the parser's 8k budget.
            // Do not relayout that entire node while a finger is scrolling; preserve
            // every segment in the stream and catch up on release. This applies only
            // to pathological overflow, not the normal incremental Markdown path.
            if (listScrolling.value) {
                snapshotFlow { listScrolling.value }.first { !it }
            } else {
                // Even when idle, cap full-node relayout to roughly four updates/s.
                delay(240L)
            }
            if (streamFreezeSignal?.isFrozen == true) return@collect
            if (listScrolling.value) snapshotFlow { listScrolling.value }.first { !it }
            val current = latestSnapshot.value
            val end = current.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val prepared = withContext(Dispatchers.Default) {
                current.slice(activeOverflowStart, end)
            }
            if (streamFreezeSignal?.isFrozen != true) {
                renderedOverflow.value = prepared
                renderedSource.value = current
            }
            return@collect
        }
        // While the user scrolls, apply streamed text at a bounded rate instead of per publication,
        // then read the latest text so one step catches up on everything published meanwhile.
        awaitStreamApplySlot(listScrolling)
        if (streamFreezeSignal?.isFrozen == true) return@collect
        val current = latestSnapshot.value
        if (current.length > MAX_MARKDOWN_RENDER_CHARS.toLong()) {
            // Do not parse or lay out the whole answer again. Freeze the parser's stable prefix and
            // render only the remaining source as one Text node in the same selection/layout tree.
            delay(80)
            if (streamFreezeSignal?.isFrozen == true) return@collect
            if (latestSnapshot.value.length > MAX_MARKDOWN_RENDER_CHARS.toLong()) {
                val overflowSnapshot = withContext(Dispatchers.Default) {
                    parser.preserveUnstableTailAsPlainText()
                }
                if (streamFreezeSignal?.isFrozen != true) {
                    val latest = latestSnapshot.value
                    val start = overflowSnapshot.overflowStart ?: overflowSnapshot.parsedLength
                    val overflow = withContext(Dispatchers.Default) {
                        latest.slice(start, latest.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    }
                    if (streamFreezeSignal?.isFrozen != true) {
                        renderedOverflow.value = overflow
                        renderedSource.value = latest
                        renderSnapshot.value = overflowSnapshot
                    }
                }
            }
            return@collect
        }
        // A virtualized row may have missed thousands of small stream publications. Replay the
        // unparsed source once; the existing parser owns its bounded internal append sizes.
        val start = renderSnapshot.value.parsedLength
        val end = current.length.coerceAtMost(MAX_MARKDOWN_RENDER_CHARS.toLong()).toInt()
        if (end > start) {
            appendIncrementalMarkdown(parser, current.slice(start, end), current,
                renderSnapshot, renderedSource, renderedOverflow, parsing, streamFreezeSignal)
        }
    }
}

private suspend fun appendIncrementalMarkdown(
    parser: IncrementalMarkdownParser,
    text: String,
    source: StreamTextSnapshot,
    renderSnapshot: MutableState<IncrementalMarkdownSnapshot>,
    renderedSource: MutableState<StreamTextSnapshot>,
    renderedOverflow: MutableState<String?>,
    parsing: MutableState<Boolean>,
    streamFreezeSignal: StreamFreezeSignal?
) {
    if (streamFreezeSignal?.isFrozen == true) return
    parsing.value = true
    try {
        val result = MarkdownRenderWork.run {
            parser.append(
                text = text,
                source = source,
                maxUnstableTailChars = MAX_INCREMENTAL_UNSTABLE_TAIL_CHARS,
                maxParserAppendChars = MAX_INCREMENTAL_PARSER_APPEND_CHARS
            )
        }
        if (streamFreezeSignal?.isFrozen == true) return
        result.linkUpdates.forEach { (label, destination) ->
            parser.referenceLinkHandler.store(label, destination)
        }
        val overflow = result.snapshot.overflowStart?.let { start ->
            withContext(Dispatchers.Default) {
                source.slice(start, source.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }
        if (streamFreezeSignal?.isFrozen != true) {
            renderedOverflow.value = overflow
            renderedSource.value = source
            renderSnapshot.value = result.snapshot
        }
    } finally {
        parsing.value = false
    }
}

@Composable
private fun RenderIncrementalMarkdown(
    parser: IncrementalMarkdownParser,
    renderSnapshot: IncrementalMarkdownSnapshot,
    renderedSource: StreamTextSnapshot,
    renderedOverflow: String?,
    truncationLabel: String,
    modifier: Modifier,
    cacheIdentity: MarkdownRenderCacheIdentity?
) {
    val preferences = LocalCodeRenderingPreferences.current
    val typography = messageMarkdownTypography()
    CompositionLocalProvider(LocalStreamingTextFade provides true) {
        RenderIncrementalMarkdownDocument(
            parser = parser,
            renderSnapshot = renderSnapshot,
            renderedSource = renderedSource,
            renderedOverflow = renderedOverflow,
            truncationLabel = truncationLabel,
            modifier = modifier,
            cacheIdentity = cacheIdentity,
            preferences = preferences,
            typography = typography
        )
    }
}

@Composable
private fun RenderIncrementalMarkdownDocument(
    parser: IncrementalMarkdownParser,
    renderSnapshot: IncrementalMarkdownSnapshot,
    renderedSource: StreamTextSnapshot,
    renderedOverflow: String?,
    truncationLabel: String,
    modifier: Modifier,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    preferences: org.starfall.generative.shared.storage.preferences.AppPreferences,
    typography: MarkdownTypography
) {
    Markdown(
        state = State.Success(
            node = parser.file,
            // This opaque revision key drives Compose updates; rendering slices source from the latest snapshot.
            content = renderSnapshot.revision.toString(),
            linksLookedUp = true,
            referenceLinkHandler = parser.referenceLinkHandler
        ),
        modifier = modifier.fillMaxWidth(),
        colors = markdownColor(),
        typography = typography,
        padding = markdownPadding(block = MARKDOWN_BLOCK_PADDING),
        components = chatMarkdownComponents(
            isStreaming = true,
            latexMode = preferences.latexMode,
            fillMaxWidth = true
        ),
        success = { _, components, successModifier ->
            RenderIncrementalMarkdownDocument(
                stableNodes = renderSnapshot.stableNodes,
                unstableNodes = renderSnapshot.unstableNodes,
                source = renderedSource,
                overflowStart = renderSnapshot.overflowStart,
                preparedOverflow = renderedOverflow,
                components = components,
                latexEnabled = !preferences.latexMode.equals("OFF", ignoreCase = true),
                modifier = successModifier
            )
        },
        error = { androidx.compose.material3.Text(truncationLabel, modifier = Modifier.fillMaxWidth()) }
    )
}
