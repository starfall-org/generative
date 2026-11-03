package org.starfall.generative.features.tools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.model.media.MediaRequestStep
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.menus.ExposedDropdownMenu
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

/** Cursor-aware completion shared by endpoint, request JSON and header-value editors. */
@OptIn(ExperimentalMaterial3Api::class)
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
    var focused by remember { mutableStateOf(false) }
    var dismissedAt by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var activeIndex by remember { mutableIntStateOf(0) }
    val responsePaths = testedResponsePaths.toMap()
    val candidates = remember(stepNumber, previousSteps, responsePaths) {
        mediaVariableCandidates(stepNumber, previousSteps, responsePaths)
    }

    LaunchedEffect(value) {
        if (fieldValue.text != value) {
            fieldValue = TextFieldValue(value, TextRange(value.length))
        }
    }

    val cursor = fieldValue.selection.end
    val token = if (focused && fieldValue.selection.collapsed) {
        mediaVariableToken(fieldValue.text, cursor)
    } else null
    val suggestions = if (token != null && dismissedAt != (fieldValue.text to cursor)) {
        mediaVariableMatches(candidates, token.prefix)
    } else emptyList()
    LaunchedEffect(token?.prefix, token?.start, suggestions.size) { activeIndex = 0 }

    fun apply(candidate: MediaVariableCandidate) {
        val current = mediaVariableToken(fieldValue.text, fieldValue.selection.end) ?: return
        val updated = completeMediaVariable(fieldValue, current, candidate)
        fieldValue = updated
        dismissedAt = updated.text to updated.selection.end
        onValueChange(updated.text)
    }

    ExposedDropdownMenuBox(expanded = suggestions.isNotEmpty(), onExpandedChange = {}) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { updated ->
                fieldValue = updated
                if (updated.text != value) onValueChange(updated.text)
            },
            modifier = modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || suggestions.isEmpty()) false
                    else when (event.key) {
                        Key.DirectionDown -> {
                            activeIndex = (activeIndex + 1) % suggestions.size
                            true
                        }
                        Key.DirectionUp -> {
                            activeIndex = (activeIndex - 1 + suggestions.size) % suggestions.size
                            true
                        }
                        Key.Tab, Key.Enter, Key.NumPadEnter -> {
                            apply(suggestions[activeIndex.coerceIn(suggestions.indices)])
                            true
                        }
                        Key.Escape -> {
                            dismissedAt = fieldValue.text to cursor
                            true
                        }
                        else -> false
                    }
                },
            textStyle = textStyle,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password
            ),
            label = label,
            supportingText = supportingText,
            isError = isError,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines
        )
        ExposedDropdownMenu(
            expanded = suggestions.isNotEmpty(),
            onDismissRequest = { dismissedAt = fieldValue.text to cursor },
            modifier = Modifier.heightIn(max = 288.dp)
        ) {
            suggestions.forEachIndexed { index, suggestion ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("media-variable-option-${suggestion.value}"),
                    text = {
                        Column {
                            Text(
                                "{{${suggestion.value}${suggestion.pathHint?.let { "<$it>" }.orEmpty()}}}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (activeIndex == index) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (suggestion.step > 0) stringResource(suggestion.description, suggestion.step)
                                else stringResource(suggestion.description),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = { apply(suggestion) }
                )
            }
        }
    }
}
