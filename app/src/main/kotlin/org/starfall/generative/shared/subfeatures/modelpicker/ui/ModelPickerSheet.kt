package org.starfall.generative.shared.subfeatures.modelpicker.ui

import org.starfall.generative.shared.subfeatures.modelpicker.logic.ModelPickerItem
import org.starfall.generative.shared.subfeatures.modelpicker.logic.sectionKey
import org.starfall.generative.shared.subfeatures.modelpicker.logic.computeModelPickerItems
import org.starfall.generative.shared.subfeatures.modelpicker.logic.computeModelPickerTabs
import org.starfall.generative.shared.subfeatures.modelpicker.logic.initialModelPickerItemIndex

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.sheets.BottomSheet

private const val FullHeightModelPickerMinRows = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<ModelProvider>,
    providerGroups: List<ProviderGroup> = emptyList(),
    collapsedGroupIdsState: Set<String> = emptySet(),
    collapsedProviderIdsState: Set<String> = emptySet(),
    onCollapsedGroupIdsChange: (Set<String>) -> Unit = {},
    onCollapsedProviderIdsChange: (Set<String>) -> Unit = {},
    selectedProviderId: String,
    selectedModelId: String,
    conversationReasoningEffort: String?,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSetReasoningEffort: (String?) -> Unit,
    dynamicModelsMap: Map<String, List<String>> = emptyMap(),
    modelFilter: (ModelProvider, String, ModelConfiguration) -> Boolean = { _, _, _ -> true },
    showReasoningEffort: Boolean = true,
    onEditProvider: (providerId: String) -> Unit = {},
    onEditModel: ((providerId: String, modelId: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var contentOverflows by remember { mutableStateOf(false) }
    BottomSheet(onDismissRequest = onDismiss, contentBehindNavigationBar = true,
        expandOnOpen = contentOverflows) {
        ModelPickerSheetContent(
            onOverflowChanged = { contentOverflows = it },
            providers = providers,
            providerGroups = providerGroups,
            collapsedGroupIdsState = collapsedGroupIdsState,
            collapsedProviderIdsState = collapsedProviderIdsState,
            onCollapsedGroupIdsChange = onCollapsedGroupIdsChange,
            onCollapsedProviderIdsChange = onCollapsedProviderIdsChange,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            conversationReasoningEffort = conversationReasoningEffort,
            onSelectModel = onSelectModel,
            onSetReasoningEffort = onSetReasoningEffort,
            dynamicModelsMap = dynamicModelsMap,
            modelFilter = modelFilter,
            showReasoningEffort = showReasoningEffort,
            onEditProvider = onEditProvider,
            onEditModel = onEditModel,
            onDismiss = onDismiss
        )
    }
}

/**
 * The picker body without its own sheet, so it can live as a page inside a shared sheet.
 * [onOpenSelectedModel] adds a button next to the search field that switches to the selected-model page.
 */
@Composable
internal fun ModelPickerSheetContent(
    providers: List<ModelProvider>,
    providerGroups: List<ProviderGroup> = emptyList(),
    collapsedGroupIdsState: Set<String> = emptySet(),
    collapsedProviderIdsState: Set<String> = emptySet(),
    onCollapsedGroupIdsChange: (Set<String>) -> Unit = {},
    onCollapsedProviderIdsChange: (Set<String>) -> Unit = {},
    selectedProviderId: String,
    selectedModelId: String,
    conversationReasoningEffort: String?,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSetReasoningEffort: (String?) -> Unit,
    dynamicModelsMap: Map<String, List<String>> = emptyMap(),
    modelFilter: (ModelProvider, String, ModelConfiguration) -> Boolean = { _, _, _ -> true },
    showReasoningEffort: Boolean = true,
    onEditProvider: (providerId: String) -> Unit = {},
    onEditModel: ((providerId: String, modelId: String) -> Unit)? = null,
    onOpenSelectedModel: (() -> Unit)? = null,
    // Disable when the containing sheet already reserves the navigation-bar inset.
    includeNavigationBarPadding: Boolean = true,
    onOverflowChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Standalone pickers draw behind the navigation bar; shared sheets can own this inset.
    val navigationBottomPadding = if (includeNavigationBarPadding) {
        with(LocalDensity.current) {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).getBottom(this).toDp()
        }
    } else 0.dp
    var searchBounds by remember { mutableStateOf<Rect?>(null) }
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }
    var expandSearchResults by remember(query) { mutableStateOf(true) }
    val forceExpanded = query.isNotBlank() && expandSearchResults
    var collapsedGroupIds by remember { mutableStateOf(collapsedGroupIdsState) }
    var collapsedProviderIds by remember { mutableStateOf(collapsedProviderIdsState) }
    val ungroupedTabLabel = stringResource(R.string.ungrouped)
    var positionBeforeSearch by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(collapsedGroupIdsState) {
        collapsedGroupIds = collapsedGroupIdsState
    }
    LaunchedEffect(collapsedProviderIdsState) {
        collapsedProviderIds = collapsedProviderIdsState
    }
    fun setCollapsedGroups(value: Set<String>) {
        collapsedGroupIds = value
        onCollapsedGroupIdsChange(value)
    }
    fun setCollapsedProviders(value: Set<String>) {
        collapsedProviderIds = value
        onCollapsedProviderIdsChange(value)
    }

    val flatItems = remember(
        providers,
        providerGroups,
        dynamicModelsMap,
        selectedProviderId,
        selectedModelId,
        query,
        expandSearchResults,
        collapsedGroupIds,
        collapsedProviderIds,
        modelFilter
    ) {
        computeModelPickerItems(
            providers = providers,
            providerGroups = providerGroups,
            dynamicModelsMap = dynamicModelsMap,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            query = query,
            expandSearchResults = expandSearchResults,
            collapsedGroupIds = collapsedGroupIds,
            collapsedProviderIds = collapsedProviderIds,
            modelFilter = modelFilter
        ).let { items ->
            // Do not add a redundant "Ungrouped" divider when there are no folders.
            if (providerGroups.isEmpty()) items.filterNot { it is ModelPickerItem.Ungrouped } else items
        }
    }

    // Start at the selected model immediately, before the first list composition/layout.
    val initialItemIndex = remember(flatItems, selectedProviderId, selectedModelId) {
        initialModelPickerItemIndex(flatItems, selectedProviderId, selectedModelId)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialItemIndex)
    val latestOnOverflowChanged by rememberUpdatedState(onOverflowChanged)
    // A model picker with at least five visible model rows is a full-screen browsing task.
    val manyVisibleModels = flatItems.count { it is ModelPickerItem.Model } >= FullHeightModelPickerMinRows
    LaunchedEffect(listState, flatItems, manyVisibleModels) {
        snapshotFlow {
            val layout = listState.layoutInfo
            if (layout.viewportSize.height <= 0 || layout.visibleItemsInfo.isEmpty()) null
            else manyVisibleModels || layout.visibleItemsInfo.size < layout.totalItemsCount
        }.filterNotNull().distinctUntilChanged().collect { latestOnOverflowChanged(it) }
    }

    // Folder tabs are local navigation shortcuts. No tab selection or scroll position is persisted.
    val folderTabs = remember(flatItems, ungroupedTabLabel) { computeModelPickerTabs(flatItems, ungroupedTabLabel) }
    val sectionIndexes = remember(flatItems) {
        flatItems.mapIndexedNotNull { index, item -> item.sectionKey?.let { it to index } }.toMap()
    }
    val visibleSectionKey by remember(listState, sectionIndexes) {
        derivedStateOf {
            sectionIndexes.entries.lastOrNull { it.value <= listState.firstVisibleItemIndex }?.key
        }
    }
    var clickedTabKey by remember { mutableStateOf<String?>(null) }
    val selectedTabIndex = folderTabs.indexOfFirst { it.key == (clickedTabKey ?: visibleSectionKey) }
        .coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    // After tapping a short final section, preserve its highlight until the user scrolls again.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) clickedTabKey = null
        }
    }

    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            if (positionBeforeSearch == null) {
                positionBeforeSearch = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
            listState.scrollToItem(0)
        } else {
            positionBeforeSearch?.let { (index, offset) ->
                listState.scrollToItem(index.coerceAtMost((flatItems.size - 1).coerceAtLeast(0)), offset)
            }
            positionBeforeSearch = null
        }
    }
    val allCollapsed = !forceExpanded && providers.filterNot { it.isHidden }.all { it.id in collapsedProviderIds }
    val selectedModel = selectedModelId.takeIf { it.isNotBlank() }?.let { modelId ->
        providers.firstOrNull { it.id == selectedProviderId }?.config?.modelConfigs?.let { configs ->
            configs[modelId] ?: configs.values.firstOrNull { it.displayName == modelId }
        }
    }

    Column(modifier = modifier.fillMaxWidth()
        // Give large catalogs their full browsing height during the page transition,
        // instead of expanding the outer sheet after the list has been laid out.
        .then(if (manyVisibleModels) Modifier.fillMaxHeight() else Modifier)
        // Match Fetch Models: follow the IME without imposing a fixed sheet height.
        .imePadding()
        .onGloballyPositioned { contentOrigin = it.positionInRoot() }
        // Observe taps before children consume them without interrupting scrolling or clicks.
        .pointerInput(searchBounds, contentOrigin, focusManager) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    if (change.changedToUpIgnoreConsumed()) {
                        if (searchBounds != null && searchBounds?.contains(contentOrigin + down.position) != true) {
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                        }
                        break
                    }
                }
            }
        }
    ) {
            // Search and section controls are the header, above the model list.
            ModelPickerToolbar(
                query = query,
                onQueryChange = { query = it },
                toggleEnabled = providers.isNotEmpty() || providerGroups.isNotEmpty(),
                allCollapsed = allCollapsed,
                onToggleAllCollapsed = {
                    expandSearchResults = false
                    if (allCollapsed) {
                        setCollapsedGroups(emptySet())
                        setCollapsedProviders(emptySet())
                    } else {
                        setCollapsedProviders(providers.map { it.id }.toSet())
                    }
                },
                selectedModelId = selectedModelId,
                selectedModel = selectedModel,
                onOpenSelectedModel = onOpenSelectedModel,
                onSearchBoundsChange = { searchBounds = it }
            )
            if (folderTabs.isNotEmpty()) {
                ModelPickerTabRow(
                    tabs = folderTabs,
                    selectedIndex = selectedTabIndex,
                    onSelectTab = { tab ->
                        sectionIndexes[tab.key]?.let { index ->
                            scope.launch {
                                // Jump directly even across long lists; do not animate through every model.
                                listState.scrollToItem(index)
                                clickedTabKey = tab.key
                            }
                        }
                    }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .testTag("model-picker-list"),
                contentPadding = PaddingValues(
                    start = 16.dp, top = 6.dp, end = 16.dp,
                    bottom = 12.dp + navigationBottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (flatItems.isEmpty()) {
                    item(key = "empty") {
                        Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.no_matching_models),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        flatItems,
                        key = { _, item ->
                                when (item) {
                                    is ModelPickerItem.Group -> "group_${item.group.id}"
                                    ModelPickerItem.Ungrouped -> "ungrouped"
                                    is ModelPickerItem.Provider -> "provider_${item.provider.id}"
                                    is ModelPickerItem.Model -> "model_${item.provider.id}_${item.modelId}"
                                }
                            }
                        ) { index, item ->
                        Box(
                            Modifier.fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow),
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    ),
                                    fadeOutSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
                                )
                                .testTag("model-picker-frame_$index")
                        ) {
                            when (item) {
                                is ModelPickerItem.Group -> ModelPickerSection(
                                    label = item.group.name,
                                    tag = "model-picker-group_${item.group.id}"
                                )
                                ModelPickerItem.Ungrouped -> ModelPickerSection(
                                    label = ungroupedTabLabel,
                                    tag = "model-picker-group_ungrouped"
                                )

                                is ModelPickerItem.Provider -> {
                                    val collapsed = item.provider.id in collapsedProviderIds &&
                                        !forceExpanded
                                        ModelPickerProviderRow(
                                            provider = item.provider,
                                            collapsed = collapsed,
                                            hasSelectedModel = item.hasSelectedModel,
                                            onEdit = {
                                                onDismiss()
                                                onEditProvider(item.provider.id)
                                            },
                                            onToggle = {
                                            expandSearchResults = false
                                            setCollapsedProviders(
                                                if (collapsed) collapsedProviderIds - item.provider.id
                                                else collapsedProviderIds + item.provider.id
                                            )
                                        }
                                    )
                                }

                                is ModelPickerItem.Model -> {
                                    Box(Modifier.fillMaxWidth().testTag("model-picker-model_${item.provider.id}_${item.modelId}")) {
                                        ModelPickerCard(
                                            modelId = item.modelId,
                                            config = item.config,
                                            isSelected = item.isSelected,
                                            conversationReasoningEffort = conversationReasoningEffort,
                                            onSetReasoningEffort = onSetReasoningEffort,
                                            showReasoningEffort = showReasoningEffort,
                                            onClick = {
                                                onSelectModel(item.provider.id, item.modelId)
                                                onDismiss()
                                            },
                                            onLongClick = onEditModel?.let { edit ->
                                                {
                                                    onDismiss()
                                                    edit(item.provider.id, item.modelId)
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
    }
}
