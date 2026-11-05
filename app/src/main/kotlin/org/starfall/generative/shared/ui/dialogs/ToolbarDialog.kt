package org.starfall.generative.shared.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R

/**
 * Dialog with a pinned action toolbar above independently scrollable content.
 * Owns the title, toolbar spacing, and default Close footer so callers only
 * provide their status/search control, actions, and flexible content.
 */
@Composable
fun ToolbarDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    toolbarLeading: @Composable RowScope.() -> Unit,
    toolbarActions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    toolbarModifier: Modifier = Modifier,
    buttons: (@Composable () -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        title = title,
        modifier = modifier,
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).then(toolbarModifier),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        toolbarLeading()
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        content = toolbarActions
                    )
                }
                content()
            }
        },
        buttons = buttons ?: {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.common_close)) }
        }
    )
}
