package org.starfall.generative.shared.ui.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.ui.AlertSeverity

/** Shared modal error presentation with scrollable, selectable and copyable details. */
@Composable
fun ErrorDialog(
    message: String,
    onDismissRequest: () -> Unit,
    title: String = stringResource(R.string.common_error),
    severity: AlertSeverity = AlertSeverity.ERROR,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        content = {
            ErrorContainer(
                message = message,
                expandable = false,
                severity = severity,
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        buttons = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.common_close)) }
        }
    )
}
