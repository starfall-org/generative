@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.starfall.generative.features.home.chat.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow
import org.starfall.generative.R
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Uncontained Material loading indicator using its built-in default size. */
@Composable
internal fun GenerationLoadingIndicator(
    modifier: Modifier = Modifier,
    streamState: StateFlow<ActiveChatStreamState?>? = null,
    conversationId: String? = null,
    messageId: String? = null
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        val stream = streamState?.collectAsState()?.value
        val preparing = stream?.conversationId == conversationId && stream?.messageId == messageId && stream?.preparingAttachments == true
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(modifier = Modifier.testTag("generation-loading-indicator"))
            if (preparing) Text(stringResource(R.string.attachment_uploading), style = MaterialTheme.typography.labelMedium)
        }
    }
}
