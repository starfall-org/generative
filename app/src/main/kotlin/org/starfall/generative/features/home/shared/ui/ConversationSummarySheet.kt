package org.starfall.generative.features.home.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.starfall.generative.R
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.ui.sheets.ContentSheet
import org.starfall.generative.shared.ui.sheets.ContentSheetTitle
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

/** Slider default; a custom target can still go above [MAX_SLIDER_TARGET_TOKENS]. */
private const val DEFAULT_TARGET_TOKENS = 6_000
private const val MAX_SLIDER_TARGET_TOKENS = 32_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSummarySheet(
    onStart: (ConversationSummaryRequest) -> Boolean,
    onDismiss: () -> Unit
) {
    ContentSheet(
        onDismissRequest = onDismiss,
        title = { ContentSheetTitle(stringResource(R.string.conversation_summarization_title)) }
    ) {
        ConversationSummarySheetContent(onStart = onStart, onDismiss = onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationSummarySheetContent(
    onStart: (ConversationSummaryRequest) -> Boolean,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    var targetSlider by remember { mutableFloatStateOf(DEFAULT_TARGET_TOKENS.toFloat()) }
    var customTarget by remember { mutableStateOf(false) }
    var customTargetText by remember { mutableStateOf(DEFAULT_TARGET_TOKENS.toString()) }
    var chunked by remember { mutableStateOf(false) }
    var chunkSlider by remember { mutableFloatStateOf(20_000f) }
    var customChunk by remember { mutableStateOf(false) }
    var customChunkText by remember { mutableStateOf("20000") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val localizedResources = LocalResources.current

    val targetTokens = if (customTarget) customTargetText.toIntOrNull() else targetSlider.roundToInt()
    val chunkTokens = if (customChunk) customChunkText.toIntOrNull() else chunkSlider.roundToInt()
    val targetValid = targetTokens != null && targetTokens > 0
    val chunkValid = !chunked || (chunkTokens != null && chunkTokens in 1..100_000)
    val validationError = when {
        !targetValid -> stringResource(R.string.conversation_summary_invalid_target)
        !chunkValid -> stringResource(R.string.conversation_summary_invalid_chunk)
        else -> null
    }

    Column(
        modifier = Modifier
            .testTag("summary-sheet-content")
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (onBack != null) {
            QuickActionsSubpageHeader(
                title = stringResource(R.string.conversation_summarization_title),
                onBack = onBack
            )
        }

        Text(stringResource(R.string.conversation_summary_target_size, targetTokens ?: 0), style = MaterialTheme.typography.titleMedium)
        val targetSliderState = remember {
            SliderState(value = targetSlider, trackRange = 1f..MAX_SLIDER_TARGET_TOKENS.toFloat())
        }
        targetSliderState.value = targetSlider
        Slider(
            state = targetSliderState,
            onValueChange = { targetSlider = it },
            enabled = !customTarget
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = customTarget, onCheckedChange = { customTarget = it })
            Text(stringResource(R.string.conversation_summary_custom_target))
        }
        if (customTarget) {
            OutlinedTextField(
                value = customTargetText,
                onValueChange = { customTargetText = it; error = null },
                label = { Text(stringResource(R.string.conversation_summary_target_tokens)) },
                isError = !targetValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(stringResource(R.string.conversation_summary_mode), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !chunked,
                onClick = { chunked = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text(stringResource(R.string.conversation_summary_mode_all)) }
            )
            SegmentedButton(
                selected = chunked,
                onClick = { chunked = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text(stringResource(R.string.conversation_summary_mode_passes)) }
            )
        }

        if (chunked) {
            Text(stringResource(R.string.conversation_summary_token_limit, chunkTokens ?: 0), style = MaterialTheme.typography.titleMedium)
            val chunkSliderState = remember {
                SliderState(value = chunkSlider, trackRange = 1f..100_000f)
            }
            chunkSliderState.value = chunkSlider
            Slider(
                state = chunkSliderState,
                onValueChange = { chunkSlider = it },
                enabled = !customChunk
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = customChunk, onCheckedChange = { customChunk = it })
                Text(stringResource(R.string.conversation_summary_custom_pass))
            }
            if (customChunk) {
                OutlinedTextField(
                    value = customChunkText,
                    onValueChange = { customChunkText = it; error = null },
                    label = { Text(stringResource(R.string.conversation_summary_tokens_per_pass)) },
                    isError = !chunkValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        val displayedError = validationError ?: error
        if (displayedError != null) {
            Text(displayedError,
                color = if (validationError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val request = ConversationSummaryRequest(
                    targetTokens = targetTokens ?: 1,
                    chunked = chunked,
                    tokensPerChunk = chunkTokens ?: 20_000
                )
                if (onStart(request)) onDismiss()
                else error = localizedResources.getString(R.string.conversation_summary_model_missing)
            },
            enabled = targetValid && chunkValid,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.conversation_summary_start))
        }
    }
}
