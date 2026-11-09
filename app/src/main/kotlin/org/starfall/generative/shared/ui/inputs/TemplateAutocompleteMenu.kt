package org.starfall.generative.shared.ui.inputs

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.menus.ExposedDropdownMenu

/** The unfinished or cursor-edited {{variable}} containing the caret. */
data class TemplateVariableToken(val start: Int, val end: Int, val prefix: String)

fun templateVariableToken(text: String, cursor: Int): TemplateVariableToken? {
    if (cursor < 2 || cursor > text.length) return null
    val start = text.lastIndexOf("{{", cursor - 1)
    if (start < 0) return null
    val closing = text.indexOf("}}", start + 2)
    if (closing >= 0 && closing < cursor) return null
    val prefix = text.substring(start + 2, cursor)
    if (prefix.any { it == '{' || it == '}' || it == '\n' || it == '\r' }) return null
    return TemplateVariableToken(start, if (closing >= cursor) closing + 2 else cursor, prefix.trimStart())
}

/** Shared focus, dismissal and keyboard behavior; callers own candidates and text replacement. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> TemplateAutocompleteMenu(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    matches: (String) -> List<T>,
    complete: (TextFieldValue, TemplateVariableToken, T) -> TextFieldValue,
    optionTag: (T) -> String,
    optionText: @Composable (T, Boolean) -> Unit,
    enabled: Boolean = true,
    content: @Composable (Modifier) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var dismissedAt by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var activeIndex by remember { mutableIntStateOf(0) }
    val cursor = value.selection.end
    val token = if (enabled && focused && value.selection.collapsed) templateVariableToken(value.text, cursor) else null
    val suggestions = if (token != null && dismissedAt != (value.text to cursor)) matches(token.prefix) else emptyList()
    LaunchedEffect(token?.prefix, token?.start, suggestions) { activeIndex = 0 }

    fun apply(candidate: T) {
        val current = templateVariableToken(value.text, value.selection.end) ?: return
        val updated = complete(value, current, candidate)
        dismissedAt = updated.text to updated.selection.end
        onValueChange(updated)
    }

    ExposedDropdownMenuBox(expanded = suggestions.isNotEmpty(), onExpandedChange = {}) {
        content(Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = enabled)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || suggestions.isEmpty()) false
                else when (event.key) {
                    Key.DirectionDown -> { activeIndex = (activeIndex + 1) % suggestions.size; true }
                    Key.DirectionUp -> { activeIndex = (activeIndex - 1 + suggestions.size) % suggestions.size; true }
                    Key.Tab, Key.Enter, Key.NumPadEnter -> {
                        apply(suggestions[activeIndex.coerceIn(suggestions.indices)])
                        true
                    }
                    Key.Escape -> { dismissedAt = value.text to cursor; true }
                    else -> false
                }
            })
        ExposedDropdownMenu(expanded = suggestions.isNotEmpty(),
            onDismissRequest = { dismissedAt = value.text to cursor }, modifier = Modifier.heightIn(max = 288.dp)) {
            suggestions.forEachIndexed { index, suggestion ->
                DropdownMenuItem(modifier = Modifier.testTag(optionTag(suggestion)),
                    text = { optionText(suggestion, activeIndex == index) }, onClick = { apply(suggestion) })
            }
        }
    }
}
