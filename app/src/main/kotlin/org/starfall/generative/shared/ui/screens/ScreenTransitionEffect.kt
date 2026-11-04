package org.starfall.generative.shared.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Direction used by [ScreenTransitionEffect] for a secondary-screen change. */
enum class ScreenTransitionDirection {
    /** A screen pushed onto the back stack: right to left. */
    FORWARD,

    /** A screen popped from the back stack: left to right. */
    BACK
}

/**
 * Shared transition for screen-local navigation.
 *
 * Navigation remains owned by the caller. Supplying [onBack] also installs the same callback for
 * the system Back action, so a screen does not need to duplicate its gesture and button wiring.
 * [contentKey] keeps a page's composition stable while a different page is animated in.
 */
@Composable
fun <T> ScreenTransitionEffect(
    targetState: T,
    direction: ScreenTransitionDirection,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    contentKey: (T) -> Any,
    content: @Composable (T) -> Unit
) {
    onBack?.let { BackHandler(enabled = LocalScreenTransitionActive.current, onBack = it) }
    val stateHolder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = { screenTransition(direction) },
        contentKey = contentKey,
        label = "screen-transition"
    ) { state ->
        stateHolder.SaveableStateProvider(contentKey(state)) {
            content(state)
        }
    }
}

private fun screenTransition(direction: ScreenTransitionDirection): ContentTransform =
    when (direction) {
        ScreenTransitionDirection.FORWARD ->
            slideInHorizontally(initialOffsetX = { it }) togetherWith
                slideOutHorizontally(targetOffsetX = { -it })
        ScreenTransitionDirection.BACK ->
            slideInHorizontally(initialOffsetX = { -it }) togetherWith
                slideOutHorizontally(targetOffsetX = { it })
    }

/** Publicly testable transition shape for callers that need to combine additional effects. */
internal fun screenTransitionEnter(direction: ScreenTransitionDirection): EnterTransition =
    when (direction) {
        ScreenTransitionDirection.FORWARD -> slideInHorizontally(initialOffsetX = { it })
        ScreenTransitionDirection.BACK -> slideInHorizontally(initialOffsetX = { -it })
    }

internal fun screenTransitionExit(direction: ScreenTransitionDirection): ExitTransition =
    when (direction) {
        ScreenTransitionDirection.FORWARD -> slideOutHorizontally(targetOffsetX = { -it })
        ScreenTransitionDirection.BACK -> slideOutHorizontally(targetOffsetX = { it })
    }

/** Full-width transition used by the retained navigation host. */
internal fun screenSlideSpec(): FiniteAnimationSpec<Float> =
    tween(380, easing = FastOutSlowInEasing)

/** Forward: incoming from right, outgoing to left. Pop: the opposite. */
internal fun screenSlideOffset(active: Boolean, incoming: Boolean, popping: Boolean): Float = when {
    active -> 0f
    incoming -> if (popping) -1f else 1f
    else -> if (popping) 1f else -1f
}

internal fun Modifier.screenSlide(offset: () -> Float): Modifier = graphicsLayer {
    translationX = size.width * offset()
}
