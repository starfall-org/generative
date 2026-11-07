package org.starfall.generative.features.home.chat.ui.markdown

import androidx.compose.runtime.*
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderCacheIdentity
import org.starfall.generative.features.home.chat.logic.markdown.PersistentMarkdownAstCache
import org.starfall.generative.features.home.chat.logic.markdown.parseLibraryMarkdown
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderWork
import org.starfall.generative.features.home.chat.ui.AssetKey
import org.starfall.generative.features.home.chat.ui.MarkdownParseCache
import org.starfall.generative.features.home.chat.ui.TrackPendingWork
import org.starfall.generative.features.home.chat.ui.TrackReadyAssets

private data class MarkdownParseRequest(
    val content: String,
    val identity: MarkdownRenderCacheIdentity?,
    val isStreaming: Boolean
)

private data class MarkdownParseResult(
    val state: State,
    val fingerprint: String?,
    val epoch: org.starfall.generative.features.home.chat.logic.markdown.MarkdownAstCacheEpoch?,
    val diskCacheMiss: Boolean
)

internal data class MarkdownParsePresentation(val state: State, val fingerprint: String?)

@Composable
internal fun rememberMarkdownParsePresentation(
    content: String,
    initialParse: State?,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    isStreaming: Boolean,
    persistentCache: PersistentMarkdownAstCache
): MarkdownParsePresentation {
    val latestContent by rememberUpdatedState(content)
    val latestCacheIdentity by rememberUpdatedState(cacheIdentity)
    val latestInitialParse by rememberUpdatedState(initialParse)
    val streamingNow by rememberUpdatedState(isStreaming)
    val inheritedFingerprint = LocalMarkdownContentFingerprint.current
    val latestInheritedFingerprint by rememberUpdatedState(inheritedFingerprint)
    val initialParsed = remember { initialParse ?: MarkdownParseCache.get(content, cacheIdentity) }
    var parsed by remember { mutableStateOf<State>(initialParsed ?: State.Loading()) }
    var parsedFingerprint by remember {
        mutableStateOf(inheritedFingerprint ?: MarkdownParseCache.getFingerprint(content, cacheIdentity))
    }
    val settledParse = parsed
    TrackPendingWork(settledParse is State.Loading)
    TrackReadyAssets(
        if (settledParse is State.Success) listOf(AssetKey.Markdown(settledParse.content, cacheIdentity)) else emptyList()
    )

    LaunchedEffect(Unit) {
        val requests = snapshotFlow {
            MarkdownParseRequest(latestContent, latestCacheIdentity, streamingNow)
        }
        collectMarkdownParseUpdates(
            requests = requests,
            initialParse = { latestInitialParse },
            inheritedFingerprint = { latestInheritedFingerprint },
            persistentCache = persistentCache
        ) { state, fingerprint ->
            parsed = state
            parsedFingerprint = fingerprint
        }
    }
    LaunchedEffect(isStreaming, settledParse, content, cacheIdentity, parsedFingerprint) {
        persistSettledMarkdown(settledParse, content, isStreaming, cacheIdentity, parsedFingerprint, persistentCache)
    }
    return MarkdownParsePresentation(parsed, parsedFingerprint)
}

private suspend fun collectMarkdownParseUpdates(
    requests: kotlinx.coroutines.flow.Flow<MarkdownParseRequest>,
    initialParse: () -> State?,
    inheritedFingerprint: () -> String?,
    persistentCache: PersistentMarkdownAstCache,
    onParsed: (State, String?) -> Unit
) {
    requests.conflate().collect { request ->
        val result = resolveMarkdownParseRequest(request, initialParse, inheritedFingerprint, persistentCache)
        onParsed(result.state, result.fingerprint)
        val state = result.state
        if (!request.isStreaming && state is State.Success) {
            MarkdownParseCache.put(request.content, state, request.identity, result.fingerprint)
            val epoch = result.epoch
            if (request.identity != null && result.diskCacheMiss && epoch != null) {
                MarkdownRenderWork.run { persistentCache.store(request.identity, request.content, state, epoch) }
            }
        }
    }
}

private suspend fun resolveMarkdownParseRequest(
    request: MarkdownParseRequest,
    initialParse: () -> State?,
    inheritedFingerprint: () -> String?,
    persistentCache: PersistentMarkdownAstCache
): MarkdownParseResult {
    val epoch = request.identity?.let(persistentCache::captureEpoch)
    val inMemory = MarkdownParseCache.get(request.content, request.identity)
    val memoryFingerprint = if (inMemory != null) {
        MarkdownParseCache.getFingerprint(request.content, request.identity)
    } else null
    val onDisk = if (inMemory == null && !request.isStreaming && request.identity != null && epoch != null) {
        MarkdownRenderWork.run { persistentCache.load(request.identity, request.content, epoch) }
    } else null
    val seeded = initialParse()?.takeIf { it is State.Success && it.content == request.content }
    val resolved = resolveMarkdownParseState(inMemory, memoryFingerprint, onDisk, seeded, inheritedFingerprint(), request.content)
    // Only a state that came from neither cache is new to the disk. A memory hit is persisted (once, and
    // only if the entry is missing) by persistSettledMarkdown, not re-written on every mount.
    return MarkdownParseResult(resolved.first, resolved.second, epoch, diskCacheMiss = inMemory == null && onDisk == null)
}

private suspend fun resolveMarkdownParseState(
    inMemory: State.Success?,
    memoryFingerprint: String?,
    onDisk: State.Success?,
    seeded: State?,
    inheritedFingerprint: String?,
    content: String
): Pair<State, String?> = when {
    inMemory != null -> inMemory to (
        memoryFingerprint ?: withContext(Dispatchers.Default) { markdownContentFingerprint(content) }
        )
    onDisk != null -> onDisk to withContext(Dispatchers.Default) { markdownContentFingerprint(content) }
    seeded is State.Success -> seeded to (
        inheritedFingerprint ?: withContext(Dispatchers.Default) { markdownContentFingerprint(content) }
        )
    else -> parseMarkdownWithFingerprint(content)
}

private suspend fun persistSettledMarkdown(
    state: State,
    content: String,
    isStreaming: Boolean,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    fingerprint: String?,
    persistentCache: PersistentMarkdownAstCache
) {
    if (isStreaming || state !is State.Success || state.content != content) return
    val settledFingerprint = fingerprint ?: withContext(Dispatchers.Default) { markdownContentFingerprint(content) }
    MarkdownParseCache.put(state.content, state, cacheIdentity, settledFingerprint)
    if (cacheIdentity != null) {
        // Epoch capture hashes IDs and the store checks the disk: keep both off the Main thread, which
        // runs this effect for every message that is mounted while a chat opens or scrolls.
        withContext(Dispatchers.IO) {
            val epoch = persistentCache.captureEpoch(cacheIdentity)
            MarkdownRenderWork.run { persistentCache.store(cacheIdentity, content, state, epoch) }
        }
    }
}

/** Parse off the UI thread and record work consistently for settled and streaming documents. */
internal suspend fun parseMarkdownWithFingerprint(content: String): Pair<State, String?> =
    withContext(Dispatchers.Default) {
        val startedAt = System.nanoTime()
        val state = try {
            parseLibraryMarkdown(content)
        } finally {
            MarkdownParseCache.recordFullParse(content.length, System.nanoTime() - startedAt)
        }
        state to if (state is State.Success) markdownContentFingerprint(content) else null
    }
