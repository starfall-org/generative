package org.starfall.generative.features.providers.list.ui

import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import org.starfall.generative.shared.ui.menus.DropdownMenu
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.starfall.generative.R
import org.starfall.generative.shared.ui.layout.FixedAppBarTitle
import org.starfall.generative.features.providers.list.logic.ProviderSearchField
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.lists.ListViewToggle
import org.starfall.generative.shared.ui.menus.DropdownMenuItem

/** App bar for the providers root screen only. Open folders draw their own full-screen header. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ProviderScreenTopBar(
    isGridView: Boolean,
    searching: Boolean,
    searchQuery: String,
    searchFields: Set<ProviderSearchField>,
    searchFilterOpen: Boolean,
    searchFocus: FocusRequester,
    onSearchQueryChange: (String) -> Unit,
    onSearchFieldsChange: (Set<ProviderSearchField>) -> Unit,
    onSearchFilterOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onToggleGridView: ((Boolean) -> Unit)?,
    onAddProvider: () -> Unit,
    onCreateGroup: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit
) {
    var searchWasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(searching) {
        if (searching) {
            withFrameNanos { }
            searchFocus.requestFocus()
        } else searchWasFocused = false
    }
    val imeVisible = WindowInsets.isImeVisible
    var imePreviouslyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imePreviouslyVisible = true
        else if (imePreviouslyVisible) {
            imePreviouslyVisible = false
            if (searching && !searchFilterOpen) onCloseSearch()
        }
    }
    TopAppBar(
        colors = edgeToEdgeTopAppBarColors(),
        title = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                AnimatedVisibility(visible = !searching, enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(90))) {
                    FixedAppBarTitle(stringResource(R.string.app_menu_language_providers))
                }
                AnimatedVisibility(visible = searching,
                    enter = expandHorizontally(expandFrom = Alignment.End, animationSpec = tween(220)) + fadeIn(tween(160)),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = tween(180)) + fadeOut(tween(120))) {
                    TextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        placeholder = { Text(stringResource(R.string.search_providers)) },
                        singleLine = true,
                        trailingIcon = {
                            Row {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") },
                                        modifier = Modifier.testTag("provider_search_clear")) {
                                        SharedIcon(Icons.Default.Close, stringResource(R.string.common_clear))
                                    }
                                }
                                IconButton(onClick = { onSearchFilterOpenChange(true) }) {
                                    SharedIcon(
                                        Icons.Outlined.FilterList,
                                        stringResource(R.string.provider_search_filter),
                                        tint = if (searchFields == setOf(ProviderSearchField.PROVIDER_NAME))
                                            MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                                    )
                                }
                                DropdownMenu(expanded = searchFilterOpen, onDismissRequest = {
                                    onSearchFilterOpenChange(false)
                                    onCloseSearch()
                                }) {
                                    listOf(
                                        ProviderSearchField.PROVIDER_NAME to R.string.provider_search_name,
                                        ProviderSearchField.FOLDER_NAME to R.string.common_folder_name,
                                        ProviderSearchField.MODEL_NAME to R.string.provider_search_model
                                    ).forEach { (field, label) ->
                                        val selected = field in searchFields
                                        DropdownMenuItem(
                                            text = { Text(stringResource(label)) },
                                            leadingIcon = { Checkbox(checked = selected, onCheckedChange = null) },
                                            enabled = !selected || searchFields.size > 1,
                                            onClick = {
                                                onSearchFieldsChange(if (selected) searchFields - field else searchFields + field)
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                            .onFocusChanged { state ->
                                if (!state.isFocused && searchWasFocused && searching && !searchFilterOpen) onCloseSearch()
                                searchWasFocused = state.isFocused
                            }.testTag("provider_search"),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
            }
        },
        actions = {
            if (!searching) {
                IconButton(onClick = onOpenSearch,
                    modifier = Modifier.testTag("provider_search_expand")) {
                    SharedIcon(Icons.Default.Search, stringResource(R.string.search_providers),
                        tint = if (searchQuery.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ListViewToggle(isGridView, onToggle = { onToggleGridView?.invoke(!isGridView) })
                IconButton(onClick = onCreateGroup, modifier = Modifier.testTag("add_provider_group")) {
                    SharedIcon(Icons.Outlined.CreateNewFolder, stringResource(R.string.common_add))
                }
                IconButton(onClick = onAddProvider, modifier = Modifier.testTag("add_provider")) {
                    SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
                }
            }
        }
    )
}
