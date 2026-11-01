package org.starfall.generative.features.speech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.starfall.generative.R
import org.starfall.generative.integrations.speech.SpeechSynthesisModel
import org.starfall.generative.integrations.speech.StandaloneSpeechClient
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.text.OutlinedTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechModelField(
    service: SpeechService,
    onChange: (String) -> Unit,
    fetchModels: (suspend (SpeechService) -> List<SpeechSynthesisModel>)? = null
) {
    val client = remember { StandaloneSpeechClient() }
    val currentModelId by rememberUpdatedState(service.modelId)
    val currentOnChange by rememberUpdatedState(onChange)
    key(service.id, service.engine, service.apiKey) {
        var models by remember { mutableStateOf<List<SpeechSynthesisModel>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var expanded by remember { mutableStateOf(false) }
        // A saved model needs no catalog request until the user opens the picker.
        // Remember this per engine/key so selecting a model never triggers another fetch.
        var fetchRequested by remember { mutableStateOf(service.modelId.isNullOrBlank()) }
        var fetchedOnce by remember { mutableStateOf(false) }
        val canFetch = service.apiKey.isNotBlank()
        LaunchedEffect(fetchRequested) {
            if (!canFetch || !fetchRequested) return@LaunchedEffect
            loading = true; error = null
            try {
                delay(600)
                val fetched = fetchModels?.invoke(service) ?: client.models(service)
                currentCoroutineContext().ensureActive()
                models = fetched
                fetchedOnce = true
                // Never overwrite a saved model or one typed/chosen while the request was running.
                if (currentModelId.isNullOrBlank()) fetched.firstOrNull()?.id?.let(currentOnChange)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                error = failure.message.orEmpty().let { if (service.apiKey.isBlank()) it else it.replace(service.apiKey, "[redacted]") }.take(500)
            } finally {
                loading = false
                fetchRequested = false
            }
        }
        fun openMenu() {
            if (canFetch && !loading && !fetchedOnce && error == null) fetchRequested = true
            expanded = true
        }
        // An editable exposed-menu anchor requests focus when opened. Catalog requests and the
        // dropdown button must leave focus (and the keyboard) with the field the user was editing.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val menuWidth = maxWidth
            val menuAction = stringResource(if (expanded) R.string.collapse else R.string.expand)
            OutlinedTextField(service.modelId.orEmpty(), onChange,
                label = { Text(stringResource(R.string.speech_synthesis_model_id)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Row {
                        if (loading) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        IconButton(onClick = { if (expanded) expanded = false else openMenu() },
                            modifier = Modifier.testTag("speech-model-dropdown").semantics { contentDescription = menuAction }) {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        }
                    }
                }, modifier = Modifier.onFocusChanged { if (it.isFocused) openMenu() }
                    .fillMaxWidth().testTag("speech-model-id"))
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(menuWidth).heightIn(max = 420.dp).testTag("speech-model-menu"),
                properties = PopupProperties(focusable = false)
            ) {
                error?.let { detail ->
                    Text(detail, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_retry)) },
                        onClick = { fetchRequested = true }
                    )
                }
                if (loading || (fetchRequested && canFetch)) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                if (!loading && !(fetchRequested && canFetch) && error == null && models.isEmpty()) {
                    Text(
                        stringResource(if (canFetch) R.string.no_models_returned
                            else R.string.speech_engine_api_key_help),
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                models.forEach { model ->
                    DropdownMenuItem(
                        modifier = Modifier.testTag("speech-model-option:${model.id}"),
                        text = {
                            Column {
                                Text(model.name)
                                if (model.name != model.id) {
                                    Text(model.id, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        trailingIcon = {
                            if (model.id == service.modelId) {
                                SharedIcon(Icons.Default.Check, stringResource(R.string.common_selected))
                            }
                        },
                        onClick = { onChange(model.id); expanded = false }
                    )
                }
            }
        }
    }
}
