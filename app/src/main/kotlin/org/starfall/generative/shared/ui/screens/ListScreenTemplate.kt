package org.starfall.generative.shared.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.inputs.AppBarSearchField
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.FixedAppBarTitle
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.lists.ListViewToggle

/**
 * A complete collection screen: app bar, search, view selection, loading/empty states and body.
 * The app bar action order is fixed: primary actions, Search, List/Grid, trailing Settings/Menu.
 * A caller supplies the actions for a slot, never their placement in this sequence.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListScreenTemplate(
    title: String,
    onBack: () -> Unit,
    handleBack: Boolean = true,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    searchQuery: String? = null,
    onSearchQueryChange: (String) -> Unit = {},
    searchPlaceholder: String = stringResource(R.string.shared_search),
    isGrid: Boolean? = null,
    onGridChange: (Boolean) -> Unit = {},
    primaryActions: @Composable RowScope.() -> Unit = {},
    trailingActions: @Composable RowScope.() -> Unit = {},
    searchActions: @Composable RowScope.() -> Unit = {},
    searchExpanded: Boolean? = null,
    onSearchExpandedChange: (Boolean) -> Unit = {},
    searchPopupOpen: Boolean = false,
    clearSearchOnClose: Boolean = false,
    searchTag: String = "list-screen-search",
    searchButtonTag: String = "list-screen-search-button",
    searchClearButtonTag: String = "list-screen-search-clear",
    searchCloseButtonTag: String? = null,
    navigationTag: String? = null,
    viewToggleTag: String = "list-screen-view-toggle",
    appBarModifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    selectionTitle: String? = null,
    onExitSelection: () -> Unit = {},
    selectionActions: @Composable RowScope.() -> Unit = {},
    horizontalPadding: Dp = 16.dp,
    topPadding: Dp = 8.dp,
    bottomPadding: Dp = 16.dp,
    isLoading: Boolean = false,
    isEmpty: Boolean = false,
    loadingContent: @Composable BoxScope.() -> Unit = {},
    emptyContent: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.(PaddingValues) -> Unit
) {
    var ownSearchExpanded by remember { mutableStateOf(false) }
    val searching = searchQuery != null && (searchExpanded ?: ownSearchExpanded)
    val searchFocus = remember { FocusRequester() }
    var searchWasFocused by remember { mutableStateOf(false) }
    var imeWasVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible

    fun setSearching(next: Boolean) {
        if (searchExpanded == null) ownSearchExpanded = next
        else onSearchExpandedChange(next)
    }
    fun closeSearch() {
        setSearching(false)
        imeWasVisible = false
        if (clearSearchOnClose && !searchQuery.isNullOrEmpty()) onSearchQueryChange("")
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    BackHandler(enabled = LocalScreenTransitionActive.current && handleBack && selectionTitle == null && !searching, onBack = onBack)
    BackHandler(enabled = LocalScreenTransitionActive.current && handleBack && selectionTitle != null, onBack = onExitSelection)
    BackHandler(enabled = LocalScreenTransitionActive.current && handleBack && searching, onBack = ::closeSearch)
    LaunchedEffect(searching) {
        if (searching) {
            withFrameNanos { }
            searchFocus.requestFocus()
        } else searchWasFocused = false
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeWasVisible = true
        else if (imeWasVisible && searching && !searchPopupOpen) closeSearch()
    }

    Scaffold(
        modifier = modifier.imePadding().testTag("list-screen-template"),
        snackbarHost = snackbarHost,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // Scaffold passes safe edges to content; scroll containers keep their full viewport.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                modifier = appBarModifier,
                colors = edgeToEdgeTopAppBarColors(),
                title = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        if (selectionTitle != null) {
                            FixedAppBarTitle(selectionTitle)
                        } else {
                            AnimatedVisibility(
                                visible = !searching,
                                enter = fadeIn(tween(180)), exit = fadeOut(tween(90))
                            ) {
                                if (subtitle == null) FixedAppBarTitle(title)
                                else Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = searching,
                                enter = expandHorizontally(expandFrom = Alignment.End, animationSpec = tween(220)) + fadeIn(tween(160)),
                                exit = shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = tween(180)) + fadeOut(tween(120))
                            ) {
                                AppBarSearchField(
                                    value = searchQuery.orEmpty(),
                                    onValueChange = onSearchQueryChange,
                                    placeholder = searchPlaceholder,
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (!searchQuery.isNullOrEmpty()) {
                                                IconButton(
                                                    onClick = { onSearchQueryChange("") },
                                                    modifier = Modifier.testTag(searchClearButtonTag)
                                                ) {
                                                    SharedIcon(Icons.Default.Close, stringResource(R.string.common_clear))
                                                }
                                            }
                                            if (searchCloseButtonTag != null) {
                                                IconButton(onClick = ::closeSearch, modifier = Modifier.testTag(searchCloseButtonTag)) {
                                                    SharedIcon(Icons.Default.Close, stringResource(R.string.common_close))
                                                }
                                            }
                                            searchActions()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                                        .onFocusChanged { state ->
                                            if (!state.isFocused && searchWasFocused && searching && !searchPopupOpen) closeSearch()
                                            searchWasFocused = state.isFocused
                                        }.testTag(searchTag)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = if (selectionTitle != null) onExitSelection else onBack,
                        modifier = if (navigationTag == null) Modifier else Modifier.testTag(navigationTag)
                    ) {
                        SharedIcon(
                            if (selectionTitle != null) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(if (selectionTitle != null) R.string.common_close else R.string.common_back)
                        )
                    }
                },
                actions = {
                    when {
                        selectionTitle != null -> selectionActions()
                        !searching -> {
                            primaryActions()
                            if (searchQuery != null) {
                                IconButton(
                                    onClick = { setSearching(true) },
                                    modifier = Modifier.testTag(searchButtonTag)
                                ) {
                                    SharedIcon(
                                        Icons.Default.Search,
                                        searchPlaceholder,
                                        tint = if (searchQuery.isNotBlank()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (isGrid != null) {
                                ListViewToggle(
                                    isGrid = isGrid,
                                    onToggle = { onGridChange(!isGrid) },
                                    modifier = Modifier.testTag(viewToggleTag)
                                )
                            }
                            trailingActions()
                        }
                    }
                }
            )
        }
    ) { scaffoldPadding ->
        val padding = scrollableScaffoldPadding(
            scaffoldPadding, horizontal = horizontalPadding,
            top = topPadding, bottom = bottomPadding
        )
        Box(Modifier.fillMaxSize().testTag("list-screen-content")) {
            when {
                isLoading -> loadingContent()
                isEmpty -> emptyContent()
                else -> content(padding)
            }
        }
    }
}
