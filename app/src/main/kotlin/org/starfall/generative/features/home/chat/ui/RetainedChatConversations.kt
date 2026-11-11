package org.starfall.generative.features.home.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import org.starfall.generative.shared.model.Conversation

/** The available heap, rather than a fixed chat count, determines what stays warm. */
internal fun retainedHeapFraction(used: Long, maximum: Long): Float {
    if (maximum <= 0L) return 0f
    val freeFraction = (1.0 - used.toDouble() / maximum).coerceIn(0.0, 1.0)
    return ((freeFraction - 0.15) / 0.20).toFloat().coerceIn(0f, 1f)
}
private const val EMPTY_CHAT_ID = "__empty_chat__"

/** Keeps visited chat compositions until heap pressure or Android memory callbacks evict LRU pages. */
internal class RetainedChatPages : EvictableCache {
    val ids = mutableStateListOf<String>()
    private val snapshots = mutableMapOf<String, Conversation>()
    private val selections = mutableMapOf<String, ChatAttachmentSelection>()
    private var activeId: String? = null
    private var temporaryId: String? = null

    fun selection(id: String?): ChatAttachmentSelection =
        selections.getOrPut(id ?: EMPTY_CHAT_ID) { ChatAttachmentSelection() }

    fun snapshot(id: String): Conversation? = snapshots[id]

    /** Only committed compositions are retained; a changing stream updates the same snapshot. */
    fun record(conversation: Conversation?, availableIds: Set<String>) {
        val id = conversation?.id
        val switching = activeId != id
        activeId = id
        if (temporaryId != id) temporaryId?.let { selections.remove(it) }
        temporaryId = id.takeIf { conversation?.isTemporary == true }
        if (conversation != null && !conversation.isTemporary) {
            snapshots[conversation.id] = conversation
            if (conversation.id !in ids) ids.add(conversation.id)
            else if (switching) {
                ids.remove(conversation.id)
                ids.add(conversation.id)
            }
        }
        // The sidebar is the source of truth for deletion, but not while it is still loading.
        if (availableIds.isNotEmpty()) ids.toList().forEach { cached ->
            if (cached != id && cached !in availableIds) remove(cached)
        }
        // Read heap use only on navigation, not for every incoming streaming chunk.
        if (switching) {
            val heap = Runtime.getRuntime()
            val used = heap.totalMemory() - heap.freeMemory()
            trimInactive(retainedHeapFraction(used, heap.maxMemory()))
        }
    }

    fun discard(conversationIds: Set<String>) {
        conversationIds.forEach(::remove)
    }

    private fun remove(id: String) {
        ids.remove(id)
        snapshots.remove(id)
        selections.remove(id)
    }

    private fun trimInactive(fraction: Float) {
        val inactiveCount = ids.count { it != activeId }
        val keep = (inactiveCount * fraction.coerceIn(0f, 1f)).toInt()
        repeat(inactiveCount - keep) { remove(ids.first { it != activeId }) }
    }

    override fun trimToFraction(fraction: Float) = trimInactive(fraction)

}

/** A parked chat stays composed but consumes no measure, placement or drawing work. */
private fun Modifier.parkChat(visible: Boolean): Modifier = layout { measurable, constraints ->
    if (visible) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else layout(0, 0) { }
}

@Composable
internal fun RetainedChatConversations(
    state: RetainedChatPages,
    conversation: Conversation?,
    availableIds: Set<String>,
    foreground: Boolean,
    keepOverlayState: Boolean = false,
    content: @Composable (Conversation?, Boolean) -> Unit
) {
    DisposableEffect(state) {
        ChatRenderCaches.register(state)
        onDispose { ChatRenderCaches.unregister(state) }
    }
    SideEffect { state.record(conversation, availableIds) }
    val activeKey = conversation?.id ?: EMPTY_CHAT_ID
    Box(Modifier.fillMaxSize()) {
        (state.ids.filter { it != activeKey } + activeKey).forEach { id ->
            val active = id == activeKey
            val page = if (active) conversation else state.snapshot(id)
            if (!active && page == null) return@forEach
            key(id) {
                Box(Modifier.fillMaxSize().parkChat(active)) {
                    CompositionLocalProvider(
                        LocalChatForeground provides (active && foreground),
                        LocalChatKeepOverlayState provides (active && keepOverlayState),
                        LocalChatAttachmentSelection provides state.selection(id)
                    ) {
                        content(page, active)
                    }
                }
            }
        }
    }
}
