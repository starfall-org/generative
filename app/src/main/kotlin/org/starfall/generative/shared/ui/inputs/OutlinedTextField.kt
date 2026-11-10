package org.starfall.generative.shared.ui.inputs
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField as MaterialOutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/** Adds the desktop-style third-click "select all" behavior without consuming text-field taps. */
fun Modifier.selectAllOnTripleClick(onSelectAll: () -> Unit): Modifier = composed {
    val currentSelectAll by rememberUpdatedState(onSelectAll)
    pointerInput(Unit) {
        var clickCount = 0
        var previousClickTime = 0L
        var previousClickPosition = Offset.Zero
        var hasPreviousClick = false
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var change = down
            do {
                change = awaitPointerEvent(PointerEventPass.Final).changes
                    .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            } while (change.pressed)
            val up = change
            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                clickCount = 0
                hasPreviousClick = false
                return@awaitEachGesture
            }
            val closeInTime = up.uptimeMillis - previousClickTime <= viewConfiguration.doubleTapTimeoutMillis
            val closeInSpace = hasPreviousClick &&
                (down.position - previousClickPosition).getDistance() <= viewConfiguration.touchSlop * 2
            clickCount = if (closeInTime && closeInSpace) clickCount + 1 else 1
            previousClickTime = up.uptimeMillis
            previousClickPosition = down.position
            hasPreviousClick = true
            if (clickCount == 3) {
                currentSelectAll()
                clickCount = 0
                hasPreviousClick = false
            }
        }
    }
}


internal fun TextFieldValue.withExternalText(text: String): TextFieldValue {
    if (this.text == text) return this
    return copy(
        text = text,
        selection = TextRange(
            selection.start.coerceAtMost(text.length),
            selection.end.coerceAtMost(text.length)
        ),
        composition = null
    )
}

/** Material text field used throughout the app, with a stable selection exposed for triple-click. */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences
    ),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }

    // Do not derive TextFieldValue from the String on every recomposition. The IME owns
    // selection/composition while editing; replacing that value mid-edit makes composing
    // keyboards (for example Gboard Translate) think the input connection was reset.
    // Only apply a genuinely external text change. An onValueChange echo already matches
    // fieldValue.text, so its composition range is left untouched.
    LaunchedEffect(value) {
        val syncedValue = fieldValue.withExternalText(value)
        if (syncedValue !== fieldValue) fieldValue = syncedValue
    }

    MaterialOutlinedTextField(
        value = fieldValue,
        onValueChange = { updated ->
            fieldValue = updated
            if (updated.text != value) onValueChange(updated.text)
        },
        modifier = modifier.dismissInputFocusOnOutsidePress().selectAllOnTripleClick {
            fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
        },
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors
    )
}

/** Caller-managed text selection, for editable fields such as cursor-aware autocomplete. */
@Composable
fun OutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    MaterialOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.dismissInputFocusOnOutsidePress().selectAllOnTripleClick {
            onValueChange(value.copy(selection = TextRange(0, value.text.length)))
        },
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = shape,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        colors = colors
    )
}
