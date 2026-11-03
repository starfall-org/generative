package org.starfall.generative.features.home.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState

private const val ANSWER_PROBE_CHARS = 256

private fun ActiveChatStreamState?.forMessage(conversationId: String?, messageId: String): ActiveChatStreamState? =
    this?.takeIf { it.conversationId == conversationId && it.messageId == messageId }

/**
 * Reasoning text exactly as the live stream has committed it.
 *
 * The stored message version is not saved while the block is streaming, so building the actions
 * block from the stored version alone leaves live thinking hidden or stale. This collects only
 * the reasoning snapshot (recomposing when its length changes, not for answer-text commits).
 * Returns null when the message is not streaming or the stream has no reasoning for it.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun rememberLiveStreamReasoning(
    streamStateFlow: StateFlow<ActiveChatStreamState?>?,
    conversationId: String?,
    messageId: String,
    enabled: Boolean
): String? {
    if (!enabled || streamStateFlow == null) return null
    val snapshot by remember(streamStateFlow, conversationId, messageId) {
        streamStateFlow
            .map { it.forMessage(conversationId, messageId)?.reasoning }
            .distinctUntilChanged { old, new -> old?.length == new?.length }
            // Materializing a long reasoning snapshot is O(total reasoning length).
            // Cap this work to ten UI updates/s; all intermediate text remains in the
            // append-only stream and the final saved message retains the full reasoning.
            .sample(100L)
    }.collectAsState(initial = null)
    val current = snapshot ?: return null
    return remember(current) { current.materialize() }
}

/**
 * True once the live answer has non-blank text at or after [offset]. The stored version's content lags
 * the stream, so relying on it would keep a finished actions block expanded while the answer is
 * already streaming. Recomposes only when the value flips.
 */
@Composable
internal fun rememberStreamAnswerStarted(
    streamStateFlow: StateFlow<ActiveChatStreamState?>?,
    conversationId: String?,
    messageId: String,
    offset: Int,
    enabled: Boolean
): Boolean {
    if (!enabled || streamStateFlow == null) return false
    fun started(state: ActiveChatStreamState?): Boolean =
        state.forMessage(conversationId, messageId)?.content
            ?.takeIf { it.length > offset }
            ?.slice(offset, offset + ANSWER_PROBE_CHARS)
            ?.isNotBlank() == true
    val answerStarted by remember(streamStateFlow, conversationId, messageId, offset) {
        streamStateFlow.map { started(it) }.distinctUntilChanged()
    }.collectAsState(initial = false)
    return answerStarted
}
