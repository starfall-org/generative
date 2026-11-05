package org.starfall.generative.shared.subfeatures.icons.ui

import org.starfall.generative.shared.ui.dialogs.AlertDialog

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.icons.logic.FaviconResult
import org.starfall.generative.shared.subfeatures.icons.logic.FaviconService
import org.starfall.generative.shared.subfeatures.icons.logic.IconStore
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import androidx.compose.foundation.BorderStroke
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.layout.nestedContainerColor

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
                selected = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                results = emptyList()
                selected = null
                error = failure.message ?: context.getString(R.string.favicon_image_error)
            } finally {
                fetching = false
            }
            dialogOpen = true
        }
    }

    /** Applies a picked logo immediately, without waiting for the dialog or editor to close. */
    internal fun select(choice: FaviconResult, onSave: (String) -> Unit) {
        if (fetching || saving || selected == choice) return
        saving = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            try {
                val name = choice.url.pathSegments.lastOrNull().orEmpty().ifBlank { choice.url.host }
                val id = withContext(Dispatchers.IO) { store.importBitmap(choice.bitmap, name) }
                onSave(id)
                selected = choice
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: context.getString(R.string.icon_import_failed)
                dialogOpen = true
            } finally {
                saving = false
            }
        } }
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
 * Fetches logos from the web only. Picking a result saves it immediately.
 * Uploading from the device lives on the logo itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FaviconDialog(
    state: FaviconFetchState,
    baseUrl: String,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val busy = state.fetching || state.saving
    val onClose = { state.dialogOpen = false }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(localizedResources.getString(R.string.favicon_get_logo)) },
        scrollableContent = false,
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.customUrl,
                    onValueChange = { state.customUrl = it; state.error = null },
                    enabled = !busy,
                    label = { Text(localizedResources.getString(R.string.favicon_homepage_url)) },
                    supportingText = { Text(localizedResources.getString(R.string.favicon_homepage_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.fetching) {
                                // Stops the search; the dialog stays open.
                                IconButton(onClick = { state.cancel() }) {
                                    SharedIcon(Icons.Outlined.Stop, contentDescription = localizedResources.getString(R.string.common_cancel))
                                }
                            } else if (!state.saving) {
                                if (state.customUrl.isNotEmpty()) {
                                    IconButton(onClick = { state.customUrl = ""; state.error = null }) {
                                        SharedIcon(Icons.Outlined.Close, contentDescription = localizedResources.getString(R.string.common_clear))
                                    }
                                }
                                IconButton(onClick = { state.fetch(baseUrl) }) {
                                    SharedIcon(Icons.Outlined.Search, contentDescription = localizedResources.getString(R.string.favicon_fetch))
                                }
                            }
                        }
                    }
                )
                if (busy) LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
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
                                onClick = { state.select(item, onSave) },
                                enabled = !busy,
                                modifier = Modifier.size(72.dp),
                                shape = shape,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    nestedContainerColor()
                                },
                                border = if (isSelected) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    nestedContainerBorder()
                                }
                            ) {
                                Image(
                                    bitmap = item.bitmap.asImageBitmap(),
                                    contentDescription = localizedResources.getString(R.string.favicon_preview),
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
            TextButton(onClick = onClose) { Text(localizedResources.getString(R.string.common_close)) }
        }
    )
}
