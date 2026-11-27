package org.starfall.generative.features.home.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.starfall.generative.R
import org.starfall.generative.integrations.generativeserver.ServerTool
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.lists.FadingLazyColumn
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionActive

@Composable
internal fun ServerToolsSheetContent(
    loadTools: suspend () -> List<ServerTool>?,
    onBack: () -> Unit,
) {
    var tools by remember { mutableStateOf(emptyList<ServerTool>()) }
    var message by remember { mutableIntStateOf(R.string.server_tools_loading) }
    var reload by remember { mutableIntStateOf(0) }
    val pageActive = LocalScreenTransitionActive.current
    LaunchedEffect(reload) {
        message = R.string.server_tools_loading
        try {
            val result = loadTools()
            tools = result.orEmpty()
            message = when {
                result == null -> R.string.server_tools_unsupported
                result.isEmpty() -> R.string.server_tools_empty
                else -> 0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message = R.string.server_tools_load_failed
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        QuickActionsSubpageHeader(stringResource(R.string.server_tools_title), onBack) {
            if (message == R.string.server_tools_load_failed) {
                IconButton(enabled = pageActive, onClick = { reload++ }) {
                    SharedIcon(Icons.Outlined.Refresh, stringResource(R.string.common_retry))
                }
            }
        }
        if (message != 0) {
            Text(stringResource(message), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            FadingLazyColumn(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tools, key = { it.id }) { tool ->
                    ItemCard(
                        isGrid = false,
                        onClick = null,
                        icon = { SharedIcon(Icons.Outlined.Build, contentDescription = null) },
                        actions = {},
                        content = {
                            Column {
                                Text(tool.name, style = MaterialTheme.typography.titleSmall)
                                if (tool.description.isNotBlank()) {
                                    Text(tool.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
