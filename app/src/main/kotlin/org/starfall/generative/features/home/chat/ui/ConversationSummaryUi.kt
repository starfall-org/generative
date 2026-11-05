package org.starfall.generative.features.home.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.ConversationSummary
import org.starfall.generative.shared.model.ConversationSummaryProgress
import org.starfall.generative.shared.model.SummaryRole
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.LinearWavyProgressIndicator
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.layout.windowHeightIn

@Composable
fun ConversationSummaryDivider(
    summary: ConversationSummary?,
    progress: ConversationSummaryProgress?,
    onOpenSummary: () -> Unit,
    onCancelSummary: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val running = progress != null
    val canOpen = !running && summary != null
    val shape = RoundedCornerShape(16.dp)
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                SharedIcon(
                    Icons.Outlined.Summarize,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = progress?.label ?: stringResource(R.string.conversation_summary_title),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(10.dp))
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
            if (progress != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearWavyProgressIndicator(
                        progress = { progress.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onCancelSummary,
                        modifier = Modifier.size(40.dp)
                    ) {
                        SharedIcon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.common_cancel),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
    if (canOpen) {
        Surface(
            onClick = onOpenSummary,
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = shape,
            color = prominentContainerColor(),
            border = prominentContainerBorder(),
            content = content
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = shape,
            color = prominentContainerColor(),
            border = prominentContainerBorder(),
            content = content
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConversationSummaryDialog(
    summary: ConversationSummary,
    onRoleChange: (SummaryRole) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conversation_summary_title)) },
        content = {
            Text(
                summary.content,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowHeightIn(maxFraction = 0.75f)
                    .verticalScroll(rememberScrollState())
                    .testTag("summary-dialog-content"),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        buttons = {
            Column(
                Modifier.fillMaxWidth().testTag("summary-dialog-controls"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider()
                Text(stringResource(R.string.summary_send_as), style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("summary-role-selector")) {
                    val roles = listOf(
                        SummaryRole.SYSTEM to stringResource(R.string.common_system),
                        SummaryRole.ASSISTANT to stringResource(R.string.common_assistant),
                        SummaryRole.USER to stringResource(R.string.common_user)
                    )
                    roles.forEachIndexed { index, (role, label) ->
                        SegmentedButton(
                            selected = summary.role == role,
                            onClick = { onRoleChange(role) },
                            shape = SegmentedButtonDefaults.itemShape(index, roles.size),
                            label = { Text(label) }
                        )
                    }
                }
                FlowRow(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                    TextButton(
                        onClick = {
                            onDelete()
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        SharedIcon(Icons.Outlined.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.summary_delete))
                    }
                }
            }
        }
    )
}
