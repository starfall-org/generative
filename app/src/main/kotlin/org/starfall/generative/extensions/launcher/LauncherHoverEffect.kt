package org.starfall.generative.extensions.launcher

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Reference delays from Lawnchair 16-dev. Rendering durations are Compose-specific. */
object LauncherDragDefaults {
    const val ReorderDelayMillis = 120L
    const val WorkspaceReorderDelayMillis = 650L
    const val FolderReorderDelayMillis = 250L
    const val FolderOpenDelayMillis = 800L
    const val FolderExitDelayMillis = 400L
    const val PlacementDurationMillis = 220
    const val LandingDurationMillis = 180
    const val PickupDurationMillis = 120
    const val PickupScale = 1.04f
}

/**
 * Launcher3-style delayed reorder / folder-open trigger. Changing targets cancels
 * the pending hover automatically; a static hover fires once, not every frame.
 */
@Composable
fun <T : Any> LauncherHoverEffect(
    state: LauncherDragDropState<T>,
    delayMillis: Long = LauncherDragDefaults.FolderReorderDelayMillis,
    movementThreshold: Dp = 8.dp,
    requireMovementAfterHover: Boolean = true,
    onHover: (targetKey: String, drag: LauncherDragSession<T>) -> Unit
) {
    LauncherHoverActionEffect(state, delayMillis, movementThreshold, requireMovementAfterHover) { target, drag ->
        onHover(target, drag)
        true
    }
}

/**
 * Dwell for a meaningful intent, then apply it. Return true only when the action changed
 * something; no-op callbacks must not consume the movement guard. intentKey can describe
 * a nearby slot or insertion half even when the registered container target stays the same.
 */
@Composable
fun <T : Any> LauncherHoverActionEffect(
    state: LauncherDragDropState<T>,
    delayMillis: Long = LauncherDragDefaults.ReorderDelayMillis,
    movementThreshold: Dp = 2.dp,
    requireMovementAfterHover: Boolean = true,
    intentKey: Any? = null,
    onHover: (targetKey: String, drag: LauncherDragSession<T>) -> Boolean
) {
    val target = state.hoveredTargetKey
    val dragKey = state.session?.item?.key
    val currentOnHover = rememberUpdatedState(onHover)
    var lastAppliedPointer by remember(state, state.session?.identity) { mutableStateOf<Offset?>(null) }
    val threshold = with(LocalDensity.current) { movementThreshold.toPx().coerceAtLeast(1f) }
    val pointer = state.session?.pointer
    val armed = pointer != null && (!requireMovementAfterHover || state.isAutoScrolling ||
        lastAppliedPointer?.let { (pointer - it).getDistance() >= threshold } != false)
    LaunchedEffect(state, state.session?.identity, target, delayMillis, armed, intentKey) {
        if (!armed || dragKey == null || target == null) return@LaunchedEffect
        delay(delayMillis.coerceAtLeast(0L))
        val drag = state.session ?: return@LaunchedEffect
        if (drag.item.key == dragKey && state.hoveredTargetKey == target) {
            // Delayed hover mutations must receive the same scroll anchoring as
            // immediate drop-target enter mutations.
            state.preserveScrollViewportDuringReorder(drag.pointer)
            if (currentOnHover.value(target, drag)) lastAppliedPointer = drag.pointer
        }
    }
}
