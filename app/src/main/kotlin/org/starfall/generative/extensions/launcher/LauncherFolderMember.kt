package org.starfall.generative.extensions.launcher

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/**
 * One reusable launcher item registration for a workspace or an open folder.
 *
 * Rendering, the item model and persistence stay with the caller. Source,
 * logical reorder slot, optional folder-join hitbox and optional self-drop
 * protection share a single policy. No database work occurs during a hover.
 */
fun <T : Any> Modifier.launcherFolderMember(
    state: LauncherDragDropState<T>,
    sourceKey: String,
    data: T,
    targetKey: String,
    sourceEnabled: Boolean = true,
    targetEnabled: Boolean = true,
    sourcePriority: Int = 0,
    targetPriority: Int = 0,
    policy: LauncherItemDropPolicy = LauncherItemDropPolicy(),
    hitBounds: Rect? = null,
    acceptsReorder: (LauncherDragItem<T>) -> Boolean = { it.key != sourceKey },
    joinKey: String = "$targetKey:join",
    joinPriority: Int = targetPriority + 1,
    acceptsJoin: (LauncherDragItem<T>) -> Boolean = { true },
    joinLandingBounds: (() -> Rect?)? = null,
    onJoinDrop: ((LauncherDragSession<T>) -> Boolean)? = null,
    selfTargetKey: String? = null,
    selfPriority: Int = targetPriority + 1,
    onSelfDrop: ((LauncherDragSession<T>) -> Boolean)? = null,
    dragRegion: Rect? = null,
    handleBounds: Rect? = null,
    hideWhileDragging: Boolean = true,
    returnBounds: (() -> Rect?)? = null,
    onDragStart: (LauncherDragSession<T>) -> Unit = {},
    onDragEnd: (LauncherDragOutcome) -> Unit = {},
    onReorderEnter: (LauncherDragSession<T>) -> Unit = {},
    onReorderDrop: (LauncherDragSession<T>) -> Boolean
): Modifier {
    require(selfTargetKey == null || selfTargetKey != targetKey && selfTargetKey != joinKey) {
        "A folder member's self-drop key must differ from its reorder and join keys"
    }
    val member = launcherDragSource(
        state = state, key = sourceKey, data = data,
        enabled = sourceEnabled, priority = sourcePriority,
        onDragStart = onDragStart, onDragEnd = onDragEnd, returnBounds = returnBounds,
        dragRegion = dragRegion, handleBounds = handleBounds,
        hideWhileDragging = hideWhileDragging
    ).launcherItemDropTarget(
        state = state, key = targetKey, policy = policy,
        enabled = targetEnabled, priority = targetPriority, hitBounds = hitBounds,
        acceptsReorder = acceptsReorder,
        joinKey = joinKey, joinPriority = joinPriority,
        acceptsJoin = acceptsJoin, joinLandingBounds = joinLandingBounds,
        onJoinDrop = onJoinDrop, onReorderEnter = onReorderEnter,
        onReorderDrop = onReorderDrop
    )
    return if (selfTargetKey != null && onSelfDrop != null) member.launcherDropTarget(
        state = state, key = selfTargetKey, priority = selfPriority,
        enabled = targetEnabled, hitBounds = hitBounds,
        accepts = { it.key == sourceKey }, onDrop = onSelfDrop
    ) else member
}
