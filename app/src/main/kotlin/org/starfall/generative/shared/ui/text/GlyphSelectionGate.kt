package org.starfall.generative.shared.ui.text

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer as ComposeSelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Text selection normally starts anywhere inside a selection container, including the blank space around
 * and between lines. Containers that provide a registry (the user message bubble) only let a long press
 * select when it lands on laid-out text; elsewhere the long press is left to the message menu.
 */
internal class GlyphHitRegistry {
    var owner: LayoutCoordinates? = null
    private val targets = mutableListOf<GlyphTarget>()

    fun add(target: GlyphTarget) { targets += target }
    fun remove(target: GlyphTarget) { targets -= target }

    /** [position] is in the owner's coordinate space; [slop] forgives a fingertip that lands just beside a glyph. */
    fun hit(position: Offset, slop: Float): Boolean {
        val ownerCoordinates = owner?.takeIf { it.isAttached } ?: return false
        return targets.any { target ->
            val coordinates = target.coordinates?.takeIf { it.isAttached } ?: return@any false
            val layout = target.layout ?: return@any false
            val local = coordinates.localPositionOf(ownerCoordinates, position)
            if (layout.lineCount == 0) return@any false
            val line = layout.getLineForVerticalPosition(local.y.coerceIn(0f, (layout.size.height - 1).coerceAtLeast(0).toFloat()))
            local.y >= layout.getLineTop(line) - slop && local.y <= layout.getLineBottom(line) + slop &&
                local.x >= layout.getLineLeft(line) - slop && local.x <= layout.getLineRight(line) + slop
        }
    }
}

internal class GlyphTarget {
    var coordinates: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
}

internal val LocalGlyphHitRegistry = staticCompositionLocalOf<GlyphHitRegistry?> { null }

/** True while a long press on blank space must not start text selection. */
internal val LocalSelectionBlocked = compositionLocalOf { false }

/**
 * Prevents nested SelectionContainers from fragmenting Select all into independent text islands.
 * Message-level containers set this for all markdown descendants.
 */
private val LocalSelectionContainerActive = compositionLocalOf { false }

@Composable
internal fun rememberGlyphTarget(): GlyphTarget? {
    val registry = LocalGlyphHitRegistry.current ?: return null
    val target = remember(registry) { GlyphTarget() }
    DisposableEffect(registry, target) {
        registry.add(target)
        onDispose { registry.remove(target) }
    }
    return target
}

/** Place last in a Text's modifier chain so the coordinates match its text layout. */
internal fun Modifier.glyphTarget(target: GlyphTarget?): Modifier =
    if (target == null) this else onGloballyPositioned { target.coordinates = it }

/**
 * Selection container that turns selection off while [LocalSelectionBlocked] is set and reuses an
 * ancestor message-level container when one exists. Nested SelectionContainers create separate
 * selection registrars, making Select all stop at an individual paragraph or chunk.
 */
@Composable
internal fun GatedSelectionContainer(content: @Composable () -> Unit) {
    when {
        LocalSelectionBlocked.current -> DisableSelection(content = content)
        LocalSelectionContainerActive.current -> content()
        else -> SelectionContainer {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalSelectionContainerActive provides true,
                content = content
            )
        }
    }
}

/** A deliberate selection island, used for reasoning text that is separate from answer content. */
@Composable
internal fun SelectionContainer(content: @Composable () -> Unit) {
    val selections = LocalReadOnlySelectionStates.current
    if (selections == null) {
        ComposeSelectionContainer(content = content)
        return
    }
    val state = remember { SelectionState() }
    DisposableEffect(selections, state) {
        selections.add(state)
        onDispose { selections.remove(state) }
    }
    ComposeSelectionContainer(state = state, content = content)
}

/**
 * Adds copy/select text for a visual message block without drawing another visible line.
 * The surrounding message-level selection container still sees this text in source order.
 */
@Composable
internal fun SelectionOnlyPlaceholder(text: String) {
    androidx.compose.material3.Text(
        text = text,
        color = Color.Transparent,
        fontSize = 0.sp,
        lineHeight = 0.sp,
        modifier = Modifier.height(0.dp)
    )
}

/**
 * Watches (without consuming anything, so scrolling and clicks are untouched) for a press on blank space
 * that stays put until just before the long-press timeout, and reports it through [onBlocked] so selection
 * is off by the time the selection long press would fire. Selection comes back when the finger lifts.
 */
internal fun Modifier.glyphSelectionGate(registry: GlyphHitRegistry, onBlocked: (Boolean) -> Unit): Modifier =
    pointerInput(registry) {
        val slop = 6.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (registry.hit(down.position, slop)) return@awaitEachGesture
            val blockAfter = (viewConfiguration.longPressTimeoutMillis - 150L).coerceAtLeast(50L)
            val settled = withTimeoutOrNull(blockAfter) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
            }
            if (settled == null) {
                onBlocked(true)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.fastAll { !it.pressed }) break
                    }
                } finally {
                    onBlocked(false)
                }
            }
        }
    }
