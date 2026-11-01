package org.starfall.generative.shared.ui.logging

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.logging.DebugLogEntry
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon

private const val DetailChunkSize = 2_048

/** Full record, including safe metadata and stack trace. Large payloads stay lazily rendered. */
@Composable
internal fun DebugLogDetailDialog(entry: DebugLogEntry, onDismiss: () -> Unit, onCopy: () -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.debug_log_details)) },
        content = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)
                .testTag("debug-log-detail"),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "metadata") {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        LogMetadata(stringResource(R.string.debug_log_id), entry.id.toString())
                        LogMetadata(stringResource(R.string.debug_log_timestamp), entry.dateTime)
                        LogMetadata(stringResource(R.string.debug_log_source), entry.tag)
                        LogMetadata(stringResource(R.string.debug_log_category),
                            stringResource(entry.category.labelRes()))
                        LogMetadata(stringResource(R.string.debug_log_level), entry.level.name)
                        LogMetadata(stringResource(R.string.debug_log_thread), entry.thread)
                        if (entry.request) {
                            entry.method?.let { LogMetadata(stringResource(R.string.debug_log_http_method), it) }
                            entry.endpoint?.let { LogMetadata(stringResource(R.string.debug_log_endpoint), it) }
                            entry.statusCode?.let { LogMetadata(stringResource(R.string.debug_log_http_status), it.toString()) }
                        }
                        if (entry.request || entry.operation) {
                            LogMetadata(stringResource(R.string.debug_log_state),
                                stringResource(if (entry.finishedAtMillis == null) R.string.debug_log_pending
                                else if (entry.level == DebugLogLevel.ERROR) R.string.debug_log_failed
                                else R.string.debug_log_completed))
                            entry.durationMillis?.let { ms ->
                                LogMetadata(stringResource(R.string.debug_log_duration), "${ms} ms")
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 6.dp))
                        Text(stringResource(R.string.debug_log_message),
                            style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(entry.message.chunked(DetailChunkSize)) { part ->
                    SelectionContainer {
                        Text(part, style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                entry.stackTrace?.let { trace ->
                    item(key = "stacktrace-label") {
                        HorizontalDivider()
                        Text(stringResource(R.string.debug_log_stack_trace),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    items(trace.chunked(DetailChunkSize)) { part ->
                        SelectionContainer {
                            Text(part, style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        buttons = {
            TextButton(onClick = onCopy, modifier = Modifier.testTag("debug-log-detail-copy")) {
                SharedIcon(Icons.Outlined.ContentCopy, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.debug_log_copy_entry))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("debug-log-detail-close")) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

@Composable
private fun LogMetadata(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(112.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
        Text(value, modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
    }
}
