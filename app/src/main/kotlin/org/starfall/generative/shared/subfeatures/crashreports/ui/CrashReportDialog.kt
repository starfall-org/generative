package org.starfall.generative.shared.subfeatures.crashreports.ui

import org.starfall.generative.shared.ui.dialogs.Dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import org.starfall.generative.shared.ui.ErrorContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashReport
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon

@Composable
internal fun CrashReportDialog(report: CrashReport, onCreateIssue: () -> Unit, onSaveLog: () -> Unit, onDismiss: () -> Unit) {
    val fullError = "${report.cause}\n\n${report.stackTrace}"
    Dialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_report_title)) },
        content = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.crash_report_description))
                Text("${report.appVersion} · ${report.androidVersion}", style = MaterialTheme.typography.bodySmall)
                ErrorContainer(
                    message = fullError,
                    title = stringResource(R.string.common_error),
                    initiallyExpanded = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        buttons = {
            TextButton(onClick = onCreateIssue) {
                SharedIcon(Icons.Outlined.OpenInNew, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.crash_create_issue))
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            TextButton(onClick = onSaveLog) {
                SharedIcon(Icons.Outlined.Save, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.crash_save_log))
            }
        }
    )
}
