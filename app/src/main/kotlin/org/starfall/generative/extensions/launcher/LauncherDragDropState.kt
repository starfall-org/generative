package org.starfall.generative.extensions.launcher

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/** Stable identity even while the source is temporarily removed from composition. */
data class LauncherDragItem<T : Any>(val key: String, val data: T)

/** All coordinates are in the window, not relative to a moving folder or item. */
data class LauncherDragSession<T : Any>(
    val item: LauncherDragItem<T>,
    val sourceBounds: Rect,
    val startPointer: Offset,
    val pointer: Offset,
    /** Artwork relative to sourceBounds.topLeft, in window pixels (e.g. an icon without its label). */
    val dragRegion: Rect = Rect(Offset.Zero, sourceBounds.size),
    internal val identity: Any = Any()
) {
    val shadowBounds: Rect get() = sourceBounds.translate(pointer - startPointer)
    val visualCenter: Offset get() = shadowBounds.topLeft + dragRegion.center
}

data class LauncherDragOutcome(val accepted: Boolean, val targetKey: String?)

/** Rendering-only tail of a completed transaction. Storage callbacks have already run. */
internal data class LauncherDragSettlement<T : Any>(
    val drag: LauncherDragSession<T>,
    val landingBounds: (() -> Rect?)?,
    val returnBounds: (() -> Rect?)?,
    val accepted: Boolean
)

internal data class LauncherSourceHit(
    val token: Any, val bounds: Rect, val dragRegion: Rect? = null, val localSize: Size = bounds.size
)

internal class LauncherSource<T : Any>(
    val token: Any,
    var item: LauncherDragItem<T>,
    var priority: Int,
    var enabled: Boolean,
    var bounds: Rect = Rect.Zero,
    var onStart: (LauncherDragSession<T>) -> Unit,
    var onEnd: (LauncherDragOutcome) -> Unit,
    var hitBounds: Rect = bounds,
    var dragRegion: Rect? = null,
    var localSize: Size = bounds.size,
    var returnBounds: (() -> Rect?)? = null,
    var handleBounds: Rect? = null
)

internal class LauncherTarget<T : Any>(
    val token: Any,
    var key: String,
    var priority: Int,
    var enabled: Boolean,
    var bounds: Rect = Rect.Zero,
    var accepts: (LauncherDragItem<T>) -> Boolean,
    var onEnter: (LauncherDragSession<T>) -> Unit,
    var onMove: (LauncherDragSession<T>) -> Unit,
    var onExit: (LauncherDragSession<T>) -> Unit,
    var onDrop: (LauncherDragSession<T>) -> Boolean,
    var hitTest: LauncherHitTest = LauncherHitTest.Pointer,
    var landingBounds: (() -> Rect?)? = null,
    var hitBounds: Rect? = null
)

/**
 * Compose adaptation of Launcher3's single-controller / multiple-target approach.
 *
 * A gesture is owned by the stable host, never by an item that may disappear when
 * moving between a folder and the workspace. Source completion is transactional:
 * an unaccepted drop produces a failure outcome; consumers restore their snapshot.
 */
@Stable
class LauncherDragDropState<T : Any> {
    private val sources = linkedMapOf<Any, LauncherSource<T>>()
    private val targets = linkedMapOf<Any, LauncherTarget<T>>()
    private var hoveredToken: Any? = null
    private var sourceCompletion: ((LauncherDragOutcome) -> Unit)? = null

    private var sourceToken: Any? = null
    private var sourceReturnBounds: (() -> Rect?)? = null
    private var geometryVersion by mutableIntStateOf(0)
    private var scrollingContainers by mutableStateOf(emptySet<Any>())
    private val scrollViewports = mutableMapOf<Any, Pair<ScrollableState, Rect>>()
    val isAutoScrolling: Boolean get() = scrollingContainers.isNotEmpty()

    internal fun registerScrollViewport(token: Any, scrollState: ScrollableState, bounds: Rect) {
        scrollViewports[token] = scrollState to bounds
    }

    internal fun removeScrollViewport(token: Any) {
        scrollViewports.remove(token)
    }

    /**
     * Lazy lists maintain the first visible key across dataset changes. If a drag
     * reorder moves that key, the entire viewport can jump to the moved item.
     * Anchor by index/offset for the next measure before invoking the hover
     * callback that may reorder items. Only the viewport under the pointer is
     * affected; ordinary scrolling and non-drag content updates remain untouched.
     */
    internal fun preserveScrollViewportDuringReorder(pointer: Offset) {
        scrollViewports.values.forEach { (scrollState, bounds) ->
            if (!bounds.contains(pointer)) return@forEach
            when (scrollState) {
                is LazyGridState -> scrollState.requestScrollToItem(
                    scrollState.firstVisibleItemIndex, scrollState.firstVisibleItemScrollOffset
                )
                is LazyListState -> scrollState.requestScrollToItem(
                    scrollState.firstVisibleItemIndex, scrollState.firstVisibleItemScrollOffset
                )
            }
        }
    }

    internal fun setAutoScrolling(token: Any, scrolling: Boolean) {
        scrollingContainers = if (scrolling) scrollingContainers + token else scrollingContainers - token
    }

    private var refreshing = false
    private var refreshPending = false
    internal var settlement: LauncherDragSettlement<T>? by mutableStateOf(null)
        private set

    /** Keep the original hidden until the shadow has landed, including after a container change. */
    fun isDragging(key: String): Boolean = session?.item?.key == key || settlement?.drag?.item?.key == key

    internal fun clearSettlement(value: LauncherDragSettlement<T>? = settlement) {
        if (settlement === value) settlement = null
    }

    internal fun sourceRect(key: String): Rect? = sources.values
        .filter { it.item.key == key && it.enabled && it.bounds.width > 0f && it.bounds.height > 0f &&
            it.hitBounds.width > 0f && it.hitBounds.height > 0f }
        .maxByOrNull { it.priority }?.bounds

    var session: LauncherDragSession<T>? by mutableStateOf(null)
        private set
    var hoveredTargetKey: String? by mutableStateOf(null)
        private set
    var hostOriginInWindow: Offset by mutableStateOf(Offset.Zero)
        internal set

    internal fun putSource(source: LauncherSource<T>) {
        if (sourceToken == source.token) {
            sourceCompletion = source.onEnd
            sourceReturnBounds = source.returnBounds
        }
        val current = sources[source.token]
        if (current == null) sources[source.token] = source
        else {
            current.item = source.item
            current.priority = source.priority
            current.enabled = source.enabled
            current.onStart = source.onStart
            current.onEnd = source.onEnd
            current.dragRegion = source.dragRegion
            current.returnBounds = source.returnBounds
            current.handleBounds = source.handleBounds
        }
    }

    internal fun sourceBounds(token: Any, bounds: Rect, hitBounds: Rect = bounds, localSize: Size = bounds.size) {
        sources[token]?.let { it.bounds = bounds; it.hitBounds = hitBounds; it.localSize = localSize }
    }

    internal fun removeSource(token: Any) {
        sources.remove(token) // Keep the active session and its completion callback.
    }

    internal fun putTarget(target: LauncherTarget<T>) {
        val current = targets[target.token]
        if (current == null) {
            targets[target.token] = target
            geometryVersion++
        } else {
            if (current.enabled != target.enabled || current.key != target.key || current.hitBounds != target.hitBounds) geometryVersion++
            current.key = target.key
            current.priority = target.priority
            current.enabled = target.enabled
            current.accepts = target.accepts
            current.onEnter = target.onEnter
            current.onMove = target.onMove
            current.onExit = target.onExit
            current.onDrop = target.onDrop
            current.hitTest = target.hitTest
            current.landingBounds = target.landingBounds
            current.hitBounds = target.hitBounds
        }
        refreshTarget()
    }

    internal fun targetBounds(token: Any, bounds: Rect) {
        val target = targets[token] ?: return
        if (target.bounds == bounds) return
        target.bounds = bounds
        // Explicit logical slots do not move when only their drawing animates.
        if (target.hitBounds == null) {
            geometryVersion++
            refreshTarget()
        }
    }

    internal fun removeTarget(token: Any) {
        val old = targets.remove(token)
        geometryVersion++
        if (hoveredToken == token) {
            hoveredToken = null
            hoveredTargetKey = null
            session?.let { old?.onExit?.invoke(it) }
        }
        refreshTarget()
    }

    /** Enabled measured regions, for nearest-slot policies in gaps or at container edges. */
    fun targetCells(): List<LauncherCell> {
        @Suppress("UNUSED_VARIABLE") val version = geometryVersion
        return targets.values.toList().mapNotNull { target ->
            val bounds = target.hitBounds ?: target.bounds
            if (target.enabled && bounds.isUsable()) LauncherCell(target.key, bounds) else null
        }
    }

    fun isPointerInside(targetKey: String): Boolean = isInside(targetKey, session?.pointer)

    /** Close only when the center of the dragged artwork has left the folder. */
    fun isVisualCenterInside(targetKey: String): Boolean = isInside(targetKey, session?.visualCenter)

    private fun isInside(targetKey: String, point: Offset?): Boolean {
        @Suppress("UNUSED_VARIABLE") val version = geometryVersion
        if (point == null) return false
        return targets.values.any { it.enabled && it.key == targetKey && (it.hitBounds ?: it.bounds).contains(point) }
    }

    internal fun sourceHitAt(point: Offset): LauncherSourceHit? =
        sourceAt(point)?.let { LauncherSourceHit(it.token, it.bounds, it.dragRegion, it.localSize) }

    private fun sourceAt(point: Offset): LauncherSource<T>? = sources.values
        .filter { it.enabled && it.bounds.isUsable() && it.hitBounds.contains(point) &&
            (it.handleBounds?.let { handle ->
                val sx = it.bounds.width / it.localSize.width.coerceAtLeast(1f)
                val sy = it.bounds.height / it.localSize.height.coerceAtLeast(1f)
                Rect(handle.left * sx, handle.top * sy, handle.right * sx, handle.bottom * sy)
                    .translate(it.bounds.topLeft).contains(point)
            } ?: true) }
        .maxWithOrNull(compareBy<LauncherSource<T>> { it.priority }
            .thenBy { -it.bounds.width * it.bounds.height })

    internal fun start(downPoint: Offset, currentPoint: Offset = downPoint): Boolean =
        sourceHitAt(downPoint)?.let { start(it, downPoint, currentPoint) } == true

    internal fun start(hit: LauncherSourceHit, downPoint: Offset, currentPoint: Offset): Boolean {
        if (session != null) return false
        val source = sources[hit.token]?.takeIf { it.enabled } ?: return false
        if (!downPoint.x.isFinite() || !downPoint.y.isFinite() ||
            !currentPoint.x.isFinite() || !currentPoint.y.isFinite()) return false
        clearSettlement()
        val fullRegion = Rect(Offset.Zero, hit.bounds.size)
        val region = hit.dragRegion?.let {
            val sx = if (hit.localSize.width > 0f) hit.bounds.width / hit.localSize.width else 1f
            val sy = if (hit.localSize.height > 0f) hit.bounds.height / hit.localSize.height else 1f
            Rect(it.left * sx, it.top * sy, it.right * sx, it.bottom * sy).intersect(fullRegion)
        }?.takeIf { it.width > 0f && it.height > 0f } ?: fullRegion
        val active = LauncherDragSession(source.item, hit.bounds, downPoint, currentPoint, region)
        sourceToken = hit.token
        sourceReturnBounds = source.returnBounds
        sourceCompletion = source.onEnd
        session = active
        try {
            source.onStart(active)
            refreshTarget()
        } catch (error: Throwable) {
            cancel()
            throw error
        }
        return true
    }

    internal fun move(point: Offset) {
        val current = session ?: return
        if (!point.x.isFinite() || !point.y.isFinite()) return
        session = current.copy(pointer = point)
        refreshTarget(notifyMove = true)
    }

    private fun refreshTarget(notifyMove: Boolean = false) {
        if (refreshing) { refreshPending = true; return }
        refreshing = true
        var deliverMove = notifyMove
        try {
            do {
                refreshPending = false
                val drag = session ?: break
                // Snapshot the registry: callbacks may mount/unmount a folder synchronously.
                val next = targets.values.toList()
                    .filter { it.enabled && it.hitTest.contains(drag, it.hitBounds ?: it.bounds) &&
                        it.accepts(drag.item) }
                    .maxWithOrNull(compareBy<LauncherTarget<T>> { it.priority }
                        .thenBy { val bounds = it.hitBounds ?: it.bounds; -bounds.width * bounds.height })
                if (hoveredToken != next?.token) {
                    val previous = hoveredToken?.let { targets[it] }
                    hoveredToken = null
                    hoveredTargetKey = null
                    previous?.onExit?.invoke(drag)
                    if (session !== drag) { refreshPending = session != null; continue }
                    if (next != null && targets[next.token] === next && next.enabled) {
                        hoveredToken = next.token
                        hoveredTargetKey = next.key
                        preserveScrollViewportDuringReorder(drag.pointer)
                        next.onEnter(drag)
                    }
                }
                if (deliverMove && session === drag && hoveredToken == next?.token) {
                    deliverMove = false
                    next?.onMove?.invoke(drag)
                }
            } while (refreshPending)
        } finally {
            refreshing = false
        }
    }

    fun drop(): LauncherDragOutcome {
        val drag = session ?: return LauncherDragOutcome(false, null)
        refreshTarget()
        val target = hoveredToken?.let { targets[it] }
        return try {
            // Drop callbacks can also change the data without a preceding hover.
            preserveScrollViewportDuringReorder(drag.pointer)
            val accepted = target?.takeIf { it.enabled && it.accepts(drag.item) }?.onDrop?.invoke(drag) == true
            if (session !== drag) return LauncherDragOutcome(false, null)
            finish(LauncherDragOutcome(accepted, target?.key?.takeIf { accepted }),
                if (accepted) target.landingBounds else null)
        } catch (error: Throwable) {
            if (session === drag) finish(LauncherDragOutcome(false, null))
            throw error
        }
    }

    fun cancel(): LauncherDragOutcome = finish(LauncherDragOutcome(false, null))

    private fun finish(outcome: LauncherDragOutcome, landingBounds: (() -> Rect?)? = null): LauncherDragOutcome {
        val drag = session ?: return outcome
        val completion = sources[sourceToken]?.onEnd ?: sourceCompletion
        val exitTarget = hoveredToken?.let { targets[it] }
        // Clear before invoking callbacks; they may remove a folder or start navigation.
        settlement = LauncherDragSettlement(drag, landingBounds, sourceReturnBounds, outcome.accepted)
        session = null
        sourceToken = null
        sourceReturnBounds = null
        hoveredToken = null
        hoveredTargetKey = null
        sourceCompletion = null
        scrollingContainers = emptySet()
        // Completion can commit or roll back the preview while the dragged key
        // still occupies the first visible row.
        preserveScrollViewportDuringReorder(drag.pointer)
        try { exitTarget?.onExit?.invoke(drag) } finally { completion?.invoke(outcome) }
        return outcome
    }
}
