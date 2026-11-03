package org.starfall.generative.shared.ui.inputs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Borderless name input meant for a TopAppBar `title` slot, replacing the static screen title.
 * A thin underline (brighter while focused) signals that it is editable.
 */
@Composable
fun HeaderNameField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    // Same IME-safe sync as OutlinedTextField: only apply genuinely external changes.
    LaunchedEffect(value) {
        val synced = fieldValue.withExternalText(value)
        if (synced !== fieldValue) fieldValue = synced
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(enabled) { if (!enabled) focusManager.clearFocus() }
    val style = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface
    )
    val lineColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    BasicTextField(
        value = fieldValue,
        onValueChange = { updated ->
            fieldValue = updated
            if (updated.text != value) onValueChange(updated.text)
        },
        modifier = modifier
            .fillMaxWidth()
            .selectAllOnTripleClick { fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length)) },
        enabled = enabled,
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val stroke = (if (focused) 2.dp else 1.dp).toPx()
                        drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, size.height - stroke / 2),
                            androidx.compose.ui.geometry.Offset(size.width, size.height - stroke / 2), stroke)
                    }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (fieldValue.text.isEmpty()) Text(
                    placeholder, style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                inner()
            }
        }
    )
}
