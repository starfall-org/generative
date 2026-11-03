package org.starfall.generative.features.providers.list.ui


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import org.starfall.generative.shared.model.provider.ProviderRootOrderItem
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import org.starfall.generative.extensions.launcher.Launcher
import org.starfall.generative.shared.ui.lists.LauncherCollection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.icons.EntityIcon
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.providerInitials
import org.starfall.generative.shared.ui.lists.ListViewToggle

/**
 * A folder shows its own logo when it has one (picked, or recognised from the folder name).
 * Only a folder without a logo falls back to a 2x2 preview of its first four providers.
 */
@Composable
internal fun ProviderFolderPreview(
    group: ProviderGroup,
    providers: List<ModelProvider>,
    modifier: Modifier = Modifier
) {
    val previewProviders = providers.take(4)
    EntityIcon(
        image = group.icon,
        modifier = modifier,
        fallback = Icons.Outlined.Folder,
        matchName = group.name,
        fallbackContent = if (previewProviders.isEmpty()) null else ({ ProviderFolderMiniGrid(group.id, previewProviders) })
    )
}

@Composable
private fun ProviderFolderMiniGrid(groupId: String, providers: List<ModelProvider>) {
    Column(
        modifier = Modifier.fillMaxSize().padding(4.dp).testTag("provider_group_preview_$groupId"),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        providers.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row.forEach { provider ->
                    EntityIcon(
                        image = provider.icon,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        text = providerInitials(provider.name),
                        matchName = provider.name
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private const val FolderPageOpacity = 0.6f
internal const val FolderOpenDurationMillis = 600 // 20% longer than the previous 500ms.
internal const val FolderCloseDurationMillis = 450
private const val FolderCollapsedWidthFraction = 0.30f

/**
 * The page starts/ends at up to 30% of the host width, centred over the actual folder tile.
 * Preserve the tile's aspect ratio instead of jumping to the middle of the screen.
 */
internal fun folderCollapsedRect(source: Rect?, width: Float): Rect? {
    val card = source ?: return null
    if (card.width <= 0f || card.height <= 0f || width <= 0f) return card
    val scale = (width * FolderCollapsedWidthFraction / card.width).coerceAtMost(1f)
    val halfWidth = card.width * scale / 2f
    val halfHeight = card.height * scale / 2f
    return Rect(
        card.center.x - halfWidth, card.center.y - halfHeight,
        card.center.x + halfWidth, card.center.y + halfHeight
    )
}

/** Bounds of the opening/closing page relative to the shared, full-screen drag host. */
internal fun folderExpansionRect(source: Rect?, width: Float, height: Float, progress: Float): Rect {
    val full = Rect(0f, 0f, width, height)
    val from = folderCollapsedRect(source, width) ?: full
    val t = progress.coerceIn(0f, 1f)
    fun interpolate(a: Float, b: Float) = a + (b - a) * t
    return Rect(
        interpolate(from.left, full.left),
        interpolate(from.top, full.top),
        interpolate(from.right, full.right),
        interpolate(from.bottom, full.bottom)
    )
}

/**
 * Gesture-only folder dismissal: inward swipes from either screen edge and
 * horizontal or downward swipes starting in the header. Never treat vertical
 * scrolling in the item grid as a dismissal.
 */
internal fun folderDismissSwipe(
    start: Offset,
    end: Offset,
    viewport: Rect,
    header: Rect,
    edgeWidth: Float,
    minDistance: Float
): Boolean {
    if (viewport.width <= 0f) return false
    val dx = end.x - start.x
    val dy = end.y - start.y
    val horizontal = kotlin.math.abs(dx) >= minDistance &&
        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f
    return (horizontal && (
        (start.x <= viewport.left + edgeWidth && dx > 0f) ||
        (start.x >= viewport.right - edgeWidth && dx < 0f)
    )) || (header.contains(start) && (
        horizontal || (dy >= minDistance && dy > kotlin.math.abs(dx) * 1.25f)
    ))
}

/** Full-screen folder page. Its header belongs to the folder, never to ProvidersScreen's app bar. */
@Composable
internal fun ProviderFolderOverlay(
    group: ProviderGroup,
    providers: List<ModelProvider>,
    isGrid: Boolean,
    originBounds: Rect?,
    hostOrigin: Offset,
    animateExitImmediately: Boolean,
    dndState: Launcher.State<ProviderRootOrderItem>,
    onBeginDrag: (String) -> Unit,
    onEndDrag: (String, Launcher.Outcome) -> Unit,
    onReorderMember: (String, String) -> Unit,
    onDropMember: () -> Boolean,
    showExitHint: Boolean,
    visible: Boolean,
    animateEnter: Boolean = true,
    onExitFinished: () -> Unit,
    onDismiss: () -> Unit,
    onAddProvider: () -> Unit,
    onToggleGridView: () -> Unit,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onEditProvider: (ModelProvider) -> Unit,
    onMoveProvider: (ModelProvider) -> Unit,
    onDeleteProvider: (String) -> Unit
) = with(Launcher) {
    val gridState = rememberLazyGridState()
    // Keep the detail expansion and full-screen backdrop fade on the same reversible timeline.
    // The scaled starting rect stays centred on the tapped card, even after root scrolling.
    val progress = remember { Animatable(if (animateEnter) 0f else 1f) }
    LaunchedEffect(visible, animateExitImmediately) {
        if (visible) {
            if (animateEnter) progress.animateTo(
                1f,
                animationSpec = tween(durationMillis = FolderOpenDurationMillis, easing = FastOutSlowInEasing)
            ) else progress.snapTo(1f)
        } else {
            // When dragging a member out, uncover the root grid immediately for drop targeting.
            if (animateExitImmediately) progress.snapTo(0f)
            else progress.animateTo(
                0f,
                animationSpec = tween(durationMillis = FolderCloseDurationMillis, easing = FastOutSlowInEasing)
            )
            onExitFinished()
        }
    }
    val fraction = progress.value.coerceIn(0f, 1f)
    val contentAlpha = ((fraction - 0.72f) / 0.22f).coerceIn(0f, 1f)
    val start = originBounds?.translate(-hostOrigin)
    // A stationary translucent background fades in/out instead of growing from the tapped card.
    val endColor = MaterialTheme.colorScheme.background.copy(alpha = FolderPageOpacity)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var viewportBounds by remember { mutableStateOf(Rect.Zero) }
    var headerBounds by remember { mutableStateOf(Rect.Zero) }
    val latestViewport by rememberUpdatedState(viewportBounds)
    val latestHeader by rememberUpdatedState(headerBounds)
    val edgeWidth = with(LocalDensity.current) { 56.dp.toPx() }
    val swipeDistance = with(LocalDensity.current) { 56.dp.toPx() }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { viewportBounds = it.boundsInWindow() }
            // Retain the closing artwork but remove the full-screen hit target immediately.
            .then(if (visible) Modifier.pointerInput(dndState, edgeWidth, swipeDistance) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false, pass = PointerEventPass.Initial
                    )
                    val bounds = latestViewport
                    val start = bounds.topLeft + down.position
                    val candidate = start.x <= bounds.left + edgeWidth ||
                        start.x >= bounds.right - edgeWidth || latestHeader.contains(start)
                    if (!candidate) return@awaitEachGesture

                    // Observe on Initial without intercepting taps or vertical grid scroll.
                    // A long-press drag belongs exclusively to the Launcher host.
                    while (dndState.session == null) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.id != down.id && it.pressed }) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (folderDismissSwipe(
                                start, latestViewport.topLeft + change.position,
                                latestViewport, latestHeader, edgeWidth, swipeDistance
                            )) {
                            change.consume()
                            currentOnDismiss()
                            break
                        }
                    }
                }
            } else Modifier)
    ) {
        val fullWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val fullHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val currentBounds = folderExpansionRect(start, fullWidthPx, fullHeightPx, fraction)
        val cornerRadius = 22.dp * (1f - fraction)
        val shape = RoundedCornerShape(cornerRadius)
        // Only an open folder blocks the root list; closing keeps drawing without taking touches.
        if (visible) Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
        )
        // This backdrop never inherits the card's bounds, scale or rounded corners.
        // Its opacity follows the same progress in both directions, including interrupted exits.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = fraction }
                .background(endColor)
        )
        // Only the folder details expand/contract from the tapped tile.
        FolderAnimationContent(
            interactive = visible,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = currentBounds.left
                    translationY = currentBounds.top
                    this.scaleX = currentBounds.width / fullWidthPx
                    this.scaleY = currentBounds.height / fullHeightPx
                    alpha = if (start == null) fraction else (fraction * 6f).coerceIn(0f, 1f)
                    this.shape = shape
                    clip = true
                }
                .graphicsLayer { alpha = contentAlpha }
                .testTag("provider_folder_screen_${group.id}")
        ) {
            Box(
                Modifier.fillMaxSize()
                    // Taps on any empty space close the folder. Tiles, header buttons and scrolling
                    // consume their own gestures first, so only unclaimed taps reach this handler.
                    // pointerInput (not clickable) keeps the children's semantics unmerged.
                    .pointerInput(Unit) { detectTapGestures { currentOnDismiss() } }
            ) {
                Column(
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                ) {
                    // Header actions remain below the status bar; the grid below may
                    // scroll into the transparent navigation-bar region.
                    Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)
                        .onGloballyPositioned { headerBounds = it.boundsInWindow() }
                        .testTag("provider_folder_header_gesture_${group.id}")
                    ) {
                        ProviderFolderHeader(
                            group = group,
                            providers = providers,
                            isGrid = isGrid,
                            onAddProvider = onAddProvider,
                            onToggleGridView = onToggleGridView,
                            onEditGroup = onEditGroup,
                            onDeleteGroup = onDeleteGroup
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (providers.isEmpty()) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            SharedIcon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.empty_provider_group),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LauncherCollection(
                            items = providers,
                            key = { "provider_${it.id}" },
                            state = dndState,
                            gridState = gridState,
                            columns = if (isGrid) 2 else 1,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                                .dropTarget(dndState, "folder-grid:${group.id}", priority = -10,
                                    enabled = visible, onDrop = { false })
                                .testTag("provider_folder_grid_${group.id}"),
                            contentPadding = PaddingValues(
                                start = 16.dp, top = 8.dp, end = 16.dp,
                                bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            ),
                            horizontalSpacing = 12.dp,
                            verticalSpacing = 12.dp,
                            // A one-column folder has roughly twice the scroll distance
                            // of the grid for the same number of providers. Keep its
                            // top/bottom-edge drag traversal responsive without changing
                            // the reusable Launcher's default scroll speed.
                            autoScrollSpeed = if (isGrid) 640.dp else 960.dp,
                            enabled = visible
                        ) { provider, itemModifier, slot ->
                            val key = "provider_${provider.id}"
                            ProviderFolderProviderTile(
                                provider = provider,
                                isGrid = isGrid,
                                onEdit = { onEditProvider(provider) },
                                onMoveToGroup = { onMoveProvider(provider) },
                                onDelete = { onDeleteProvider(provider.id) },
                                modifier = itemModifier.folderMember(
                                    state = dndState,
                                    sourceKey = key,
                                    data = ProviderRootOrderItem(provider.id, false),
                                    targetKey = "folder-slot:${provider.id}",
                                    sourcePriority = 30,
                                    targetPriority = 30,
                                    sourceEnabled = visible && fraction >= 0.999f,
                                    targetEnabled = visible && fraction >= 0.999f,
                                    policy = Launcher.DropPolicy(
                                        reorderHitTest = Launcher.Collision.Pointer
                                    ),
                                    hitBounds = slot,
                                    acceptsReorder = { !it.data.isGroup && it.data.id != provider.id },
                                    selfTargetKey = "folder-source:${provider.id}",
                                    selfPriority = 31,
                                    onSelfDrop = { onDropMember() },
                                    onDragStart = { onBeginDrag(key) },
                                    onDragEnd = { outcome -> onEndDrag(key, outcome) },
                                    onReorderEnter = { drag ->
                                        onReorderMember(drag.item.data.id, provider.id)
                                    },
                                    onReorderDrop = { onDropMember(); true }
                                )
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = showExitHint,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) { ProviderFolderExitHint() }
            }
        }
    }
}

@Composable
private fun ProviderFolderHeader(
    group: ProviderGroup,
    providers: List<ModelProvider>,
    isGrid: Boolean,
    onAddProvider: () -> Unit,
    onToggleGridView: () -> Unit,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
            .testTag("provider_folder_header_${group.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProviderFolderPreview(
            group = group,
            providers = providers,
            modifier = Modifier.size(80.dp).testTag("provider_folder_header_icon_${group.id}")
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().testTag("provider_folder_header_name_${group.id}")
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("provider_folder_toolbar_${group.id}")
            ) {
                ProviderGroupContextMenu(onEdit = onEditGroup, onDelete = onDeleteGroup, compact = false)
                ListViewToggle(isGrid, onToggleGridView)
                IconButton(onClick = onAddProvider, modifier = Modifier.testTag("add_folder_provider")) {
                    SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
                }
            }
        }
    }
}

/**
 * Hint shown just above the nav bar while a folder member is dragged. It is a plain translucent label
 * (no Surface, no click handling), so it never intercepts touches meant for what sits behind it.
 */
@Composable
private fun ProviderFolderExitHint() {
    Text(
        text = stringResource(R.string.drag_to_exit_folder_hint),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("provider_folder_exit_hint")
    )
}

/** Retain the last rendered contents during exit, without placing their touch targets. */
@Composable
internal fun FolderAnimationContent(
    interactive: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val layer = rememberGraphicsLayer()
    Layout(
        content = { Box(Modifier.fillMaxSize()) { content() } },
        modifier = modifier.drawWithContent {
            if (interactive) layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
    ) { measurables, constraints ->
        val child = measurables.single().measure(constraints)
        layout(child.width, child.height) {
            if (interactive) child.placeRelative(0, 0)
        }
    }
}
