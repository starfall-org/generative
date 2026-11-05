package org.starfall.generative.features.home.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.DEFAULT_REASONING_EFFORTS
import org.starfall.generative.shared.subfeatures.modelpicker.ui.localizedReasoningEffortLabels
import org.starfall.generative.shared.ui.sheets.ContentSheet
import org.starfall.generative.shared.ui.sheets.ContentSheetTitle
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ReasoningEffortControl
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortOptions
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortIndex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningEffortSheet(
    currentEffort: String?,
    onApply: (String?) -> Unit,
    onDismiss: () -> Unit,
    providerType: ModelApiType? = null,
    modelId: String = "",
    maxTokens: Int = Int.MAX_VALUE,
    supportedEfforts: List<String> = DEFAULT_REASONING_EFFORTS
) {
    var effort by remember(currentEffort) { mutableStateOf(currentEffort) }
    val reasoningLabels = localizedReasoningEffortLabels()
    val options = remember(supportedEfforts, reasoningLabels) {
        reasoningEffortOptions(supportedEfforts, reasoningLabels)
    }

    ContentSheet(
        onDismissRequest = onDismiss
        ,title = { ContentSheetTitle(stringResource(R.string.reasoning_effort_title)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.reasoning_effort_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                options[reasoningEffortIndex(effort, options)].label,
                style = MaterialTheme.typography.headlineSmall
            )
            ReasoningEffortControl(effort = effort, onEffortChange = { effort = it }, options = options)
            Button(
                onClick = {
                    onApply(effort)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.common_apply))
            }
        }
    }
}
