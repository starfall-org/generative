package org.starfall.generative.features.httptools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon

@Composable
internal fun HttpAdvancedSection(
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    label: String = stringResource(R.string.image_tool_advanced_json), content: @Composable ColumnScope.() -> Unit
) {
    TextButton(onClick = { onExpandedChange(!expanded) }) {
        Text(label)
        SharedIcon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
    }
    if (expanded) Column(content = content)
}
