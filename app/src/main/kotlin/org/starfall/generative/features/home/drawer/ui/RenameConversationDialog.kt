package org.starfall.generative.features.home.drawer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.text.OutlinedTextField

@Composable
internal fun RenameConversationDialog(
    chat: ConversationListItem,
    onSuggestTitle: (suspend (String) -> Result<String>)?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(chat.id) { mutableStateOf(chat.title) }
    var generating by remember(chat.id) { mutableStateOf(false) }
    var error by remember(chat.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sidebar_rename_chat)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; error = false },
                modifier = Modifier.fillMaxWidth().testTag("rename-chat-title"),
                singleLine = true,
                label = { Text(stringResource(R.string.sidebar_chat_title)) },
                isError = error,
                supportingText = if (error) ({
                    Text(stringResource(R.string.sidebar_title_generation_failed), color = MaterialTheme.colorScheme.error)
                }) else null,
                trailingIcon = {
                    if (generating) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator(Modifier.size(22.dp))
                        }
                    } else {
                        IconButton(
                            modifier = Modifier.testTag("rename-chat-generate-title"),
                            enabled = onSuggestTitle != null && chat.hasMessages,
                            onClick = {
                                val originalTitle = title
                                error = false
                                generating = true
                                scope.launch {
                                    try {
                                        val suggestion = try {
                                            onSuggestTitle?.invoke(chat.id)
                                                ?: Result.failure(IllegalStateException("Title generation unavailable"))
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            Result.failure(failure)
                                        }
                                        // A manual edit made while the request was running wins over its response.
                                        if (title == originalTitle) {
                                            suggestion.onSuccess { title = it }
                                                .onFailure { error = true }
                                        }
                                    } finally {
                                        generating = false
                                    }
                                }
                            }
                        ) {
                            SharedIcon(Icons.Outlined.Refresh, stringResource(R.string.sidebar_regenerate_chat_title),
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
            )
        },
        confirmAtRight = true,
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onSave(title.trim()) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}
