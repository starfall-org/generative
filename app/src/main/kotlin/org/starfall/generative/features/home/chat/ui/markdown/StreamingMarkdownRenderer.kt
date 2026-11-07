package org.starfall.generative.features.home.chat.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderCacheIdentity
import org.starfall.generative.shared.ui.text.GatedSelectionContainer
import org.starfall.generative.features.home.chat.model.StreamTextSnapshot
import org.starfall.generative.features.home.chat.model.StreamFreezeSignal
import org.starfall.generative.features.home.chat.ui.MarkdownParseCache

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
    streamFreezeSignal: StreamFreezeSignal? = null,
    streamGrowthActive: Boolean = isStreaming
) {
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
        key(streamGenerationId, streamMessageId, streamStartOffset) {
            StreamGrowthViewport(modifier, active = streamGrowthActive) {
                IncrementalStreamingMarkdown(
                    streamSnapshot = streamSnapshot,
                    generationId = streamGenerationId,
                    messageId = streamMessageId,
                    sourceStartOffset = streamStartOffset,
                    modifier = Modifier,
                    cacheIdentity = cacheIdentity,
                    streamFreezeSignal = streamFreezeSignal
                )
            }
        }
        return
    }

    val latestContent = rememberUpdatedState(content)
    val displayContent = remember(streamGenerationId, streamMessageId, cacheIdentity) { mutableStateOf(content) }
    val oversizedPreview = remember(streamGenerationId, streamMessageId, cacheIdentity) { mutableStateOf<String?>(null) }
    val settled = remember { mutableStateOf(!isStreaming) }
    ObserveOversizedStreamingPreview(
        latestContent = latestContent,
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
        settled = settled,
        cacheIdentity = cacheIdentity
    )
    val document: @Composable (Modifier) -> Unit = { documentModifier ->
        RenderStreamingMarkdown(
            displayContent = displayContent.value,
            oversizedPreview = oversizedPreview.value,
            modifier = documentModifier,
            isStreaming = isStreaming,
            settled = settled.value,
            cacheIdentity = cacheIdentity,
            streamGenerationId = streamGenerationId
        )
    }
    if (isStreaming) {
        StreamGrowthViewport(modifier) { document(Modifier) }
    } else {
        document(modifier)
    }
}

@Composable
private fun ObserveOversizedStreamingPreview(
    latestContent: androidx.compose.runtime.State<String>,
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
    settled: MutableState<Boolean>,
    cacheIdentity: MarkdownRenderCacheIdentity?
) {
    LaunchedEffect(isStreaming, if (isStreaming) null else content) {
        if (isStreaming) {
            settled.value = false
            collectStreamingMarkdownChunks(latestContent, displayContent)
        } else {
            displayContent.value = latestContent.value
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
    val (state, fingerprint) = parseMarkdownWithFingerprint(finalText)
    MarkdownParseCache.put(finalText, state, cacheIdentity, fingerprint)
}

@Composable
private fun RenderStreamingMarkdown(
    displayContent: String,
    oversizedPreview: String?,
    modifier: Modifier,
    isStreaming: Boolean,
    settled: Boolean,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    streamGenerationId: Long?
) {
    when {
        // Once generation stops, the complete source reaches MarkdownRenderer.
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
