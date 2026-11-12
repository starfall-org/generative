package org.starfall.generative.features.home.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.layoutBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.starfall.generative.features.home.chat.logic.SentMessageDetector
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState
import org.starfall.generative.features.home.chat.model.ChatStreamStatus
import org.starfall.generative.features.home.chat.ui.markdown.LocalChatListScrolling
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.StoredMessage

/** False while the retained chat page is offscreen. */
internal val LocalChatForeground = compositionLocalOf { true }

/** Keep the active chat's transient viewers while its code preview is on top. */
internal val LocalChatKeepOverlayState = compositionLocalOf { false }

/** Eligibility and one replaceable intent. LazyColumn owns all measurement, motion and arbitration. */
@Stable
internal class ChatMessageListState(initialMessages: List<StoredMessage> = emptyList()) {
    // The final keyed boundary is part of initial measurement. Compose fills the viewport
    // with preceding chronological content; no opening scroll or synthetic offset is needed.
    val listState = LazyListState(firstVisibleItemIndex = chatMessageTurns(initialMessages).size)
    val sentMessages = SentMessageDetector()
    internal val jumpViewportBounds = LayoutBoundsHolder()
    internal val latestContentBounds = LayoutBoundsHolder()
    // Button visibility only: these bounds/estimates never enter scroll intent or motion.
    var showJumpToLatest by mutableStateOf(false)
        private set

    internal fun calculateJumpVisibility(): Boolean {
        val viewport = jumpViewportBounds.bounds ?: return false
        val layout = listState.layoutInfo
        val screenHeight = layout.viewportSize.height
        if (screenHeight <= 0) return false
        val contentEnd = latestContentBounds.bounds
        val distance = if (contentEnd != null) {
            contentEnd.boundsInRoot.bottom.toFloat() - viewport.boundsInRoot.bottom
        } else {
            // LazyColumn cannot measure virtualized history exactly. Use its native estimate,
            // excluding trailing list padding (including the 21% gap). The readable viewport
            // ends above the composer.
            val indicator = listState.scrollIndicatorState ?: return false
            val composerInset = screenHeight - layout.beforeContentPadding - viewport.height
            (indicator.contentSize.toLong() - indicator.scrollOffset - indicator.viewportSize -
                layout.afterContentPadding + composerInset).toFloat()
        }
        return distance >= 2f * screenHeight
    }

    internal fun updateJumpVisibility(visible: Boolean) { showJumpToLatest = visible }

    internal val starts = mutableStateMapOf<String, BringIntoViewRequester>()
    internal val ends = mutableStateMapOf<String, BringIntoViewRequester>()
    internal val visibleEnds = mutableStateMapOf<String, Boolean>()
    internal var request by mutableStateOf<ChatListRequest?>(null)
        private set
    var detached by mutableStateOf(false)
        private set
    private var returningToEnd = false
    private var observedContentEndId: String? = null
    private var observedContentEndY: Int? = null
    // Latched only after the actual end marker has entered the readable viewport.
    // Growth can temporarily move that marker out of view before native anchoring catches up.
    private var pinnedLatestId: String? = null
    internal var requestCount = 0L
        private set

    fun resume() { detached = false; returningToEnd = false }
    fun detach() { detached = true; returningToEnd = false; request = null; pinnedLatestId = null }
    fun send(id: String) { resume(); request = ChatListRequest(++requestCount, ChatListAction.Send(id)) }
    fun jump() { resume(); request = ChatListRequest(++requestCount, ChatListAction.Jump) }
    fun navigate(id: String, toEnd: Boolean = false) {
        val action = ChatListAction.Navigate(id, toEnd)
        if (request?.action == action) return
        detach()
        request = ChatListRequest(++requestCount, action)
    }
    fun follow(enabled: Boolean) {
        if (enabled && !detached && (request == null || request?.action == ChatListAction.Follow)) {
            request = ChatListRequest(++requestCount, ChatListAction.Follow)
        }
    }
    fun cancelFollow() { if (request?.action == ChatListAction.Follow) request = null }
    fun complete(completed: ChatListRequest) { if (request == completed) request = null }
    fun isLatestVisible(id: String?): Boolean = id != null && visibleEnds[id] == true
    fun isLatestPinned(id: String?): Boolean = id != null && pinnedLatestId == id && !detached
    fun manualScroll(towardsEnd: Boolean) { detach(); returningToEnd = towardsEnd }
    /**
     * Streaming has two layout modes with auto-scroll enabled:
     * - At latest: offset the LazyColumn by measured growth, keeping the invisible clip edge fixed.
     * - Away from latest: do not move the LazyColumn; previously visible text stays stationary
     *   while the renderer's actual clipping edge keeps growing downward with new content.
     * The renderer always owns clipping; this method only decides whether the list follows it.
     */
    fun contentEndPosition(
        id: String, y: Int, enabled: Boolean,
        anchorMeasuredGrowth: (Int) -> Boolean = { false }
    ) {
        if (observedContentEndId != id) {
            observedContentEndId = id
            observedContentEndY = y
            pinnedLatestId = id.takeIf { isLatestVisible(it) && !detached }
        } else if (observedContentEndY != y) {
            val growth = y - (observedContentEndY ?: y)
            observedContentEndY = y
            if (!enabled || !isLatestPinned(id)) return
            if (growth > 0 &&
                (request == null || request?.action == ChatListAction.Follow) &&
                anchorMeasuredGrowth(growth)
            ) {
                // Native anchoring follows each measured layout frame rather than scheduling
                // another animated bring-into-view that trails the already moving edge.
                cancelFollow()
            } else {
                follow(enabled)
            }
        }
    }
    fun endVisibility(id: String, visible: Boolean, latestId: String?) {
        visibleEnds[id] = visible
        if (visible && id == latestId) {
            if (returningToEnd) resume()
            if (!detached) pinnedLatestId = id
        }
    }
}

private enum class ChatListBoundary { End }

internal data class ChatListRequest(val sequence: Long, val action: ChatListAction)
internal sealed interface ChatListAction {
    data class Send(val id: String) : ChatListAction
    data class Navigate(val id: String, val toEnd: Boolean = false) : ChatListAction
    data object Jump : ChatListAction
    data object Follow : ChatListAction
}

/** Turns and their message children retain ordinary chronological order. */
internal fun chatMessageTurns(messages: List<StoredMessage>): List<List<StoredMessage>> {
    val turns = mutableListOf<MutableList<StoredMessage>>()
    messages.forEach { message ->
        if (turns.isEmpty() || message.role == ChatRole.USER) turns.add(mutableListOf())
        turns.last().add(message)
    }
    return turns
}

/** Declarative allowance, independent of child content, streaming status and renderer readiness. */
internal fun chatBottomGap(screenHeight: Dp, usableHeight: Dp): Dp =
    (screenHeight.coerceAtLeast(0.dp) * 0.21f).coerceAtMost(usableHeight.coerceAtLeast(0.dp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatMessageList(
    state: ChatMessageListState,
    messages: List<StoredMessage>,
    streaming: Boolean,
    autoScroll: Boolean,
    historyLoading: Boolean,
    activeStream: StateFlow<ActiveChatStreamState?>?,
    appBarHeight: Dp,
    bottomClearance: Dp,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    messageContent: @Composable (StoredMessage) -> Unit
) {
    val foreground = LocalChatForeground.current
    val turns = remember(messages) { chatMessageTurns(messages) }
    val currentTurns by rememberUpdatedState(turns)
    val latestId = messages.lastOrNull()?.id
    val currentLatestId by rememberUpdatedState(latestId)
    val canFollow by rememberUpdatedState {
        val stream = activeStream?.value
        foreground && autoScroll && streaming && (stream == null ||
            (stream.status == ChatStreamStatus.STREAMING && !stream.stopSignal.isFrozen))
    }
    val listState = state.listState
    LaunchedEffect(state) {
        // Avoid observing layout and bounds on every scroll frame. When scrolling ends,
        // snapshotFlow reads the latest geometry and resumes observing growth and resizing.
        snapshotFlow {
            if (listState.isScrollInProgress) null else state.calculateJumpVisibility()
        }.collect { visible ->
            if (visible != null) state.updateJumpVisibility(visible)
        }
    }
    val userDrag = listState.interactionSource.collectIsDraggedAsState()
    val readableBounds = remember { LayoutBoundsHolder() }
    val scrollConnection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (userDrag.value && source == NestedScrollSource.UserInput && available.y != 0f) {
                    state.manualScroll(towardsEnd = available.y < 0f)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y < 0f) currentLatestId?.let {
                    state.endVisibility(it, state.isLatestVisible(it), it)
                }
                return Offset.Zero
            }
        }
    }
    // A single cancellable consumer serves Send, Jump, minimap and content-follow requests.
    // No opening intent exists. New requests supersede obsolete motion instead of queuing chunks.
    LaunchedEffect(state, foreground) {
        if (!foreground) return@LaunchedEffect
        snapshotFlow { state.request }.collectLatest { request ->
            if (request == null) return@collectLatest
            try {
                val action = request.action
                val id = when (action) {
                    is ChatListAction.Send -> action.id
                    is ChatListAction.Navigate -> action.id
                    ChatListAction.Jump, ChatListAction.Follow -> currentLatestId
                } ?: return@collectLatest
                if (action != ChatListAction.Follow) {
                    // Send can arrive before the new message has entered composition.
                    // Keep that intent until its chronological data is applied.
                    val index = snapshotFlow {
                        currentTurns.indexOfFirst { turn -> turn.any { it.id == id } }
                    }.first { it >= 0 }
                    if (action == ChatListAction.Jump) listState.animateScrollToItem(currentTurns.size)
                    else listState.scrollToItem(index)
                    val targets = if (action is ChatListAction.Send ||
                        (action is ChatListAction.Navigate && !action.toEnd)) state.starts else state.ends
                    snapshotFlow { targets[id] }.filterNotNull().first().bringIntoView()
                } else if (canFollow() && !state.detached) {
                    state.ends[id]?.bringIntoView()
                }
            } finally {
                state.complete(request)
            }
        }
    }
    var pausedOffscreen by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state, foreground, autoScroll, streaming) {
        if (!foreground) {
            pausedOffscreen = true
            state.cancelFollow()
        } else if (pausedOffscreen) {
            pausedOffscreen = false
            state.follow(canFollow() && state.isLatestPinned(currentLatestId)) // Resume only if latest was pinned.
        } else if (!autoScroll || !streaming) {
            state.cancelFollow()
        }
    }
    LaunchedEffect(state, activeStream) {
        activeStream?.map { stream -> stream?.let { it.status to it.stopSignal } }
            ?.distinctUntilChanged()?.collectLatest { lifecycle ->
                if (lifecycle == null || lifecycle.first != ChatStreamStatus.STREAMING) {
                    state.cancelFollow()
                } else {
                    lifecycle.second.frozen.collect { frozen -> if (frozen) state.cancelFollow() }
                }
            }
    }
    BoxWithConstraints(modifier) {
        val gap = if (messages.lastOrNull()?.role == ChatRole.MODEL)
            chatBottomGap(maxHeight, maxHeight - appBarHeight - bottomClearance) else 0.dp
        val minimumTurnHeight = (maxHeight - appBarHeight - bottomClearance - gap).coerceAtLeast(0.dp)
        // Native visibility detection accounts for the app bar, composer and intentional breathing
        // room. No content bounds/heights or distance-to-end calculations enter the intent gate.
        Box(Modifier.fillMaxSize().padding(top = appBarHeight, bottom = bottomClearance)
            .layoutBounds(state.jumpViewportBounds)
            .padding(bottom = gap).layoutBounds(readableBounds))
        val nativeRelocation = LocalBringIntoViewSpec.current
        val density = LocalDensity.current
        val topInset = with(density) { appBarHeight.toPx() }
        val bottomInset = with(density) { (bottomClearance + gap).toPx() }
        val insetRelocation = remember(nativeRelocation, topInset, bottomInset) {
            object : BringIntoViewSpec {
                // Translate the declarative occluding insets only. Compose's original policy
                // still computes displacement and owns animation; no message geometry is read.
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                    nativeRelocation.calculateScrollDistance(
                        offset - topInset, size, (containerSize - topInset - bottomInset).coerceAtLeast(0f)
                    )
            }
        }
        // A State, not a value: streaming rows read it inside coroutines, so a scroll starting or
        // stopping never recomposes them. They use it to apply streamed text at a bounded rate.
        val listScrolling = remember(listState) { derivedStateOf { listState.isScrollInProgress } }
        ChatOpeningVeil(active = historyLoading, modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides insetRelocation,
            LocalChatListScrolling provides listScrolling
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = false,
                // Keep scrolling content intact; ChatAppBar alone paints the themed scrim.
                modifier = Modifier.fillMaxSize().testTag("chat-message-list")
                    .nestedScroll(scrollConnection),
                contentPadding = PaddingValues(top = appBarHeight, bottom = bottomClearance + gap)
            ) {
                itemsIndexed(turns, key = { _, turn -> turn.first().id }) { index, turn ->
                    // Only a minimum: fillParentMaxHeight also fixes maxHeight and truncates a
                    // growing turn. Leave the lazy item's natural maximum height unbounded.
                    Column(if (index == turns.lastIndex) Modifier.heightIn(min = minimumTurnHeight) else Modifier) {
                        turn.forEach { message ->
                            key(message.id) {
                                val start = remember { BringIntoViewRequester() }
                                val end = remember { BringIntoViewRequester() }
                                DisposableEffect(message.id, state) {
                                    state.starts[message.id] = start
                                    state.ends[message.id] = end
                                    onDispose {
                                        state.starts.remove(message.id)
                                        state.ends.remove(message.id)
                                        state.visibleEnds.remove(message.id)
                                    }
                                }
                                Box(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(start))
                                messageContent(message)
                                Box(Modifier.fillMaxWidth().height(1.dp).testTag("chat-content-end-${message.id}")
                                    .bringIntoViewRequester(end)
                                    .then(if (message.id == latestId)
                                        Modifier.layoutBounds(state.latestContentBounds) else Modifier)
                                    .onVisibilityChanged(viewportBounds = readableBounds) {
                                        state.endVisibility(message.id, it, currentLatestId)
                                    }
                                    .onGloballyPositioned { coordinates ->
                                        if (message.id == currentLatestId) {
                                            // Relative to the message column: ordinary list scrolling
                                            // does not move this marker, but rendered content growth does.
                                            state.contentEndPosition(message.id,
                                                coordinates.positionInParent().y.toInt(), canFollow()) { growth ->
                                                // requestScrollToItem is a native LazyColumn anchor adjustment:
                                                // no manual velocity, delta animation or stream-token tracking.
                                                listState.requestScrollToItem(
                                                    listState.firstVisibleItemIndex,
                                                    listState.firstVisibleItemScrollOffset + growth
                                                )
                                                true
                                            }
                                        }
                                    })
                            }
                        }
                        if (index == turns.lastIndex) footer?.invoke()
                    }
                }
                // An empty conversation can still report a failed first save/submission.
                // Keep that error in the same chronological list, never inside InputArea.
                if (turns.isEmpty() && footer != null) {
                    item(key = "chat-empty-footer") { footer() }
                }
                // This zero-height boundary selects the first viewport at the chronological end.
                // Actual follow requests still target the real-content markers inside each turn,
                // before any unused minimum-height room and the list's trailing content padding.
                item(key = ChatListBoundary.End) { Spacer(Modifier.height(0.dp)) }
            }
        }
        }
    }
}
