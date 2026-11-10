package org.starfall.generative.shared.ui.inputs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue

/**
 * A document-sized editing canvas, with no TextField decoration, border or internal card.
 * The entire available viewport is editable and long documents scroll with the caret.
 */
@Composable
fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    // Keep the IME composition and selection intact while the parent echoes typing back.
    LaunchedEffect(value) {
        val synced = fieldValue.withExternalText(value)
        if (synced !== fieldValue) fieldValue = synced
    }

    BasicTextField(
        value = fieldValue,
        onValueChange = { updated ->
            fieldValue = updated
            if (updated.text != value) onValueChange(updated.text)
        },
        modifier = modifier.dismissInputFocusOnOutsidePress().selectAllOnTripleClick {
            fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
        },
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize()) {
                if (fieldValue.text.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
                innerTextField()
            }
        }
    )
}
