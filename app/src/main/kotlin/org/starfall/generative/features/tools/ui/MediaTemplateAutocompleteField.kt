package org.starfall.generative.features.tools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import org.starfall.generative.shared.model.media.MediaRequestStep
import org.starfall.generative.shared.ui.inputs.TemplateAutocompleteMenu
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

/** Cursor-aware completion shared by endpoint, request JSON and header-value editors. */
@Composable
internal fun MediaTemplateAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    stepNumber: Int,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    previousSteps: List<MediaRequestStep> = emptyList(),
    testedResponsePaths: Map<Int, List<String>> = emptyMap()
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    val responsePaths = testedResponsePaths.toMap()
    val candidates = remember(stepNumber, previousSteps, responsePaths) {
        mediaVariableCandidates(stepNumber, previousSteps, responsePaths)
    }

    LaunchedEffect(value) {
        if (fieldValue.text != value) {
            fieldValue = TextFieldValue(value, TextRange(value.length))
        }
    }

    TemplateAutocompleteMenu(
        value = fieldValue,
        onValueChange = { fieldValue = it; onValueChange(it.text) },
        matches = { mediaVariableMatches(candidates, it) },
        complete = ::completeMediaVariable,
        optionTag = { "media-variable-option-${it.value}" },
        optionText = { suggestion, active ->
            Column {
                Text("{{${suggestion.value}${suggestion.pathHint?.let { "<$it>" }.orEmpty()}}}",
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(if (suggestion.step > 0) stringResource(suggestion.description, suggestion.step)
                    else stringResource(suggestion.description), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    ) { completionModifier ->
        OutlinedTextField(value = fieldValue, onValueChange = { updated ->
            fieldValue = updated
            if (updated.text != value) onValueChange(updated.text)
        }, modifier = modifier.then(completionModifier), textStyle = textStyle,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            label = label, supportingText = supportingText, isError = isError,
            singleLine = singleLine, minLines = minLines, maxLines = maxLines)
    }
}
