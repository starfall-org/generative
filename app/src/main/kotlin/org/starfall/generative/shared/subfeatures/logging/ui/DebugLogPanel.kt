package org.starfall.generative.shared.subfeatures.logging.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLog
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLogEntry
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.layout.nestedContainerColor
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.menus.DropdownMenu

/** Compact, newest-first event list; tapping a row opens a full diagnostic record. */
@Composable
internal fun DebugLogPanel(modifier: Modifier = Modifier) {
    val entries by DebugLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var category by remember { mutableStateOf<DebugLogCategory?>(null) }
    var level by remember { mutableStateOf<DebugLogLevel?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val visible = remember(entries, query, category, level) {
        entries.asReversed().filter { entry ->
            entry.level.priority >= DebugLog.minimumLevel.priority &&
            (category == null || category == entry.category) &&
                (level == null || level == entry.level) &&
                (query.isBlank() || listOf(entry.tag, entry.message, entry.summary.orEmpty(),
                    entry.stackTrace.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) })
        }
    }
    val selected = entries.firstOrNull { it.id == selectedId }

    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp),
        color = prominentContainerColor(opacityBoost = 0.47f),
        border = prominentContainerBorder(), shadowElevation = 8.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${stringResource(R.string.debug_logs)} · ${visible.size}/${entries.size}",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (category != null || level != null) {
                        Text(listOfNotNull(category?.let { stringResource(it.labelRes()) }, level?.name)
                            .joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("debug-log-filter-summary"))
                    }
                }
                Box {
                    IconButton(onClick = { filtersOpen = !filtersOpen },
                        modifier = Modifier.testTag("debug-log-filter")) {
                        SharedIcon(Icons.Outlined.FilterList,
                            "${stringResource(R.string.debug_log_category)} / ${stringResource(R.string.debug_log_level)}",
                            tint = if (category != null || level != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = filtersOpen, onDismissRequest = { filtersOpen = false }) {
                        Text(stringResource(R.string.debug_log_category),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
                        DropdownMenuItem(text = { Text(stringResource(R.string.debug_log_all_categories)) },
                            onClick = { category = null },
                            trailingIcon = if (category == null) {
                                { SharedIcon(Icons.Outlined.Check, null) }
                            } else null,
                            modifier = Modifier.testTag("debug-log-category-all"))
                        DebugLogCategory.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(stringResource(item.labelRes())) },
                                onClick = { category = item },
                                trailingIcon = if (category == item) {
                                    { SharedIcon(Icons.Outlined.Check, null) }
                                } else null,
                                modifier = Modifier.testTag("debug-log-category-${item.value}"))
                        }
                        HorizontalDivider()
                        Text(stringResource(R.string.debug_log_level),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
                        DropdownMenuItem(text = { Text(stringResource(R.string.debug_log_all_levels)) },
                            onClick = { level = null },
                            trailingIcon = if (level == null) {
                                { SharedIcon(Icons.Outlined.Check, null) }
                            } else null,
                            modifier = Modifier.testTag("debug-log-level-all"))
                        DebugLogLevel.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(item.name) },
                                onClick = { level = item },
                                trailingIcon = if (level == item) {
                                    { SharedIcon(Icons.Outlined.Check, null) }
                                } else null,
                                modifier = Modifier.testTag("debug-log-level-${item.name.lowercase()}"))
                        }
                    }
                }
                IconButton(onClick = { copyLog(context, DebugLog.exportAll(visible.asReversed())) },
                    enabled = visible.isNotEmpty()) {
                    SharedIcon(Icons.Outlined.ContentCopy, stringResource(R.string.debug_log_copy_all))
                }
                IconButton(onClick = DebugLog::clear, enabled = entries.isNotEmpty()) {
                    SharedIcon(Icons.Outlined.Delete, stringResource(R.string.debug_log_clear_all))
                }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text(stringResource(R.string.debug_log_search)) },
                leadingIcon = { SharedIcon(Icons.Outlined.Search, null) },
                trailingIcon = if (query.isNotBlank()) {
                    { IconButton(onClick = { query = "" }) {
                        SharedIcon(Icons.Outlined.Close, stringResource(R.string.common_clear))
                    } }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .testTag("debug-log-search"),
                shape = RoundedCornerShape(18.dp))
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(if (entries.isEmpty()) R.string.debug_log_empty
                        else R.string.debug_log_no_matches), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(visible, key = { it.id }) { entry ->
                        DebugLogRow(entry, onClick = { selectedId = entry.id })
                    }
                }
            }
        }
    }
    if (selected != null) DebugLogDetailDialog(entry = selected,
        onDismiss = { selectedId = null }, onCopy = { copyLog(context, selected.format()) })
}

@Composable
private fun DebugLogRow(entry: DebugLogEntry, onClick: () -> Unit) {
    val accent = when (entry.level) {
        DebugLogLevel.ERROR -> MaterialTheme.colorScheme.error
        DebugLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        DebugLogLevel.INFO -> MaterialTheme.colorScheme.primary
        DebugLogLevel.DEBUG, DebugLogLevel.TRACE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("debug-log-entry-${entry.id}"),
        shape = RoundedCornerShape(12.dp), color = nestedContainerColor(), border = nestedContainerBorder()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.level.name, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, color = accent)
                Text(stringResource(entry.category.labelRes()), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(entry.time, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(entry.tag, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(entry.preview, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun copyLog(context: Context, text: String) {
    val copied = runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: error(context.getString(R.string.debug_clipboard_unavailable))
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.debug_log_clip_label), text))
    }.isSuccess
    if (!copied) Toast.makeText(context, R.string.debug_log_copy_failed, Toast.LENGTH_SHORT).show()
    else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.common_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}
