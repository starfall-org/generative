package org.starfall.generative.features.appdata.ui

import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.dialogs.Dialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.subfeatures.auth.logic.oauthErrorDetails
import org.starfall.generative.database.repositories.ProviderRepairResult
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDataScreen(
    conversationCount: Int,
    providerCount: Int,
    onClearAllConversations: () -> Unit,
    onResetAllData: () -> Unit,
    onExportZip: () -> Unit = {},
    onImportZip: () -> Unit = {},
    exportInProgress: Boolean = false,
    importInProgress: Boolean = false,
    onRepairProviders: (suspend () -> Result<ProviderRepairResult>)? = null,
    onBack: () -> Unit
) {
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var showSensitiveActions by rememberSaveable { mutableStateOf(false) }
    var showExportWarning by rememberSaveable { mutableStateOf(false) }
    var showRepairConfirm by rememberSaveable { mutableStateOf(false) }
    var repairing by rememberSaveable { mutableStateOf(false) }
    var repairCount by rememberSaveable { mutableStateOf<Int?>(null) }
    var repairError by rememberSaveable { mutableStateOf<String?>(null) }
    var repairFailedIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val transferBusy = exportInProgress || importInProgress
    // Transfers belong to the application scope, not this screen. Navigation never waits for them.
    EditorScreenTemplate(
        title = stringResource(R.string.settings_data_controls),
        onBack = onBack
    ) { scaffoldPadding ->
        val innerPadding = scrollableScaffoldPadding(scaffoldPadding, horizontal = 20.dp, top = 16.dp, bottom = 16.dp)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                DataSection(title = stringResource(R.string.settings_data_overview)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        DataStatItem(stringResource(R.string.settings_chats), conversationCount.toString())
                        DataStatItem(stringResource(R.string.model_providers), providerCount.toString())
                    }
                }
            }
            item {
                DataSection(title = stringResource(R.string.settings_export_data)) {
                    Text(
                        stringResource(R.string.settings_export_data_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DataActionButton(
                        title = stringResource(R.string.settings_export_all_data_zip),
                        enabled = !transferBusy
                    ) {
                        showExportWarning = true
                    }
                    Spacer(Modifier.height(4.dp))
                    DataActionButton(
                        title = stringResource(R.string.settings_import_all_data_zip),
                        enabled = !transferBusy
                    ) {
                        onImportZip()
                    }
                    if (importInProgress) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                stringResource(R.string.settings_import_background_status),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (onRepairProviders != null) item {
                DataSection(title = stringResource(R.string.settings_repair_provider_title)) {
                    Text(stringResource(R.string.settings_repair_provider_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DataActionButton(
                        title = stringResource(R.string.settings_repair_provider_action),
                        enabled = !transferBusy && !repairing,
                        modifier = Modifier.testTag("app-data-repair-providers")
                    ) { showRepairConfirm = true }
                    if (repairing) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.settings_repair_provider_running))
                    }
                    repairCount?.let { Text(stringResource(R.string.settings_repair_provider_result, it)) }
                    repairError?.let { ErrorContainer(it) }
                    if (repairFailedIds.isNotEmpty()) ErrorContainer(
                        stringResource(R.string.settings_repair_provider_failed, repairFailedIds.joinToString(", "))
                    )
                }
            }
            item {
                DataActionButton(
                    title = stringResource(R.string.settings_manage_data),
                    enabled = !transferBusy,
                    modifier = Modifier.testTag("app-data-manage")
                ) { showSensitiveActions = true }
            }
        }

    }

    if (showRepairConfirm && !transferBusy) {
        AlertDialog(
            onDismissRequest = { showRepairConfirm = false },
            title = { Text(stringResource(R.string.settings_repair_provider_title)) },
            text = { Text(stringResource(R.string.settings_repair_provider_warning)) },
            confirmButton = {
                Button(onClick = {
                    showRepairConfirm = false
                    repairing = true
                    repairCount = null
                    repairError = null
                    repairFailedIds = emptyList()
                    scope.launch {
                        try {
                            val result = onRepairProviders?.invoke()
                            result?.onSuccess {
                                repairCount = it.repaired
                                repairFailedIds = it.failedIds
                            }?.onFailure { repairError = oauthErrorDetails(it) }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { repairError = oauthErrorDetails(e) }
                        finally { repairing = false }
                    }
                }) { Text(stringResource(R.string.settings_repair_provider_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showRepairConfirm = false }) { Text(stringResource(R.string.common_close)) }
            }
        )
    }

    if (showSensitiveActions && !transferBusy) {
        Dialog(
            onDismissRequest = { showSensitiveActions = false },
            title = { Text(stringResource(R.string.settings_manage_data)) },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            showSensitiveActions = false
                            showClearConfirm = true
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().testTag("app-data-clear-chats")
                    ) {
                        SharedIcon(Icons.Outlined.Delete, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.settings_clear_all_chat_history))
                    }
                    OutlinedButton(
                        onClick = {
                            showSensitiveActions = false
                            showResetConfirm = true
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().testTag("app-data-reset-all")
                    ) {
                        SharedIcon(Icons.Outlined.Delete, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.settings_reset_all_application_data))
                    }
                }
            },
            buttons = {
                TextButton(onClick = { showSensitiveActions = false }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }

    if (showExportWarning && !transferBusy) {
        AlertDialog(
            onDismissRequest = { showExportWarning = false },
            title = { Text(stringResource(R.string.settings_backup_sensitive_warning_title)) },
            text = { Text(stringResource(R.string.settings_backup_sensitive_warning_message)) },
            confirmButton = {
                Button(onClick = {
                    showExportWarning = false
                    onExportZip()
                }) { Text(stringResource(R.string.settings_backup_sensitive_warning_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportWarning = false }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }

    if (showClearConfirm && !transferBusy) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_all_chats_title)) },
            text = { Text(stringResource(R.string.settings_clear_all_chats_message)) },
            confirmButton = {
                Button(onClick = {
                    onClearAllConversations()
                    showClearConfirm = false
                }) { Text(stringResource(R.string.settings_clear_all)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }

    if (showResetConfirm && !transferBusy) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_factory_reset_title)) },
            text = { Text(stringResource(R.string.settings_factory_reset_message)) },
            confirmButton = {
                Button(onClick = {
                    onResetAllData()
                    showResetConfirm = false
                }) { Text(stringResource(R.string.settings_reset_everything)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }
}

@Composable
private fun DataSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun DataStatItem(label: String, count: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = count,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun DataActionButton(
    title: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(title)
    }
}
