package org.starfall.generative.shared.ui.layout

import org.starfall.generative.shared.ui.inputs.rememberClearInputFocus
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Material 3 tab row used inside swipeable page layouts. */
@Composable
private fun AppTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    tabTag: (Int) -> String? = { null }
) {
    if (tabs.isEmpty()) return
    val selected = selectedIndex.coerceIn(0, tabs.lastIndex)
    val rowModifier = modifier.then(appTabRowBorder())
    val tabContent: @Composable () -> Unit = {
        tabs.forEachIndexed { index, title ->
            val tag = tabTag(index)
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                modifier = if (tag == null) Modifier else Modifier.testTag(tag),
                text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
    PrimaryTabRow(
        selectedTabIndex = selected,
        containerColor = appTabRowColor(),
        modifier = rowModifier,
        divider = { if (showDivider) androidx.compose.material3.HorizontalDivider() },
        tabs = tabContent
    )
}

/**
 * One tab row and its swipe-enabled content. The caller can retain [pagerState] to control
 * the active page from its app bar, navigation arguments, or an editor flow. Swipe gestures,
 * tab clicks, and selected-tab highlighting all use the same Compose [PagerState].
 */
@Composable
fun AppSwipeTabs(
    tabs: List<String>,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    tabRowModifier: Modifier = Modifier,
    showDivider: Boolean = true,
    spacing: Dp = 0.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    tabTag: (Int) -> String? = { null },
    pageContent: @Composable (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val clearInputFocus = rememberClearInputFocus()
    LaunchedEffect(pagerState, clearInputFocus) {
        snapshotFlow { pagerState.currentPage }.drop(1).collect {
            clearInputFocus()
        }
    }
    Column(modifier) {
        AppTabRow(
            tabs = tabs,
            selectedIndex = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            modifier = tabRowModifier,
            showDivider = showDivider,
            tabTag = tabTag
        )
        if (spacing > 0.dp) Spacer(Modifier.height(spacing))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("app-swipe-pager"),
            verticalAlignment = verticalAlignment,
            pageContent = { page -> pageContent(page) }
        )
    }
}
