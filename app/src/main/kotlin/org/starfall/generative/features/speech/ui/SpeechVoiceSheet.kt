package org.starfall.generative.features.speech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import org.starfall.generative.shared.ui.sheets.ItemListSheet
import org.starfall.generative.shared.ui.sheets.ItemListSheetItem

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
        ItemListSheet(
            items = voices.map { voice ->
                val selected = voice.id == service.voice
                ItemListSheetItem(
                    key = voice.id,
                    value = voice,
                    onClick = { onChoose(voice) },
                    selected = selected,
                    icon = {
                        SharedIcon(
                            Icons.Outlined.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    actions = {
                        if (selected) SharedIcon(Icons.Default.Check, stringResource(R.string.common_selected))
                    },
                    content = {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(voice.name, style = MaterialTheme.typography.titleSmall)
                            val subtitle = listOf(voice.description, voice.languages.joinToString())
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else null
                )
            },
            onDismissRequest = onDismiss,
            searchQuery = query,
            onSearchQueryChange = { query = it },
            searchPlaceholder = stringResource(R.string.speech_search_voices),
            filterContent = if (service.engine != SpeechEngine.ELEVENLABS) {
                @Composable {
                    Checkbox(checked = owned, onCheckedChange = { checked -> owned = checked })
                    Text(stringResource(R.string.speech_owned_voices))
                }
            } else null,
            loading = loading,
            error = error,
            onRetry = { retry++ },
            emptyMessage = stringResource(R.string.speech_no_voices),
            footer = if (cursor != null) {
                @Composable {
                    TextButton(
                        enabled = !loading,
                        onClick = { pageJob = scope.launch { fetch(cursor, true) } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.speech_load_more)) }
                }
            } else null,
            contentBehindNavigationBar = true,
            fixedHeightFraction = 0.8f
        )
    }
}
