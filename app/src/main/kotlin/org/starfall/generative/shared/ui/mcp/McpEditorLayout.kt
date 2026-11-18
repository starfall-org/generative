package org.starfall.generative.shared.ui.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.layout.AppSwipeTabs
import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate

/** The feature editor; an available tools capability adds the second tab. */
@Composable
internal fun McpEditorLayout(
    title: String, onBack: () -> Unit, canSave: Boolean, onSave: () -> Unit,
    pager: PagerState? = null, tools: (@Composable () -> Unit)? = null,
    configuration: @Composable () -> Unit
) {
    EditorScreenTemplate(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onBack = onBack, actions = {
            Button(onClick = onSave, enabled = canSave, modifier = Modifier.padding(end = 8.dp)) {
                Text(stringResource(R.string.common_save))
            }
        }
    ) { scaffoldPadding ->
        val padding = scrollableScaffoldPadding(scaffoldPadding, horizontal = 16.dp, top = 8.dp, bottom = 16.dp)
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (pager != null && tools != null) {
                AppSwipeTabs(tabs = listOf(stringResource(R.string.mcp_basic_settings), stringResource(R.string.common_tools)),
                    pagerState = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    if (page == 0) configuration() else tools()
                }
            } else configuration()
        }
    }
}
