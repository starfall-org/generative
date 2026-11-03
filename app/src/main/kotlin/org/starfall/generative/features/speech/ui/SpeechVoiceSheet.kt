package org.starfall.generative.features.speech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import org.starfall.generative.shared.ui.lists.ItemSurface
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.integrations.speech.SpeechVoice
import org.starfall.generative.integrations.speech.VoicePage
import org.starfall.generative.integrations.speech.StandaloneSpeechClient
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.ui.sheets.BottomSheet
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

@Composable
internal fun SpeechVoiceSheet(
    service: SpeechService,
    onChoose: (SpeechVoice) -> Unit,
    onDismiss: () -> Unit,
    fetchVoices: (suspend (SpeechService, String, String?, Boolean) -> VoicePage)? = null
) {
    // A new composition per engine/key prevents late responses from crossing accounts.
    key(service.engine, service.apiKey) {
        val client = remember { StandaloneSpeechClient() }
        var query by remember { mutableStateOf("") }
        var owned by remember { mutableStateOf(false) }
        var voices by remember { mutableStateOf<List<SpeechVoice>>(emptyList()) }
        var pageCount by remember { mutableIntStateOf(0) }
        var cursor by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        var pageJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        suspend fun fetch(next: String?, append: Boolean) {
            loading = true; error = null
            try {
                val page = fetchVoices?.invoke(service, query, next, owned) ?: client.voices(service, query, next, owned)
                voices = (if (append) voices + page.voices else page.voices).distinctBy { it.id }.take(500)
                pageCount = if (append) pageCount + 1 else 1
                cursor = page.nextCursor.takeIf { voices.size < 500 && pageCount < 25 }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                error = failure.message.orEmpty().let { if (service.apiKey.isBlank()) it else it.replace(service.apiKey, "[redacted]") }
            } finally { loading = false }
        }
        LaunchedEffect(query, owned, retry) {
            pageJob?.cancel(); voices = emptyList(); cursor = null
            delay(350)
            fetch(null, false)
        }
        DisposableEffect(Unit) { onDispose { pageJob?.cancel() } }
        BottomSheet(onDismissRequest = onDismiss, fixedHeightFraction = 0.8f) {
            Column(Modifier.fillMaxWidth().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.speech_choose_voice), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.speech_search_voices)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (service.engine != SpeechEngine.ELEVENLABS) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(owned, { owned = it })
                    Text(stringResource(R.string.speech_owned_voices))
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.common_retry)) }
                }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!loading && error == null && voices.isEmpty()) item { Text(stringResource(R.string.speech_no_voices)) }
                    items(voices, key = { it.id }) { voice ->
                        val selected = voice.id == service.voice
                        ItemSurface(isGrid = false, onClick = { onChoose(voice) },
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else prominentContainerColor()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(voice.name, style = MaterialTheme.typography.titleSmall)
                                    val subtitle = listOf(voice.description, voice.languages.joinToString())
                                        .filter { it.isNotBlank() }.joinToString(" · ")
                                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selected) SharedIcon(Icons.Default.Check, stringResource(R.string.common_selected))
                            }
                        }
                    }
                    if (cursor != null) item {
                        TextButton(enabled = !loading, onClick = { pageJob = scope.launch { fetch(cursor, true) } }) {
                            Text(stringResource(R.string.speech_load_more))
                        }
                    }
                }
            }
        }
    }
}
