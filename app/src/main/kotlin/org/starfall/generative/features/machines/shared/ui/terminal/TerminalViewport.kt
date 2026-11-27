package org.starfall.generative.features.machines.shared.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.terminal.RemoteTerminalBridge

@Composable
internal fun TerminalViewport(
    bridge: RemoteTerminalBridge?,
    keyboardInsets: WindowInsets,
    modifier: Modifier,
    extraKeys: @Composable () -> Unit,
    localInput: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
    terminal: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val keyboardOpen by remember(keyboardInsets, density) {
        derivedStateOf { keyboardInsets.getBottom(density) > 0 }
    }
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            PannableTerminalFrame(bridge, keyboardOpen, overlay, terminal)
            Box { localInput() }
            Box { extraKeys() }
        }
    ) { (frame, draft, keys), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val keyboardHeight = keyboardInsets.getBottom(this)
        val keyBar = keys.measure(Constraints(maxWidth = width, maxHeight = height))
        val gridHeight = height - keyBar.height
        val keyBarTop = (gridHeight - keyboardHeight).coerceAtLeast(0)
        val draftPanel = draft.measure(Constraints(maxWidth = width, maxHeight = keyBarTop))
        val visibleHeight = keyBarTop - if (keyboardHeight > 0) draftPanel.height else 0
        // The minimum height is the visible window. The maximum is the keyboard-free grid size.
        val terminalFrame = frame.measure(
            Constraints(width, width, minHeight = visibleHeight, maxHeight = gridHeight)
        )
        layout(width, height) {
            terminalFrame.placeRelative(0, 0)
            draftPanel.placeRelative(0, keyBarTop - draftPanel.height)
            keyBar.placeRelative(0, keyBarTop)
        }
    }
}

@Composable
private fun PannableTerminalFrame(
    bridge: RemoteTerminalBridge?,
    keyboardOpen: Boolean,
    overlay: @Composable BoxScope.() -> Unit,
    terminal: @Composable BoxScope.() -> Unit
) {
    val cursorBottom = if (keyboardOpen) bridge?.cursorBottomPx ?: 0 else 0
    val inputGeneration = if (keyboardOpen) bridge?.inputGeneration else null
    var manualPan by remember(bridge, keyboardOpen, inputGeneration) {
        mutableStateOf<Float?>(null)
    }
    var travel by remember { mutableIntStateOf(0) }
    var visibleHeight by remember { mutableIntStateOf(0) }
    fun offset(): Float = (manualPan ?: (cursorBottom - visibleHeight).toFloat()).coerceIn(0f, travel.toFloat())
    fun pan(delta: Float): Boolean {
        val current = offset()
        val next = (current + delta).coerceIn(0f, travel.toFloat())
        if (next == current) return false
        manualPan = next
        return true
    }

    Layout(
        modifier = Modifier.clipToBounds(),
        content = {
            Box(content = terminal)
            Box {
                overlay()
                if (keyboardOpen && bridge != null) {
                    val description = stringResource(R.string.remote_terminal_pan_frame)
                    Box(
                        Modifier.align(Alignment.CenterEnd)
                            .size(width = 32.dp, height = 72.dp)
                            .draggable(rememberDraggableState { pan(-it) }, Orientation.Vertical)
                            .semantics {
                                contentDescription = description
                                verticalScrollAxisRange = ScrollAxisRange({ offset() }, { travel.toFloat() })
                                scrollBy { _, y -> pan(y) }
                            },
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Box(
                            Modifier.size(width = 5.dp, height = 44.dp)
                                .background(MaterialTheme.colorScheme.outline, CircleShape)
                        )
                    }
                }
            }
        }
    ) { (grid, controls), constraints ->
        val width = constraints.maxWidth
        val fullHeight = constraints.maxHeight
        val clippedHeight = constraints.minHeight
        val terminalGrid = grid.measure(Constraints.fixed(width, fullHeight))
        val viewportControls = controls.measure(Constraints.fixed(width, clippedHeight))
        visibleHeight = clippedHeight
        travel = fullHeight - clippedHeight
        layout(width, clippedHeight) {
            terminalGrid.placeRelativeWithLayer(0, -offset().roundToInt())
            viewportControls.placeRelative(0, 0)
        }
    }
}
