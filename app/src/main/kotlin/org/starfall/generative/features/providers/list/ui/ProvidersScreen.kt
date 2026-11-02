package org.starfall.generative.features.providers.list.ui

import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import org.starfall.generative.extensions.launcher.Launcher
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.features.providers.list.logic.*
import org.starfall.generative.features.providers.shared.logic.isEmpty
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.defaultAuthorization
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.model.provider.ProviderPlacement
import org.starfall.generative.shared.model.provider.ProviderRootOrderItem
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.lists.FadeGridListContent
import org.starfall.generative.shared.ui.lists.moved

internal fun newProviderId(): String = "custom_${UUID.randomUUID()}"

@Composable
fun ProvidersScreen(
    providers: List<ModelProvider>,
    providerGroups: List<ProviderGroup> = emptyList(),
    collapsedSectionsState: Set<String> = emptySet(),
    onCollapsedSectionsChange: (Set<String>) -> Unit = {},
    isGridView: Boolean = false,
    onToggleGridView: ((Boolean) -> Unit)? = null,
    onOpenProviderEditor: (ModelProvider, Boolean) -> Unit,
    onSaveGroup: (ProviderGroup) -> Unit = {},
    onSaveGroupIcon: (ProviderGroup) -> Unit = {},
    onDeleteGroup: (String) -> Unit = {},
    onMoveProviderToGroup: (String, String?) -> Unit = { _, _ -> },
    onPlaceProvider: (suspend (ProviderPlacement) -> Result<Unit>)? = null,
    onPlaceProviderImmediately: ((ProviderPlacement) -> Unit)? = null,
    onPlaceProviderTracked: ((ProviderPlacement, (Result<Unit>) -> Unit) -> Unit)? = null,
    onDeleteProvider: (String) -> Unit,
    onReorderProviders: (List<String>) -> Unit,
    onReorderRootItems: (List<ProviderRootOrderItem>) -> Unit = {},
    onReorderRootItemsTracked: ((List<ProviderRootOrderItem>, (Result<Unit>) -> Unit) -> Unit)? = null,
    onBack: () -> Unit
) = with(Launcher) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val scope = rememberCoroutineScope()
    val dialogs = rememberProviderListDialogs()
    var orderedProviders by remember { mutableStateOf(providers) }
    var rootItems by remember { mutableStateOf(providerRootItems(providers, providerGroups)) }
    var pendingProviderOrder by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var pendingRootOrder by remember { mutableStateOf<List<ProviderRootOrderItem>?>(null) }
    var pendingGridLayout by remember { mutableStateOf<ProviderDragLayout?>(null) }
    var collapsedSections by remember { mutableStateOf(collapsedSectionsState) }
    var pendingCollapsedSections by remember { mutableStateOf<Set<String>?>(null) }
    // Preserve the opened folder when navigation temporarily disposes this screen for provider editing.
    var openGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var skipNextFolderEnterAnimation by rememberSaveable { mutableStateOf(false) }
    // Folder selection controls input; rendered pages live independently through their exit animation.
    val renderedFolderGroups = remember { mutableStateMapOf<String, ProviderGroup>() }
    val folderOriginBounds = remember { mutableStateMapOf<String, Rect>() }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchFields by remember { mutableStateOf(setOf(ProviderSearchField.PROVIDER_NAME)) }
    var searchFilterOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val query = searchQuery.trim()
    fun matches(provider: ModelProvider) = matchesProviderSearch(provider, query, searchFields)
    // Search is a flat provider list, including members of matching folders.
    val matchingGroupIds = providerGroups.filter {
        matchesFolderSearch(it.name, query, searchFields)
    }.mapTo(hashSetOf()) { it.id }
    val visibleRootItems = if (query.isNotBlank()) {
        orderedProviders.filter { matches(it) || it.groupId in matchingGroupIds }
            .map { ProviderRootItem.ProviderItem(it) }
    } else rootItems
    fun closeSearch() {
        searching = false
        searchFilterOpen = false
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    BackHandler(enabled = LocalScreenTransitionActive.current && searching) { closeSearch() }
    var draggedProviderId by remember { mutableStateOf<String?>(null) }
    var draggedGroupId by remember { mutableStateOf<String?>(null) }
    var dragRootSnapshot by remember { mutableStateOf<List<ProviderRootItem>?>(null) }
    var dragProviderSnapshot by remember { mutableStateOf<List<ModelProvider>?>(null) }
    var dragCollapsedSnapshot by remember { mutableStateOf<Set<String>?>(null) }
    var liveOrderChanged by remember { mutableStateOf(false) }
    // One stable gesture host owns the root and every opened folder.
    val dndState = Launcher.rememberState<ProviderRootOrderItem>()
    // Track the actual visible folder card, including root grid scrolling and grid/list changes.
    val folderCardBounds = remember { mutableStateMapOf<String, Rect>() }
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    var containerWindowBounds by remember { mutableStateOf(Rect.Zero) }
    var rootGridWindowBounds by remember { mutableStateOf(Rect.Zero) }
    val folderEscapeEdgePx = with(LocalDensity.current) { 5.dp.toPx() }
    val rootScrollEdgePx = with(LocalDensity.current) { 48.dp.toPx() }

    val sortedGroups = remember(providerGroups) {
        providerGroups.sortedWith(compareBy<ProviderGroup> { it.sortOrder }.thenBy { it.name.lowercase() })
    }
    val validGroupIds = remember(providerGroups) { providerGroups.mapTo(hashSetOf()) { it.id } }
    val openGroup = sortedGroups.firstOrNull { it.id == openGroupId }
    LaunchedEffect(openGroup?.id) {
        if (openGroup != null) renderedFolderGroups[openGroup.id] = openGroup
    }
    // Barely-there blur: items behind the translucent folder stay recognisable and their text
    // stays faintly legible (API 31+; a no-op below).
    val backdropBlur by animateDpAsState(
        targetValue = if (openGroup != null) 1.5.dp else 0.dp,
        animationSpec = tween(
            durationMillis = if (openGroup != null) FolderOpenDurationMillis else FolderCloseDurationMillis
        ),
        label = "providerFolderBackdropBlur"
    )
    // Render exits first; the active folder is always the uppermost interactive page.
    val folderOverlayGroups = renderedFolderGroups.values.filter { it.id != openGroupId } +
        listOfNotNull(openGroup)
    BackHandler(enabled = LocalScreenTransitionActive.current && openGroup != null) {
        if (openGroupId != null) openGroupId = null
    }
    // The skip flag is one-shot: the overlay reads it when first composed, then it is cleared so later opens animate.
    LaunchedEffect(openGroup?.id) {
        if (openGroup != null) skipNextFolderEnterAnimation = false
    }

    fun createProvider(groupId: String? = null) {
        onOpenProviderEditor(
            ModelProvider(
                id = newProviderId(),
                name = "",
                type = ModelApiType.OPENAI,
                baseUrl = "",
                auth = ModelApiType.OPENAI.defaultAuthorization(),
                groupId = groupId
            ),
            true
        )
    }

    fun setCollapsedSections(value: Set<String>) {
        collapsedSections = value
        pendingCollapsedSections = value
        onCollapsedSectionsChange(value)
    }

    fun beginDrag(key: String) {
        dragRootSnapshot = rootItems
        dragProviderSnapshot = orderedProviders
        dragCollapsedSnapshot = collapsedSections
        liveOrderChanged = false
        if (key.startsWith("provider_")) draggedProviderId = key.removePrefix("provider_")
        else draggedGroupId = key.removePrefix("group_")
    }

    fun currentLayout() = ProviderDragLayout(orderedProviders, rootItems.map { it.toOrderItem() })

    fun applyLayout(next: ProviderDragLayout) {
        orderedProviders = next.providers
        val groups = rootItems.filterIsInstance<ProviderRootItem.GroupItem>().associateBy { it.id }
        val byId = next.providers.associateBy { it.id }
        rootItems = next.root.mapNotNull { item ->
            if (item.isGroup) groups[item.id] else byId[item.id]?.let { ProviderRootItem.ProviderItem(it) }
        }
        liveOrderChanged = true
    }

    fun applyProviderPreview(id: String, target: ProviderRootOrderItem?) {
        val layout = currentLayout()
        val next = moveProviderDrag(layout, id, target)
        if (next != layout) applyLayout(next)
    }

    fun moveRootItem(fromKey: String, toKey: String) {
        val from = rootItems.indexOfFirst { it.cellKey() == fromKey }
        val to = rootItems.indexOfFirst { it.cellKey() == toKey }
        if (from < 0 || to < 0 || from == to) return
        rootItems = rootItems.moved(from, to)
        liveOrderChanged = true
    }

    fun commitProviderMove(id: String) {
        val layout = ProviderDragLayout(orderedProviders, rootItems.map { it.toOrderItem() })
        val original = dragProviderSnapshot?.firstOrNull { it.id == id }
        val moved = orderedProviders.firstOrNull { it.id == id }
        if (original != null && moved != null && liveOrderChanged) {
            val groupOrders = rootItems.filterIsInstance<ProviderRootItem.GroupItem>().associate { item ->
                item.id to orderedProviders.filter { it.groupId == item.id }.map { it.id }
            }
            pendingGridLayout = layout
            val placement = ProviderPlacement(id, moved.groupId, layout.root, groupOrders)
            val previousProviders = dragProviderSnapshot.orEmpty()
            val previousRoot = dragRootSnapshot.orEmpty()
            val previousCollapsed = dragCollapsedSnapshot.orEmpty()
            if (onPlaceProviderTracked != null) {
                // Enqueue synchronously, then acknowledge on Main only after the background write.
                onPlaceProviderTracked(placement) { result ->
                    if (result.isFailure && pendingGridLayout == layout) {
                        pendingGridLayout = null
                        orderedProviders = previousProviders
                        rootItems = previousRoot
                        setCollapsedSections(previousCollapsed)
                    }
                }
            } else if (onPlaceProviderImmediately != null) {
                onPlaceProviderImmediately(placement)
            } else if (onPlaceProvider != null) scope.launch {
                if (onPlaceProvider(placement).isFailure) {
                    if (pendingGridLayout == layout) {
                        pendingGridLayout = null
                        orderedProviders = previousProviders
                        rootItems = previousRoot
                        setCollapsedSections(previousCollapsed)
                    }
                    Toast.makeText(context, localizedResources.getString(R.string.provider_drag_save_failed), Toast.LENGTH_SHORT).show()
                }
            } else {
                if (original.groupId != moved.groupId) onMoveProviderToGroup(id, moved.groupId)
                groupOrders.filter { (group, ids) -> previousProviders.filter { it.groupId == group }.map { it.id } != ids }
                    .values.forEach(onReorderProviders)
                if (previousRoot.map { it.toOrderItem() } != layout.root) onReorderRootItems(layout.root)
            }
        }
        draggedProviderId = null
        draggedGroupId = null
        dragRootSnapshot = null
        dragProviderSnapshot = null
        liveOrderChanged = false
    }

    fun finishDrag(key: String, cancelled: Boolean) {
        if (cancelled) {
            dragRootSnapshot?.let { rootItems = it }
            dragProviderSnapshot?.let { orderedProviders = it }
            dragCollapsedSnapshot?.let { collapsedSections = it }
        } else {
            if (key.startsWith("provider_")) {
                commitProviderMove(key.removePrefix("provider_"))
            }
            else if (liveOrderChanged) {
                val order = rootItems.map { it.toOrderItem() }
                val before = dragRootSnapshot.orEmpty()
                pendingRootOrder = order
                if (onReorderRootItemsTracked != null) {
                    onReorderRootItemsTracked(order) { result ->
                        if (result.isFailure && pendingRootOrder == order) {
                            pendingRootOrder = null
                            rootItems = before
                        }
                    }
                } else onReorderRootItems(order)
            }
            if (collapsedSections != dragCollapsedSnapshot) setCollapsedSections(collapsedSections)
        }
        draggedProviderId = null
        draggedGroupId = null
        dragRootSnapshot = null
        dragProviderSnapshot = null
        dragCollapsedSnapshot = null
        liveOrderChanged = false
    }

    fun exitOpenFolder(providerId: String) {
        val folderId = openGroupId ?: return
        val layout = currentLayout()
        val next = ejectProviderFromFolder(layout, providerId, folderId)
        if (next != layout) applyLayout(next)
        // The stable Launcher host keeps tracking the pointer as the folder page leaves.
        openGroupId = null
        // The hover is only a preview. An actual drop target must confirm the release.
    }

    fun finishDnd(key: String, outcome: Launcher.Outcome) {
        finishDrag(key, cancelled = !outcome.accepted)
    }

    BackHandler(enabled = LocalScreenTransitionActive.current && dndState.session != null) {
        dndState.cancel()
    }

    LaunchedEffect(providers, providerGroups, draggedProviderId, draggedGroupId) {
        if (draggedProviderId != null || draggedGroupId != null) return@LaunchedEffect

        val incomingProvidersById = providers.associateBy { it.id }
        pendingGridLayout?.let { preview ->
            val incomingRoot = providerRootItems(providers, providerGroups).map { it.toOrderItem() }
            val sameCatalog = preview.providers.map { it.id }.toSet() == providers.map { it.id }.toSet() &&
                preview.root.filter { it.isGroup }.map { it.id }.toSet() == providerGroups.map { it.id }.toSet()
            val acknowledged = incomingRoot == preview.root && preview.providers.all { p ->
                incomingProvidersById[p.id]?.groupId == p.groupId
            } && providerGroups.all { g ->
                providers.filter { it.groupId == g.id }.map { it.id } == preview.providers.filter { it.groupId == g.id }.map { it.id }
            }
            if (sameCatalog && !acknowledged) return@LaunchedEffect
            pendingGridLayout = null
        }
        val pendingProvider = pendingProviderOrder
        if (pendingProvider == null) {
            orderedProviders = providers
        } else {
            val (groupId, expectedOrder) = pendingProvider
            val actualOrder = providers.filter { it.groupId == groupId }.map { it.id }
            // A folder move/add/delete changes membership, so an old drag order
            // can no longer be acknowledged and must not keep the old positions.
            if (actualOrder == expectedOrder || actualOrder.toSet() != expectedOrder.toSet()) {
                pendingProviderOrder = null
                orderedProviders = providers
            } else {
                val currentIds = orderedProviders.mapTo(hashSetOf()) { it.id }
                orderedProviders =
                    orderedProviders.mapNotNull { incomingProvidersById[it.id] } +
                        providers.filter { it.id !in currentIds }
            }
        }

        val incomingRootItems = providerRootItems(providers, providerGroups)
        val pendingRoot = pendingRootOrder
        if (pendingRoot == null) {
            rootItems = incomingRootItems
        } else if (incomingRootItems.map { it.toOrderItem() } == pendingRoot ||
            incomingRootItems.map { it.toOrderItem() }.toSet() != pendingRoot.toSet()) {
            pendingRootOrder = null
            rootItems = incomingRootItems
        } else {
            val incomingByKey = incomingRootItems.associateBy { it.toOrderItem() }
            val currentKeys = rootItems.mapTo(hashSetOf()) { it.toOrderItem() }
            rootItems =
                rootItems.mapNotNull { incomingByKey[it.toOrderItem()] } +
                    incomingRootItems.filter { it.toOrderItem() !in currentKeys }
        }
    }

    LaunchedEffect(collapsedSectionsState) {
        val pending = pendingCollapsedSections
        when {
            pending == null -> collapsedSections = collapsedSectionsState
            collapsedSectionsState == pending -> pendingCollapsedSections = null
        }
    }
    LaunchedEffect(validGroupIds) {
        if (validGroupIds.isEmpty()) return@LaunchedEffect // groups not loaded yet; don't wipe the saved collapsed state
        val cleaned = collapsedSections.filterTo(linkedSetOf()) { it in validGroupIds }
        if (cleaned != collapsedSections) setCollapsedSections(cleaned)
    }

    Launcher.Host(
        state = dndState,
        modifier = Modifier.fillMaxSize()
            .onGloballyPositioned {
                containerOrigin = it.positionInRoot()
                containerWindowBounds = it.boundsInWindow()
            },
        preview = { drag ->
            val data = drag.item.data
            val item = if (data.isGroup) sortedGroups.firstOrNull { it.id == data.id }
                ?.let { ProviderRootItem.GroupItem(it) }
                else orderedProviders.firstOrNull { it.id == data.id }
                    ?.let { ProviderRootItem.ProviderItem(it) }
            if (item != null) {
                Box(Modifier.fillMaxWidth().height(if (isGridView) 152.dp else 88.dp)
                    .testTag(if (data.isGroup) "dragged_group" else "dragged_provider")) {
                    ProviderRootCard(
                        item = item,
                        members = (item as? ProviderRootItem.GroupItem)
                            ?.let { group -> orderedProviders.filter { it.groupId == group.id } }.orEmpty(),
                        isGrid = isGridView, isDropTarget = false, testTag = false,
                        onEditProvider = {}, onMoveProvider = {}, onDeleteProvider = {},
                        onOpenGroup = {}
                    )
                }
            }
        }
    ) {
        openGroupId?.let { groupId ->
            Launcher.FolderExit(
                state = dndState,
                folderTargetKey = "folder-grid:$groupId",
                open = openGroup != null,
                fromFolder = { drag ->
                    !drag.data.isGroup && dragProviderSnapshot
                        ?.firstOrNull { it.id == drag.data.id }?.groupId == groupId
                },
                onExit = {
                    dndState.session?.item?.data?.takeUnless { it.isGroup }
                        ?.let { exitOpenFolder(it.id) }
                },
                delayMillis = 180L,
                extraExitRegion = { drag ->
                    val bounds = containerWindowBounds
                    bounds.width > 0f &&
                        (drag.pointer.x <= bounds.left + folderEscapeEdgePx ||
                         drag.pointer.x >= bounds.right - folderEscapeEdgePx)
                }
            )
        }
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            // Root controls are covered by the folder page, and must also be hidden from accessibility.
            modifier = (if (openGroup != null) Modifier.clearAndSetSemantics {} else Modifier)
                .then(if (backdropBlur > 0.dp) Modifier.blur(backdropBlur) else Modifier),
            topBar = {
                // This target remains composed while the full-screen folder is visible.
                // Crossing it closes the folder without ending the active DND session.
                Box(Modifier.dropTarget(
                    state = dndState, key = "root-header", priority = -10,
                    enabled = !searching && query.isBlank(),
                    onDragEnter = { drag ->
                        if (openGroupId != null && !drag.item.data.isGroup)
                            exitOpenFolder(drag.item.data.id)
                    }, onDrop = { true }
                )) {
                ProviderScreenTopBar(
                    isGridView = isGridView,
                    searching = searching,
                    searchQuery = searchQuery,
                    searchFields = searchFields,
                    searchFilterOpen = searchFilterOpen,
                    searchFocus = searchFocus,
                    onSearchQueryChange = { searchQuery = it },
                    onSearchFieldsChange = { searchFields = it },
                    onSearchFilterOpenChange = { searchFilterOpen = it },
                    onBack = onBack,
                    onToggleGridView = onToggleGridView,
                    onAddProvider = { createProvider() },
                    onCreateGroup = { dialogs.creatingGroup = true },
                    onOpenSearch = { openGroupId = null; searching = true },
                    onCloseSearch = ::closeSearch
                )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                if (visibleRootItems.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(padding)) { ProviderEmptyState(hasSearchQuery = query.isNotEmpty(), backdropBlur = 0.dp) }
                } else {
                    FadeGridListContent(
                        isGrid = isGridView,
                        modifier = Modifier.fillMaxSize()
                    ) { gridMode ->
                        val gridState = rememberLazyGridState()
                        val cellHeight = if (gridMode) 164.dp else 100.dp
                        val activeDrag = dndState.session
                        // Near the top, the first card lives below the Scaffold header,
                        // outside the narrow auto-scroll edge. Let it yield once the
                        // viewport reaches its start, even without another finger move.
                        LaunchedEffect(
                            gridState, activeDrag?.identity, activeDrag?.pointer,
                            gridState.canScrollBackward, rootGridWindowBounds, gridMode
                        ) {
                            val drag = activeDrag ?: return@LaunchedEffect
                            val pointer = drag.pointer
                            val bounds = rootGridWindowBounds
                            if (gridMode != isGridView || gridState.canScrollBackward ||
                                !bounds.contains(pointer) ||
                                pointer.y > bounds.top + rootScrollEdgePx ||
                                pointer.y >= drag.startPointer.y
                            ) return@LaunchedEffect
                            val key = if (drag.item.data.isGroup) "group_${drag.item.data.id}"
                                else "provider_${drag.item.data.id}"
                            if (rootItems.any { it.cellKey() == key }) {
                                rootItems.firstOrNull()?.cellKey()
                                    ?.takeIf { it != key }?.let { firstKey ->
                                        // This top-edge fallback bypasses Launcher target-enter
                                        // callbacks. Preserve the viewport by index here too:
                                        // LazyGrid otherwise follows the old first key down
                                        // when the dragged item is inserted at index zero.
                                        gridState.requestScrollToItem(
                                            gridState.firstVisibleItemIndex,
                                            gridState.firstVisibleItemScrollOffset
                                        )
                                        moveRootItem(key, firstKey)
                                    }
                            }
                        }
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(if (gridMode) 2 else 1),
                            contentPadding = scrollableScaffoldPadding(padding, horizontal = 16.dp, top = 8.dp, bottom = 8.dp),
                            modifier = Modifier.fillMaxSize()
                                .dropTarget(
                                    state = dndState, key = "root-end", priority = -10,
                                    enabled = !searching && query.isBlank() && openGroupId == null,
                                    onDrop = { drag ->
                                        val dragged = drag.item.data
                                        val key = if (dragged.isGroup) "group_${dragged.id}"
                                            else "provider_${dragged.id}"
                                        if (rootItems.any { it.cellKey() == key }) {
                                            // The background spans the entire viewport. A
                                            // drop in the top header/gutter must not be
                                            // mistaken for an append-to-end gesture.
                                            val lastVisibleBottom = gridState.layoutInfo.visibleItemsInfo
                                                .maxOfOrNull { it.offset.y + it.size.height }
                                            val isAfterLastRow = !gridState.canScrollForward &&
                                                rootGridWindowBounds.contains(drag.pointer) &&
                                                lastVisibleBottom != null &&
                                                drag.pointer.y > rootGridWindowBounds.top + lastVisibleBottom
                                            if (isAfterLastRow) {
                                                rootItems.lastOrNull()?.cellKey()
                                                    ?.takeIf { it != key }?.let { moveRootItem(key, it) }
                                            }
                                            liveOrderChanged
                                        } else if (!dragged.isGroup) {
                                            applyProviderPreview(dragged.id, null)
                                            true
                                        } else false
                                    }
                                )
                                .autoScroll(dndState, gridState, enabled = openGroupId == null)
                                .onGloballyPositioned {
                                    if (gridMode == isGridView) rootGridWindowBounds = it.boundsInWindow()
                                }
                                .testTag("provider_list")
                        ) {
                            items(visibleRootItems, key = { it.cellKey() }) { item ->
                                val key = item.cellKey()
                                Box(
                                    Modifier
                                        .animateItem()
                                        .fillMaxWidth()
                                        .height(cellHeight)
                                        .padding(6.dp)
                                        .dragSource(
                                            state = dndState, key = key, data = item.toOrderItem(),
                                            enabled = !searching && query.isBlank() && openGroupId == null,
                                            onDragStart = { beginDrag(key) },
                                            onDragEnd = { outcome -> finishDnd(key, outcome) }
                                        )
                                        .itemDropTarget(
                                            state = dndState, key = key, priority = 11,
                                            enabled = !searching && query.isBlank() && openGroupId == null,
                                            policy = Launcher.DropPolicy(
                                                reorderHitTest = Launcher.Collision.Pointer,
                                                joinHitTest = if (item is ProviderRootItem.GroupItem)
                                                    Launcher.Collision.Pointer else null
                                            ),
                                            // Folder-to-folder and folder-to-provider both reorder.
                                            // Provider-to-folder remains a join, never a reorder.
                                            acceptsReorder = { drag ->
                                                drag.key != key &&
                                                    (item is ProviderRootItem.ProviderItem || drag.data.isGroup)
                                            },
                                            acceptsJoin = { dragged -> !dragged.data.isGroup },
                                            joinKey = "folder-drop_${item.id}",
                                            joinLandingBounds = { folderCardBounds[item.id] },
                                            onJoinDrop = if (item is ProviderRootItem.GroupItem) ({ drag ->
                                                val layout = currentLayout()
                                                val next = moveProviderDrag(layout, drag.item.data.id, item.toOrderItem())
                                                if (next != layout) applyLayout(next)
                                                true
                                            }) else null,
                                            onReorderEnter = { drag ->
                                                val dragged = drag.item.data
                                                val from = if (dragged.isGroup) "group_${dragged.id}"
                                                    else "provider_${dragged.id}"
                                                if (rootItems.any { it.cellKey() == from })
                                                    moveRootItem(from, key)
                                                else if (!dragged.isGroup)
                                                    applyProviderPreview(dragged.id, item.toOrderItem())
                                            },
                                            onReorderDrop = { true }
                                        )
                                        // The source's own slot must win over the grid-wide
                                        // fallback, otherwise a long-press near the first
                                        // visible row reorders it before any real movement.
                                        .dropTarget(
                                            state = dndState, key = "source-slot:$key",
                                            priority = 12,
                                            enabled = !searching && query.isBlank() && openGroupId == null,
                                            accepts = { it.key == key },
                                            onDrop = {
                                                dragRootSnapshot != rootItems || liveOrderChanged
                                            }
                                        )
                                        .onGloballyPositioned { coordinates ->
                                            if (item is ProviderRootItem.GroupItem && gridMode == isGridView) {
                                                folderCardBounds[item.id] = coordinates.boundsInRoot()
                                            }
                                        }
                                ) {
                                    ProviderRootCard(
                                        item = item,
                                        members = (item as? ProviderRootItem.GroupItem)
                                            ?.let { g -> orderedProviders.filter { it.groupId == g.id } }.orEmpty(),
                                        isGrid = gridMode,
                                        isDropTarget = item is ProviderRootItem.GroupItem &&
                                            (dndState.hoveredTargetKey == key ||
                                                dndState.hoveredTargetKey == "folder-drop_${item.id}") &&
                                            dndState.session?.item?.data?.isGroup == false,
                                        onEditProvider = { onOpenProviderEditor(it, false) },
                                        onMoveProvider = { dialogs.movingProvider = it },
                                        onDeleteProvider = { dialogs.deletingProviderId = it },
                                        onOpenGroup = { id ->
                                            folderCardBounds[id]?.let { folderOriginBounds[id] = it }
                                            sortedGroups.firstOrNull { it.id == id }?.let {
                                                renderedFolderGroups[id] = it
                                                openGroupId = id
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Folder draws over the entire Scaffold, including its app bar, with a separate header.
        folderOverlayGroups.forEach { group ->
            val members = orderedProviders.filter { it.groupId == group.id }
            val folderMatches = query.isEmpty() || matchesFolderSearch(group.name, query, searchFields)
            key(group.id) {
                ProviderFolderOverlay(
                    group = group,
                    providers = if (folderMatches) members else members.filter(::matches),
                    isGrid = isGridView,
                    originBounds = folderOriginBounds[group.id] ?: folderCardBounds[group.id],
                    hostOrigin = containerOrigin,
                    animateExitImmediately = dndState.session != null,
                    dndState = dndState,
                    onBeginDrag = ::beginDrag,
                    onEndDrag = ::finishDnd,
                    onReorderMember = { draggedId, targetId ->
                        val layout = currentLayout()
                        if (orderedProviders.firstOrNull { it.id == draggedId }?.groupId == group.id) {
                            val next = moveProviderDrag(layout, draggedId, ProviderRootOrderItem(targetId, false))
                            if (next != layout) applyLayout(next)
                        }
                    },
                    onDropMember = { liveOrderChanged },
                    showExitHint = dndState.session?.item?.data?.let { !it.isGroup } == true && openGroupId == group.id,
                    visible = openGroupId == group.id && openGroup != null,
                    animateEnter = !skipNextFolderEnterAnimation,
                    onExitFinished = {
                        if (openGroupId != group.id) {
                            renderedFolderGroups.remove(group.id)
                            folderOriginBounds.remove(group.id)
                        }
                    },
                    onDismiss = { openGroupId = null },
                    onAddProvider = {
                        // Returning from the editor keeps this folder open, so don't replay the open animation.
                        skipNextFolderEnterAnimation = true
                        createProvider(group.id)
                    },
                    onToggleGridView = { onToggleGridView?.invoke(!isGridView) },
                    onEditGroup = { dialogs.groupToRename = group },
                    onDeleteGroup = { dialogs.deletingGroup = group; openGroupId = null },
                    onEditProvider = {
                        skipNextFolderEnterAnimation = true
                        onOpenProviderEditor(it, false)
                    },
                    onMoveProvider = { dialogs.movingProvider = it },
                    onDeleteProvider = { dialogs.deletingProviderId = it }
                )
            }
        }


    }

        ProviderDialogHost(
            dialogs = dialogs,
            sortedGroups = sortedGroups,
            nextRootSortOrder = { (rootItems.maxOfOrNull { it.sortOrder } ?: -1) + 1 },
            onSaveGroup = onSaveGroup,
            onSaveGroupIcon = onSaveGroupIcon,
            onGroupDeleteConfirmed = { group ->
                onDeleteGroup(group.id)
                setCollapsedSections(collapsedSections - group.id)
            },
            onMoveProviderToGroup = onMoveProviderToGroup,
            onProviderDeleteConfirmed = onDeleteProvider
        )
}
