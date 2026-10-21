package org.starfall.generative.shared.ui.dialogs

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Holds the logo fetch outside the dialog so closing the dialog does not stop it. The fetch is
 * tied to whoever remembers this state (see [rememberFaviconFetchState]) and is cancelled when
 * that composable leaves the composition. The dialog reopens by itself once the fetch ends.
 */
@Stable
class FaviconFetchState internal constructor(private val context: Context, private val scope: CoroutineScope) {
    private val service = FaviconService(context)
    private val store = IconStore(context)
    private var fetchJob: Job? = null

    var dialogOpen by mutableStateOf(false)
    var customUrl by mutableStateOf("")
    internal var results by mutableStateOf<List<FaviconResult>>(emptyList())
    internal var selected by mutableStateOf<FaviconResult?>(null)
    var error by mutableStateOf<String?>(null)
    var fetching by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set

    fun fetch(baseUrl: String) {
        if (fetching) return
        fetchJob = scope.launch {
            fetching = true
            error = null
            try {
                val found = service.getAll(customUrl, baseUrl)
                results = found
                selected = found.firstOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                results = emptyList()
                selected = null
                error = failure.message ?: "Could not retrieve the image."
            } finally {
                fetching = false
            }
            dialogOpen = true
        }
    }

    /** Closes the dialog. The selected logo is saved; a fetch still running keeps going. */
    fun close(onSave: (String) -> Unit) {
        dialogOpen = false
        val choice = selected
        if (fetching || choice == null) return
        results = emptyList()
        selected = null
        scope.launch {
            saving = true
            try {
                val name = choice.url.pathSegments.lastOrNull().orEmpty().ifBlank { choice.url.host }
                val id = withContext(Dispatchers.IO) { store.importBitmap(choice.bitmap, name) }
                onSave(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                results = listOf(choice)
                selected = choice
                error = failure.message ?: context.getString(R.string.icon_import_failed)
                dialogOpen = true
            } finally {
                saving = false
            }
        }
    }

    fun cancel() {
        fetchJob?.cancel()
        fetchJob = null
        fetching = false
    }
}

@Composable
fun rememberFaviconFetchState(): FaviconFetchState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(context, scope) { FaviconFetchState(context.applicationContext, scope) }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}

/**
 * Fetches logos from the web only. Every image found is listed so the person picks one; closing
 * the dialog saves the picked one. Uploading from the device lives on the logo itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FaviconDialog(
    state: FaviconFetchState,
    baseUrl: String,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    val busy = state.fetching
    val onClose = { state.close(onSave) }

    AppAlertDialog(
        onDismissRequest = onClose,
        title = { Text(context.getString(R.string.favicon_get_logo)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SelectableOutlinedTextField(
                    value = state.customUrl,
                    onValueChange = { state.customUrl = it; state.error = null },
                    enabled = !busy,
                    label = { Text(context.getString(R.string.favicon_homepage_url)) },
                    supportingText = { Text(context.getString(R.string.favicon_homepage_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (busy) {
                                // Stops the search; the dialog stays open.
                                IconButton(onClick = { state.cancel() }) {
                                    FluentIcon(Icons.Outlined.Stop, contentDescription = context.getString(R.string.common_cancel))
                                }
                            } else {
                                if (state.customUrl.isNotEmpty()) {
                                    IconButton(onClick = { state.customUrl = ""; state.error = null }) {
                                        FluentIcon(Icons.Outlined.Close, contentDescription = context.getString(R.string.common_clear))
                                    }
                                }
                                IconButton(onClick = { state.fetch(baseUrl) }) {
                                    FluentIcon(Icons.Outlined.Search, contentDescription = context.getString(R.string.favicon_fetch))
                                }
                            }
                        }
                    }
                )
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.results.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        state.results.forEach { item ->
                            val isSelected = item == state.selected
                            val shape = RoundedCornerShape(14.dp)
                            Surface(
                                modifier = Modifier.size(72.dp)
                                    .clip(shape)
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape
                                    )
                                    .clickable(enabled = !busy) { state.selected = item },
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
                    state.selected?.let {
                        Text(it.url.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(context.getString(R.string.common_close)) }
        }
    )
}
