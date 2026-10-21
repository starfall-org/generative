package org.starfall.generative.shared.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.shared.icons.FaviconResult
import org.starfall.generative.shared.icons.FaviconService
import org.starfall.generative.shared.icons.IconStore
import org.starfall.generative.shared.ui.icons.FluentIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.text.SelectableOutlinedTextField

/**
 * Fetches logos from the web only. Every image found is listed so the person picks one; nothing
 * is saved until they confirm. Uploading from the device lives on the logo itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FaviconDialog(
    baseUrl: String,
    onSave: (String) -> Unit,
    onBusyChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { FaviconService(context) }
    val store = remember(context) { IconStore(context) }
    var customUrl by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FaviconResult>>(emptyList()) }
    var selected by remember { mutableStateOf<FaviconResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val currentBusy by rememberUpdatedState(onBusyChange)
    DisposableEffect(Unit) { onDispose { currentBusy(false) } }

    fun fetch() {
        scope.launch {
            busy = true
            currentBusy(true)
            error = null
            try {
                val found = withContext(Dispatchers.IO) { service.getAll(customUrl, baseUrl) }
                results = found
                selected = found.firstOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                results = emptyList()
                selected = null
                error = failure.message ?: "Could not retrieve the image."
            } finally {
                busy = false
                currentBusy(false)
            }
        }
    }

    fun save(choice: FaviconResult) {
        scope.launch {
            busy = true
            currentBusy(true)
            error = null
            try {
                val name = choice.url.pathSegments.lastOrNull().orEmpty().ifBlank { choice.url.host }
                val id = withContext(Dispatchers.IO) { store.importBitmap(choice.bitmap, name) }
                onSave(id)
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: context.getString(R.string.icon_import_failed)
            } finally {
                busy = false
                currentBusy(false)
            }
        }
    }

    AppAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(context.getString(R.string.favicon_get_logo)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SelectableOutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it; error = null },
                    enabled = !busy,
                    label = { Text(context.getString(R.string.favicon_homepage_url)) },
                    supportingText = { Text(context.getString(R.string.favicon_homepage_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (customUrl.isNotEmpty()) {
                                IconButton(enabled = !busy, onClick = { customUrl = ""; error = null }) {
                                    FluentIcon(Icons.Outlined.Close, contentDescription = context.getString(R.string.common_clear))
                                }
                            }
                            TextButton(enabled = !busy, onClick = ::fetch) { Text(context.getString(R.string.favicon_fetch)) }
                        }
                    }
                )
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (results.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        results.forEach { item ->
                            val isSelected = item == selected
                            val shape = RoundedCornerShape(14.dp)
                            Surface(
                                modifier = Modifier.size(72.dp)
                                    .clip(shape)
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape
                                    )
                                    .clickable(enabled = !busy) { selected = item },
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Image(
                                    bitmap = item.bitmap.asImageBitmap(),
                                    contentDescription = context.getString(R.string.favicon_preview),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(6.dp)
                                )
                            }
                        }
                    }
                    selected?.let {
                        Text(it.url.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !busy && selected != null, onClick = { selected?.let(::save) }) {
                    Text(context.getString(R.string.common_save))
                }
                TextButton(enabled = !busy, onClick = onDismiss) { Text(context.getString(R.string.common_close)) }
            }
        }
    )
}
