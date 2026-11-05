package org.starfall.generative.features.providers.shared.ui

import org.starfall.generative.features.providers.shared.logic.ModelConnectionTests

import org.starfall.generative.features.providers.shared.logic.ModelConnectionRemovals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.providers.shared.logic.isEmpty
import org.starfall.generative.features.providers.shared.model.ModelConnectionStatus
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.dialogs.ToolbarDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.windowHeightIn
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

@Composable
internal fun ModelConnectionDialog(provider: ModelProvider, tests: ModelConnectionTests, available: Boolean,
    onRemoveModels: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val removals = remember(provider.id) {
        ModelConnectionRemovals((provider.config.modelIds ?: provider.config.modelConfigs.keys.toList())
            .filter { provider.config.modelConfigs[it]?.modelType == ModelType.TEXT_GENERATION })
    }
    val textModels = removals.modelIds
    val testableModels = textModels.filterNot { it in removals.pending }
    val testingAny = tests.batchRunning.value || textModels.any { tests.running[it] == true }
    val progress = tests.progress(textModels)
    val unavailable = textModels.filter { tests.results[it]?.isFailure == true && it !in removals.pending }.toSet()
    val hasFailedRemovals = removals.pending.any { tests.results[it]?.isFailure == true }
    val showUnavailableAction = unavailable.isNotEmpty() || hasFailedRemovals
    fun close() {
        // Closing the dialog only hides its UI; connection tests belong to the editor screen.
        removals.close(remove = { ids -> tests.forget(ids); onRemoveModels(ids) }, dismiss = onDismiss)
    }
    val removedDescription = stringResource(R.string.model_removed_on_dialog_close)
    ToolbarDialog(
        onDismissRequest = ::close,
        title = { Text(stringResource(R.string.check_connection)) },
        toolbarModifier = Modifier.testTag("connection-toolbar"),
        toolbarLeading = {
            ModelConnectionCounts(progress, showProgress = true,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp))
        },
        toolbarActions = {
            IconButton(onClick = { if (testingAny) tests.cancelAll() else tests.testAll(provider, testableModels, skip = { it in removals.pending }) },
                enabled = (testingAny || testableModels.isNotEmpty()) && available) {
                if (testingAny) SharedIcon(Icons.Outlined.StopCircle, stringResource(R.string.common_cancel))
                else SharedIcon(Icons.Outlined.NetworkCheck, stringResource(R.string.provider_test_all_chat_models))
            }
            if (showUnavailableAction) {
                val restore = unavailable.isEmpty()
                IconButton(onClick = { if (restore) removals.restoreAll() else removals.mark(unavailable) }) {
                    SharedIcon(if (restore) Icons.Outlined.Restore else Icons.Outlined.DeleteSweep,
                        stringResource(if (restore) R.string.restore_removed_models else R.string.remove_unavailable_models),
                        tint = if (restore) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        },
        content = {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).windowHeightIn(maxFraction = 0.75f)
                .testTag("connection-model-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (textModels.isEmpty()) item { Text(stringResource(R.string.provider_test_no_chat_models)) }
                items(textModels, key = { it }) { id ->
                    val testing = tests.running[id] == true
                    val removed = id in removals.pending
                    val result = tests.results[id]
                    val config = provider.config.modelConfigs[id] ?: ModelConfiguration()
                    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (removed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            else prominentContainerColor(),
                        border = if (removed) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            else prominentContainerBorder(),
                        modifier = Modifier.fillMaxWidth().testTag("connection-model-$id")
                            .semantics { if (removed) stateDescription = removedDescription }
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(config.displayName.ifBlank { id },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (removed) mutedText else MaterialTheme.colorScheme.onSurface,
                                    textDecoration = if (removed) TextDecoration.LineThrough else null)
                                if (config.displayName.isNotBlank()) Text(id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (removed) mutedText else MaterialTheme.colorScheme.onSurfaceVariant)
                                result?.let { tested ->
                                    val status = tests.status(id) ?: ModelConnectionStatus.PASSED
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SharedIcon(status.icon(), null,
                                            tint = if (removed) mutedText else status.accent(),
                                            modifier = Modifier.size(16.dp))
                                        Text(status.message(tested), style = MaterialTheme.typography.bodySmall,
                                            color = if (removed) mutedText else status.textColor())
                                    }
                                }
                            }
                            IconButton(
                                modifier = Modifier.size(40.dp),
                                enabled = available && (testing || (!removed && !testingAny)),
                                onClick = { if (testing) tests.cancel(id) else tests.test(provider, id) }
                            ) {
                                SharedIcon(if (testing) Icons.Outlined.StopCircle else Icons.Outlined.NetworkCheck,
                                    if (testing) stringResource(R.string.common_cancel) else stringResource(R.string.test_model, id),
                                    modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                modifier = Modifier.size(40.dp),
                                onClick = { if (!removed && testing) tests.cancel(id); removals.toggle(id) }
                            ) {
                                SharedIcon(if (removed) Icons.Outlined.Restore else Icons.Outlined.Delete,
                                    stringResource(if (removed) R.string.restore_model else R.string.delete_model, id),
                                    modifier = Modifier.size(20.dp),
                                    tint = if (removed) mutedText else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    )
}
