package org.starfall.generative.navigation

import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.screens.ScreenTemplate
import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.starfall.generative.R

/** Only unsaved drafts bypass loading. Existing metadata must go through [load]. */
@Composable
internal fun <T : Any> TargetedEditorContent(
    recordId: String,
    title: String,
    loadingMessage: String,
    onBack: () -> Unit,
    load: suspend () -> T,
    draft: T? = null,
    content: @Composable (T) -> Unit,
) {
    val isDraft = draft != null
    var record by remember(recordId, isDraft) { mutableStateOf(draft) }
    var failure by remember(recordId, isDraft) { mutableStateOf<String?>(null) }
    var attempt by remember(recordId, isDraft) { mutableIntStateOf(0) }
    val loadFailureFallback = stringResource(R.string.editor_load_failed_fallback)
    LaunchedEffect(recordId, isDraft, attempt) {
        if (isDraft) return@LaunchedEffect
        record = null
        failure = null
        try {
            record = load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message?.takeIf { it.isNotBlank() }
                ?: loadFailureFallback
        }
    }

    val loaded = record
    if (loaded != null) {
        content(loaded)
        return
    }

    ScreenTemplate(title = title, onBack = onBack) {
        ContentPreview(
            modifier = Modifier.fillMaxSize(),
            scrollable = false,
            contentPadding = scrollableScaffoldPadding(scaffoldPadding, horizontal = 24.dp, top = 24.dp, bottom = 24.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val error = failure
                    if (error == null) {
                        LoadingIndicator()
                        Text(loadingMessage, textAlign = TextAlign.Center)
                    } else {
                        Text(stringResource(R.string.editor_open_failed), style = MaterialTheme.typography.titleMedium)
                        Text(error, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.editor_load_retry_hint), textAlign = TextAlign.Center)
                        Button(onClick = { failure = null; attempt++ }) { Text(stringResource(R.string.common_retry)) }
                    }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
                }
            }
        }
    }
}
