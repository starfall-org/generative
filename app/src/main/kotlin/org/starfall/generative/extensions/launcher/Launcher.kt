package org.starfall.generative.extensions.launcher

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single-import entry point for the independent launcher interaction primitives.
 *
 * Use `with(Launcher) { ... }` around Compose content to enable the member
 * Modifier extensions. Methods delegate to the existing controller; this facade
 * does not own a second drag state, gesture detector, or persistence layer.
 */
object Launcher {
    typealias State<T> = LauncherDragDropState<T>
    typealias Item<T> = LauncherDragItem<T>
    typealias Session<T> = LauncherDragSession<T>
    typealias Outcome = LauncherDragOutcome
    typealias Layout<T> = LauncherReorderLayout<T>
    typealias Cell = LauncherCell
    typealias ReorderHit = LauncherReorderHit
    typealias ReorderRule = LauncherReorderPolicy
    typealias ReorderContext = LauncherReorderContext
    typealias DropPolicy = LauncherItemDropPolicy
    typealias CollisionRule = LauncherHitTest
    typealias Activation = LauncherDragActivation
    typealias Style = LauncherDragStyle

    val defaults = LauncherDragDefaults
    val geometry = LauncherGridGeometry
    val folderGeometry = LauncherFolderGeometry

    object Collision {
        val Pointer = LauncherHitTest.Pointer
        val VisualCenter = LauncherHitTest.VisualCenter
        typealias Center = LauncherHitTest.Center
        typealias Overlap = LauncherHitTest.Overlap
        typealias Custom = LauncherHitTest.Custom
    }

    object Reorder {
        val OnEntry = LauncherReorderPolicy.OnEntry
        val CrossCenter = LauncherReorderPolicy.CrossCenter
        val Before = LauncherReorderPolicy.Before
        val After = LauncherReorderPolicy.After
        typealias Custom = LauncherReorderPolicy.Custom
    }

    object ActivationMode {
        typealias LongPress = LauncherDragActivation.LongPress
        val AfterSlop = LauncherDragActivation.AfterSlop
    }

    @Composable
    fun <T : Any> rememberState(): State<T> = rememberLauncherDragDropState()

    @Composable
    fun <T : Any> Host(
        state: State<T>,
        modifier: Modifier = Modifier,
        preview: @Composable (Session<T>) -> Unit,
        activation: Activation = LauncherDragActivation.LongPress(),
        style: Style = LauncherDragStyle(),
        content: @Composable BoxScope.() -> Unit
    ) = LauncherDragDropHost(state, modifier, preview, activation, style, content)

    fun <T : Any> Modifier.dragSource(
        state: State<T>,
        key: String,
        data: T,
        enabled: Boolean = true,
        priority: Int = 0,
        onDragStart: (Session<T>) -> Unit = {},
        onDragEnd: (Outcome) -> Unit = {},
        dragRegion: Rect? = null,
        hideWhileDragging: Boolean = true,
        returnBounds: (() -> Rect?)? = null,
        handleBounds: Rect? = null
    ): Modifier = launcherDragSource(
        state, key, data, enabled, priority, onDragStart, onDragEnd,
        dragRegion, hideWhileDragging, returnBounds, handleBounds
    )

    fun <T : Any> Modifier.dropTarget(
        state: State<T>,
        key: String,
        enabled: Boolean = true,
        priority: Int = 0,
        accepts: (Item<T>) -> Boolean = { true },
        hitTest: CollisionRule = LauncherHitTest.Pointer,
        landingBounds: (() -> Rect?)? = null,
        hitBounds: Rect? = null,
        onDragEnter: (Session<T>) -> Unit = {},
        onDragMove: (Session<T>) -> Unit = {},
        onDragExit: (Session<T>) -> Unit = {},
        onDrop: (Session<T>) -> Boolean
    ): Modifier = launcherDropTarget(
        state, key, enabled, priority, accepts, hitTest, landingBounds, hitBounds,
        onDragEnter, onDragMove, onDragExit, onDrop
    )

    fun <T : Any> Modifier.itemDropTarget(
        state: State<T>,
        key: String,
        policy: DropPolicy = LauncherItemDropPolicy(),
        enabled: Boolean = true,
        priority: Int = 0,
        hitBounds: Rect? = null,
        acceptsReorder: (Item<T>) -> Boolean = { true },
        joinKey: String = "$key:join",
        joinPriority: Int = priority + 1,
        acceptsJoin: (Item<T>) -> Boolean = { true },
        joinLandingBounds: (() -> Rect?)? = null,
        onJoinDrop: ((Session<T>) -> Boolean)? = null,
        onReorderEnter: (Session<T>) -> Unit = {},
        onReorderDrop: (Session<T>) -> Boolean
    ): Modifier = launcherItemDropTarget(
        state, key, policy, enabled, priority, hitBounds, acceptsReorder,
        joinKey, joinPriority, acceptsJoin, joinLandingBounds, onJoinDrop, onReorderEnter, onReorderDrop
    )

    /** A customizable folder/workspace item with source, reorder, join and self-drop targets. */
    fun <T : Any> Modifier.folderMember(
        state: State<T>,
        sourceKey: String,
        data: T,
        targetKey: String,
        sourceEnabled: Boolean = true,
        targetEnabled: Boolean = true,
        sourcePriority: Int = 0,
        targetPriority: Int = 0,
        policy: DropPolicy = LauncherItemDropPolicy(),
        hitBounds: Rect? = null,
        acceptsReorder: (Item<T>) -> Boolean = { it.key != sourceKey },
        joinKey: String = "$targetKey:join",
        joinPriority: Int = targetPriority + 1,
        acceptsJoin: (Item<T>) -> Boolean = { true },
        joinLandingBounds: (() -> Rect?)? = null,
        onJoinDrop: ((Session<T>) -> Boolean)? = null,
        selfTargetKey: String? = null,
        selfPriority: Int = targetPriority + 1,
        onSelfDrop: ((Session<T>) -> Boolean)? = null,
        dragRegion: Rect? = null,
        handleBounds: Rect? = null,
        hideWhileDragging: Boolean = true,
        returnBounds: (() -> Rect?)? = null,
        onDragStart: (Session<T>) -> Unit = {},
        onDragEnd: (Outcome) -> Unit = {},
        onReorderEnter: (Session<T>) -> Unit = {},
        onReorderDrop: (Session<T>) -> Boolean
    ): Modifier = launcherFolderMember(
        state, sourceKey, data, targetKey, sourceEnabled, targetEnabled,
        sourcePriority, targetPriority, policy, hitBounds, acceptsReorder,
        joinKey, joinPriority, acceptsJoin, joinLandingBounds, onJoinDrop,
        selfTargetKey, selfPriority, onSelfDrop,
        dragRegion, handleBounds, hideWhileDragging, returnBounds,
        onDragStart, onDragEnd, onReorderEnter, onReorderDrop
    )

    @Composable
    fun <T : Any> Hover(
        state: State<T>,
        delayMillis: Long = LauncherDragDefaults.FolderReorderDelayMillis,
        movementThreshold: Dp = 8.dp,
        requireMovementAfterHover: Boolean = true,
        onHover: (targetKey: String, drag: Session<T>) -> Unit
    ) = LauncherHoverEffect(state, delayMillis, movementThreshold, requireMovementAfterHover, onHover)

    @Composable
    fun <T : Any> HoverAction(
        state: State<T>,
        delayMillis: Long = LauncherDragDefaults.ReorderDelayMillis,
        movementThreshold: Dp = 2.dp,
        requireMovementAfterHover: Boolean = true,
        intentKey: Any? = null,
        onHover: (targetKey: String, drag: Session<T>) -> Boolean
    ) = LauncherHoverActionEffect(
        state, delayMillis, movementThreshold, requireMovementAfterHover, intentKey, onHover
    )

    @Composable
    fun <T : Any> FolderExit(
        state: State<T>,
        folderTargetKey: String,
        open: Boolean,
        fromFolder: (Item<T>) -> Boolean,
        onExit: () -> Unit,
        delayMillis: Long = LauncherDragDefaults.FolderExitDelayMillis,
        extraExitRegion: (Session<T>) -> Boolean = { false }
    ) = LauncherFolderExitEffect(
        state, folderTargetKey, open, fromFolder, onExit, delayMillis, extraExitRegion
    )

    fun <T : Any> Modifier.autoScroll(
        state: State<T>,
        scrollState: ScrollableState,
        edge: Dp = 48.dp,
        maxSpeed: Dp = 640.dp,
        horizontal: Boolean = false,
        reverseLayout: Boolean = false,
        enabled: Boolean = true
    ): Modifier = if (enabled) launcherDragAutoScroll(
        state, scrollState, edge, maxSpeed, horizontal, reverseLayout
    ) else this
}
