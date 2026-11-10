package org.starfall.generative.shared.ui.inputs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

private class InputFocusTarget {
    var coordinates: LayoutCoordinates? = null
}

private class InputFocusState(val dismiss: () -> Unit) {
    var focused: InputFocusTarget? = null
}

private val LocalInputFocusState = staticCompositionLocalOf<InputFocusState?> { null }

/**
 * Observes presses before child controls consume them, without consuming gestures itself.
 * Each window needs its own host. Only shared inputs opt in; Chat's input keeps its policy.
 */
@Composable
fun InputFocusHost(
    modifier: Modifier = Modifier,
    propagateMinConstraints: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val focusSink = remember { FocusRequester() }
    val state = remember { InputFocusState { focusSink.requestFocus() } }
    val host = remember { InputFocusTarget() }
    CompositionLocalProvider(LocalInputFocusState provides state) {
        Box(
            modifier.onGloballyPositioned { host.coordinates = it }
                .pointerInput(state) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                                ?: continue
                            val target = state.focused?.coordinates ?: continue
                            val origin = host.coordinates ?: continue
                            if (origin.isAttached && target.isAttached &&
                                !origin.localBoundingBoxOf(target).contains(down.position)
                            ) {
                                state.dismiss()
                            }
                        }
                    }
                },
            propagateMinConstraints = propagateMinConstraints,
            contentAlignment = contentAlignment
        ) {
            content()
            // Keep a non-input destination in this window. Clearing the Android owner
            // can immediately focus the first field again, especially with a keyboard.
            Box(Modifier.requiredSize(0.dp).focusRequester(focusSink).focusTarget())
        }
    }
}

/** Moves focus to the window host without letting Android re-focus the first input. */
@Composable
internal fun rememberClearInputFocus(): () -> Unit {
    val state = LocalInputFocusState.current
    val focusManager = LocalFocusManager.current
    return remember(state, focusManager) {
        { if (state != null) state.dismiss() else focusManager.clearFocus(force = true) }
    }
}

/** Registers the whole decorated field so taps on padding and field actions keep focus. */
internal fun Modifier.dismissInputFocusOnOutsidePress(): Modifier = composed {
    val state = LocalInputFocusState.current ?: return@composed this
    val target = remember { InputFocusTarget() }
    DisposableEffect(state, target) {
        onDispose { if (state.focused === target) state.focused = null }
    }
    this.onGloballyPositioned { target.coordinates = it }
        .onFocusChanged {
            if (it.isFocused) state.focused = target
            else if (state.focused === target) state.focused = null
        }
}
