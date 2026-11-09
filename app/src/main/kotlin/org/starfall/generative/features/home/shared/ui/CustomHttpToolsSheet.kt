package org.starfall.generative.features.home.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.lists.ItemCard

@Composable
internal fun CustomHttpToolsSheetContent(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val controls = LocalToolControls.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QuickActionsSubpageHeader(title = stringResource(R.string.custom_http_tools), onBack = onBack, actions = {
            QuickActionsSettingsButton(stringResource(R.string.custom_http_tools), onOpenSettings)
        })
        if (controls.customHttpTools.isEmpty()) Text(stringResource(R.string.http_tools_empty))
        controls.customHttpTools.forEach { tool ->
            ItemCard(isGrid = false, onClick = { controls.setCustomHttpTool(tool.id, !tool.enabled) },
                icon = { SharedIcon(Icons.Outlined.Build, null) },
                content = { Column {
                    Text(tool.name, style = MaterialTheme.typography.titleSmall)
                    Text(tool.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                } }, actions = {
                    Switch(checked = tool.enabled, onCheckedChange = { controls.setCustomHttpTool(tool.id, it) })
                })
        }
    }
}
