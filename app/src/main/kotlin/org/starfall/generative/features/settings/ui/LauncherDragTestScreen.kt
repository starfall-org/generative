package org.starfall.generative.features.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.extensions.launcher.Launcher

/** Test diagnostics remain available when the source correctly clears its accessibility subtree. */
internal val LauncherWorkspaceOrder = SemanticsPropertyKey<List<String>>("LauncherWorkspaceOrder")

private const val Workspace = "workspace"
private const val Folder = "folder"
private const val FolderItem = "folder-alpha"
private const val SecondFolderItem = "folder-beta"
private const val SecondFolder = "folder-beta-content"
private fun isFolderTile(item: String) = item == FolderItem || item == SecondFolderItem
private fun folderContent(item: String) = if (item == SecondFolderItem) SecondFolder else Folder
private fun folderDropKey(item: String) = if (item == SecondFolderItem) "folder-icon-beta" else "folder-icon"

private fun sampleLayout(): Map<String, List<String>> = mapOf(
    Workspace to listOf(FolderItem) + (1..3).map { "Tile $it" } + listOf(SecondFolderItem) + (4..18).map { "Tile $it" },
    Folder to (19..24).map { "Tile $it" },
    SecondFolder to (25..28).map { "Tile $it" }
)

/**
 * Isolated manual test harness for the reusable launcher drag system.
 * No provider data, repository or preferences are read or written.
 */
@Composable
fun LauncherDragTestScreen(onBack: () -> Unit) = with(Launcher) {
    val resources = LocalResources.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val state = Launcher.rememberState<String>()
    val layout = remember { Launcher.Layout({ it }, sampleLayout()) }
    var openFolderItem by remember { mutableStateOf<String?>(null) }
    val openFolder = openFolderItem != null
    val openFolderContent = folderContent(openFolderItem ?: FolderItem)
    var options by remember { mutableStateOf(LauncherDragTestOptions()) }
    val initial = stringResource(R.string.launcher_drag_test_initial)
    val cancelled = stringResource(R.string.launcher_drag_test_cancelled)
    val reset = stringResource(R.string.launcher_drag_test_reset_result)
    var lastOutcome by remember { mutableStateOf(initial) }
    val positions = remember { mutableMapOf<String, Rect>() }
    var previewedTarget by remember { mutableStateOf<LauncherPreviewedInsertion?>(null) }

    val proximityPx = with(LocalDensity.current) { options.reorderProximityDp.dp.toPx() }
    val reorderPolicy = if (options.reorderOnEntry) Launcher.Reorder.OnEntry else Launcher.Reorder.CrossCenter

    fun finish(outcome: Launcher.Outcome) {
        if (outcome.accepted) {
            layout.commit()
            if (outcome.targetKey == Workspace || outcome.targetKey?.startsWith("slot:$Workspace:") == true) {
                openFolderItem = null
            }
            lastOutcome = resources.getString(R.string.launcher_drag_test_dropped, outcome.targetKey.orEmpty())
        } else {
            layout.cancel()
            lastOutcome = cancelled
        }
    }

    fun nearestWorkspace(drag: Launcher.Session<String>): Pair<Launcher.ReorderHit, Rect>? {
        val prefix = "slot:$Workspace:"
        val ownKey = prefix + drag.item.key
        // The existing placeholder wins equal-distance ties, preventing gap oscillation.
        val cells = state.targetCells().filter { it.key.startsWith(prefix) }.sortedBy { it.key != ownKey }
        val hit = Launcher.geometry.closestSlot(drag.visualCenter, cells, horizontal = !options.list,
            rtl = rtl, maxDistancePx = proximityPx) ?: return null
        if (hit.key == ownKey) return null
        return hit to cells.first { it.key == hit.key }.boundsInWindow
    }

    fun enterWorkspace(drag: Launcher.Session<String>): Boolean {
        val (hit, bounds) = nearestWorkspace(drag) ?: return false
        val changed = layout.hover(Workspace, hit.key.removePrefix("slot:$Workspace:"), drag, bounds,
            reorderPolicy, !options.list, rtl)
        if (changed) previewedTarget = LauncherPreviewedInsertion(hit.key, bounds, drag.visualCenter, reorderPolicy, !options.list, rtl)
        return changed
    }

    fun handleBack() {
        if (state.session != null) state.cancel()
        else if (openFolder) openFolderItem = null
        else onBack()
    }
    BackHandler { handleBack() }

    val hoverIntentKey: Any? = state.session?.let { drag ->
        val target = state.hoveredTargetKey
        when {
            target == Workspace -> nearestWorkspace(drag)?.first
            target?.startsWith("slot:") == true && reorderPolicy == Launcher.Reorder.CrossCenter -> {
                positions[target]?.let { bounds ->
                    Launcher.ReorderHit(target, if (!options.list) (drag.visualCenter.x >= bounds.center.x) != rtl
                        else drag.visualCenter.y >= bounds.center.y)
                }
            }
            else -> target
        }
    }
    Launcher.HoverAction(state, intentKey = hoverIntentKey, delayMillis =
        if (state.hoveredTargetKey?.startsWith("folder-icon") == true) options.folderOpenDelayMillis else options.reorderDelayMillis
    ) { target, drag ->
        when {
            target.startsWith("folder-icon") && !isFolderTile(drag.item.key) -> {
                val folder = if (target == folderDropKey(SecondFolderItem)) SecondFolderItem else FolderItem
                val changed = layout.hover(folderContent(folder))
                val opened = openFolderItem != folder
                openFolderItem = folder
                changed || opened
            }
            target.startsWith("slot:") -> {
                val parts = target.removePrefix("slot:").split(":", limit = 2)
                if (parts.size == 2) {
                    val changed = positions[target]?.let { bounds ->
                        val moved = layout.hover(parts[0], parts[1], drag, bounds, reorderPolicy, !options.list, rtl)
                        if (moved) previewedTarget = LauncherPreviewedInsertion(target, bounds, drag.visualCenter, reorderPolicy, !options.list, rtl)
                        moved
                    } ?: false
                    changed
                } else false
            }
            target == Workspace -> enterWorkspace(drag)
            (target == Folder || target == SecondFolder) -> layout.enter(target)
            else -> false
        }
    }
    Launcher.FolderExit(
        state = state,
        folderTargetKey = "folder-region",
        open = openFolder,
        fromFolder = { it.key in layout.preview[openFolderContent].orEmpty() },
        onExit = { openFolderItem = null },
        delayMillis = Launcher.defaults.FolderExitDelayMillis
    )

    Scaffold(
        topBar = { MainAppbar(stringResource(R.string.launcher_drag_test_title), ::handleBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()
        )) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.launcher_drag_test_workspace), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = {
                    state.cancel()
                    layout.update(sampleLayout())
                    openFolderItem = null
                    lastOutcome = reset
                }, modifier = Modifier.testTag("launcher_reset")) { Text(stringResource(R.string.launcher_drag_test_reset)) }
                if (state.session != null) {
                    Button(onClick = { state.cancel() }, modifier = Modifier.testTag("launcher_cancel")) {
                        Text(stringResource(R.string.launcher_drag_test_cancel))
                    }
                }
            }
            Text(
                text = stringResource(R.string.launcher_drag_test_help),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LauncherDragTestControls(options, onChange = { options = it })
            val noneLabel = stringResource(R.string.launcher_drag_test_none)
            val source = state.session?.item?.key ?: noneLabel
            Text(
                stringResource(R.string.launcher_drag_test_status, source, state.hoveredTargetKey ?: noneLabel),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("launcher_drag_status"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                lastOutcome,
                Modifier.padding(horizontal = 16.dp).testTag("launcher_drag_result"),
                style = MaterialTheme.typography.labelMedium
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Launcher.Host(
                state = state,
                modifier = Modifier.fillMaxSize().testTag("launcher_drag_host"),
                preview = { drag ->
                    TestTile(drag.item.key, dragging = true)
                }
            ) {
                LauncherDragTestCollection(
                    items = layout.preview[Workspace].orEmpty(), state = state, options = options,
                    modifier = Modifier.fillMaxSize()
                        .dropTarget(state, Workspace, hitTest = Launcher.Collision.VisualCenter,
                            onDrop = { drag ->
                                val moved = enterWorkspace(drag)
                                moved || drag.item.key in layout.preview[Workspace].orEmpty()
                            })
                        .semantics { this[LauncherWorkspaceOrder] = layout.preview[Workspace].orEmpty() }
                        .testTag("launcher_workspace"),
                    padding = 16.dp, enabled = !openFolder
                ) { item, modifier, slot ->
                    TestDragTile(
                        item = item, modifier = modifier, container = Workspace, state = state,
                        sourceEnabled = !openFolder, targetEnabled = true,
                        onBegin = {
                            previewedTarget = null
                            layout.begin(item)
                            lastOutcome = resources.getString(R.string.launcher_drag_test_dragging, item, Workspace)
                        },
                        onEnd = ::finish, onOpenFolder = { openFolderItem = item },
                        positions = positions, layout = layout, previewedTarget = previewedTarget,
                        onPreviewInsertion = { previewedTarget = it },
                        slotBounds = slot, folderHitFraction = options.folderHitFraction, horizontal = !options.list,
                        reorderPolicy = reorderPolicy, folderJoinEnabled = options.folderJoinEnabled, rtl = rtl
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = openFolder,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 0.96f)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                            .heightIn(min = 210.dp, max = 320.dp)
                            .dropTarget(
                                state, "folder-region", priority = 20, enabled = openFolder,
                                hitTest = Launcher.Collision.VisualCenter,
                                accepts = { !isFolderTile(it.key) },
                                onDrop = { layout.enter(openFolderContent); true }
                            )
                            .testTag("launcher_folder_panel"),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(if (openFolderItem == SecondFolderItem) R.string.launcher_drag_test_folder_beta else R.string.launcher_drag_test_folder_alpha), Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(onClick = { openFolderItem = null }) { Text(stringResource(R.string.launcher_drag_test_close)) }
                            }
                            Spacer(Modifier.height(6.dp))
                            LauncherDragTestCollection(
                                items = layout.preview[openFolderContent].orEmpty(), state = state, options = options,
                                modifier = Modifier.fillMaxWidth().height(232.dp).testTag("launcher_folder_grid"),
                                padding = 4.dp, enabled = openFolder
                            ) { item, modifier, slot ->
                                TestDragTile(
                                    item = item, modifier = modifier, container = openFolderContent, state = state,
                                    sourceEnabled = openFolder, targetEnabled = openFolder,
                                    onBegin = {
                                        previewedTarget = null
                                        layout.begin(item)
                                        lastOutcome = resources.getString(R.string.launcher_drag_test_dragging, item, openFolderContent)
                                    },
                                    onEnd = ::finish, onOpenFolder = {},
                                    positions = positions, layout = layout, previewedTarget = previewedTarget,
                                    onPreviewInsertion = { previewedTarget = it },
                                    slotBounds = slot, folderHitFraction = options.folderHitFraction, horizontal = !options.list,
                        reorderPolicy = reorderPolicy, folderJoinEnabled = options.folderJoinEnabled, rtl = rtl
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestDragTile(
    item: String,
    modifier: Modifier = Modifier,
    container: String,
    state: Launcher.State<String>,
    sourceEnabled: Boolean,
    targetEnabled: Boolean,
    onBegin: () -> Unit,
    onEnd: (Launcher.Outcome) -> Unit,
    onOpenFolder: () -> Unit,
    positions: MutableMap<String, Rect>,
    layout: Launcher.Layout<String>,
    previewedTarget: LauncherPreviewedInsertion?,
    onPreviewInsertion: (LauncherPreviewedInsertion) -> Unit,
    slotBounds: Rect?,
    folderHitFraction: Float,
    horizontal: Boolean,
    reorderPolicy: Launcher.ReorderRule,
    folderJoinEnabled: Boolean,
    rtl: Boolean
) = with(Launcher) {
    val isFolder = isFolderTile(item)
    val targetKey = "slot:$container:$item"
    val joinKey = folderDropKey(item)
    SideEffect { if (slotBounds != null) positions[targetKey] = slotBounds }
    TestTile(
        item = item,
        modifier = modifier
            .onGloballyPositioned {
                positions[targetKey] = slotBounds ?: it.boundsInWindow()
                if (isFolder) positions[joinKey] = it.boundsInWindow()
            }
            .folderMember(
                state = state, sourceKey = item, data = item, targetKey = targetKey,
                sourceEnabled = sourceEnabled, targetEnabled = targetEnabled,
                sourcePriority = if (container != Workspace) 30 else 0,
                targetPriority = if (container != Workspace) 30 else 6,
                onDragStart = { onBegin() }, onDragEnd = onEnd,
                returnBounds = if (container != Workspace) ({ positions[folderDropKey(if (container == SecondFolder) SecondFolderItem else FolderItem)] }) else null,
                policy = Launcher.DropPolicy(
                    joinHitTest = if (isFolder && folderJoinEnabled)
                        Launcher.Collision.Center(folderHitFraction, folderHitFraction) else null
                ),
                hitBounds = slotBounds,
                acceptsReorder = { it.key != item },
                joinKey = joinKey,
                acceptsJoin = { !isFolderTile(it.key) },
                joinLandingBounds = { positions[joinKey] },
                onJoinDrop = if (isFolder && folderJoinEnabled) ({ layout.hover(folderContent(item)); true }) else null,
                onReorderEnter = { drag ->
                    if (isFolder && isFolderTile(drag.item.key) && drag.item.key != item) {
                        (slotBounds ?: positions[targetKey])?.let { bounds ->
                            val moved = layout.hover(container, item, drag, bounds,
                                Launcher.Reorder.OnEntry, horizontal, rtl)
                            if (moved) onPreviewInsertion(LauncherPreviewedInsertion(
                                targetKey, bounds, drag.visualCenter, Launcher.Reorder.OnEntry, horizontal, rtl
                            ))
                        }
                    }
                },
                onReorderDrop = { drag ->
                    (slotBounds ?: positions[targetKey])?.let { bounds ->
                        if (previewedTarget?.matches(targetKey, bounds, drag.visualCenter, reorderPolicy, horizontal, rtl) != true) {
                            layout.hover(container, item, drag, bounds, reorderPolicy, horizontal, rtl)
                        }
                    }
                    true
                }
            )
            .then(if (state.hoveredTargetKey == targetKey || isFolder && state.hoveredTargetKey == joinKey)
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                else Modifier)
            .then(if (isFolder) Modifier.clickable(onClick = onOpenFolder) else Modifier)
            .testTag("launcher_item_$item")
    )
}

@Composable
private fun TestTile(
    item: String,
    modifier: Modifier = Modifier,
    dragging: Boolean = false
) {
    Card(
        modifier = modifier.fillMaxWidth().height(88.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isFolderTile(item)) "▣" else "◆",
                    color = if (dragging) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (item == FolderItem) stringResource(R.string.launcher_drag_test_folder_alpha)
                    else if (item == SecondFolderItem) stringResource(R.string.launcher_drag_test_folder_beta)
                    else item,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/** Preserve an applied preview only while it still represents the same logical drop intent. */
private data class LauncherPreviewedInsertion(
    val key: String,
    val bounds: Rect,
    val center: Offset,
    val policy: Launcher.ReorderRule,
    val horizontal: Boolean,
    val rtl: Boolean
) {
    fun matches(key: String, bounds: Rect, center: Offset, policy: Launcher.ReorderRule,
                horizontal: Boolean, rtl: Boolean): Boolean {
        if (this.key != key || this.bounds != bounds || this.policy != policy ||
            this.horizontal != horizontal || this.rtl != rtl) return false
        return when (policy) {
            is Launcher.Reorder.Custom -> false
            Launcher.Reorder.CrossCenter -> if (horizontal) {
                (this.center.x >= bounds.center.x) == (center.x >= bounds.center.x)
            } else (this.center.y >= bounds.center.y) == (center.y >= bounds.center.y)
            else -> true
        }
    }
}
