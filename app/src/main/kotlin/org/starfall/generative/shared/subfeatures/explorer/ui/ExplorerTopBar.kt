package org.starfall.generative.shared.subfeatures.explorer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import org.starfall.generative.shared.ui.lists.ListViewToggle
import org.starfall.generative.shared.ui.text.AppBarSearchField

/**
 * Explorer AppBar: title/breadcrumb, inline search, and the directory actions.
 *
 * The search field lives in the title slot so expanding it never moves the navigation icon, and
 * it keeps the app's inline AppBar search styling instead of introducing an outlined box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExplorerTopBar(
    title: String,
    subtitle: String?,
    searching: Boolean,
    query: String,
    filterActive: Boolean,
    searchFocus: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onBack: () -> Unit,
    onNewFolder: (() -> Unit)?,
    onUpload: (() -> Unit)?,
    isGrid: Boolean,
    onToggleLayout: () -> Unit,
    onRefresh: () -> Unit
) {
    TopAppBar(
        colors = edgeToEdgeTopAppBarColors(),
        title = {
            AnimatedContent(targetState = searching, label = "explorerSearch") { isSearching ->
                if (isSearching) {
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    AppBarSearchField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = stringResource(R.string.explorer_search),
                        onSearch = onCloseSearch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocus)
                            .onFocusChanged { state -> onSearchFocusChanged(state.isFocused) }
                            .testTag("explorer-search")
                    )
                } else {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("explorer-back")) {
                SharedIcon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.common_back)
                )
            }
        },
        actions = {
            if (searching) {
                IconButton(onClick = onCloseSearch, modifier = Modifier.testTag("explorer-close-search")) {
                    SharedIcon(Icons.Default.Close, stringResource(R.string.explorer_close_search))
                }
            } else {
                IconButton(onClick = onOpenSearch, modifier = Modifier.testTag("explorer-open-search")) {
                    SharedIcon(
                        Icons.Default.Search,
                        stringResource(R.string.explorer_search),
                        tint = if (filterActive) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current
                    )
                }
                if (onNewFolder != null) {
                    IconButton(onClick = onNewFolder, modifier = Modifier.testTag("explorer-new-folder")) {
                        SharedIcon(
                            Icons.Outlined.CreateNewFolder,
                            stringResource(R.string.explorer_new_folder)
                        )
                    }
                }
                if (onUpload != null) {
                    IconButton(onClick = onUpload, modifier = Modifier.testTag("explorer-upload")) {
                        SharedIcon(Icons.Outlined.UploadFile, stringResource(R.string.explorer_upload))
                    }
                }
                ListViewToggle(
                    isGrid = isGrid,
                    onToggle = onToggleLayout,
                    modifier = Modifier.testTag("explorer-toggle-layout")
                )
                IconButton(onClick = onRefresh, modifier = Modifier.testTag("explorer-refresh")) {
                    SharedIcon(Icons.Default.Refresh, stringResource(R.string.refresh))
                }
            }
        }
    )
}
