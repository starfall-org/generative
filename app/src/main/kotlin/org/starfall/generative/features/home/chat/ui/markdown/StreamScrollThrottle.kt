package org.starfall.generative.features.home.chat.ui.markdown

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Minimum gap between two applications of streamed text while the user scrolls the chat list.
 * Text keeps moving (about 8 applications per second) but stops forcing a parse, measure and
 * draw of the live tail for every published token during a scroll frame.
 */
internal const val SCROLL_STREAM_APPLY_INTERVAL_MS = 120L

private val NotScrolling: State<Boolean> = mutableStateOf(false)

/**
 * True while the chat list is being scrolled. It is a [State] so reading it inside a coroutine or
 * `snapshotFlow` does not recompose every streaming row when scrolling starts or stops.
 */
internal val LocalChatListScrolling = staticCompositionLocalOf { NotScrolling }

/**
 * Throttle stream applications during a scroll. The caller re-reads the latest stream snapshot
 * afterwards, coalescing publications received during the wait into one parser update.
 *
 * When scrolling stops, resume immediately instead of keeping the final update behind the
 * remaining throttle delay. This never waits while the list is idle.
 */
internal suspend fun awaitStreamApplySlot(
    scrolling: State<Boolean>,
    intervalMs: Long = SCROLL_STREAM_APPLY_INTERVAL_MS
) {
    if (!scrolling.value || intervalMs <= 0L) return
    withTimeoutOrNull(intervalMs) {
        snapshotFlow { scrolling.value }.first { !it }
    }
}
