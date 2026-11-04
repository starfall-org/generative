package org.starfall.generative.shared.ui.screens

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Whether this page, including any nested editor, currently owns the system Back action. */
val LocalScreenTransitionActive = compositionLocalOf { true }

/** Keep the parent composed; dispose a closing editor only after its slide completes. */
@Composable
fun <T : Any> RetainedScreenContent(
    editor: T?,
    content: @Composable (T?) -> Unit,
) {
    val parentActive = LocalScreenTransitionActive.current
    val transition = updateTransition(targetState = editor, label = "editor-slide")
    val transitioning = transition.currentState != transition.targetState || transition.isRunning
    val parentOffset = transition.animateFloat(
        transitionSpec = { screenSlideSpec() }, label = "editor-parent"
    ) { page -> if (page == null) 0f else -1f }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .retainWhen(editor == null || (transitioning && transition.currentState == null))
                .screenSlide { parentOffset.value }
                .then(if (editor == null || transitioning) Modifier else Modifier.clearAndSetSemantics { })
        ) {
            CompositionLocalProvider(
                LocalScreenTransitionActive provides (parentActive && editor == null)
            ) { content(null) }
        }
        listOfNotNull(transition.currentState, transition.targetState).distinct().forEach { page ->
            key(page) {
                val offset = transition.animateFloat(
                    transitionSpec = {
                        if (transition.currentState != transition.targetState) screenSlideSpec() else snap()
                    }, label = "editor-child"
                ) { state ->
                    screenSlideOffset(
                        active = state == page,
                        incoming = page == transition.targetState,
                        popping = transition.targetState == null
                    )
                }
                val visible = page == editor || (transitioning && page == transition.currentState)
                Box(
                    Modifier.fillMaxSize().retainWhen(visible).screenSlide { offset.value }
                        .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
                ) {
                    CompositionLocalProvider(
                        LocalScreenTransitionActive provides (parentActive && page == editor)
                    ) { content(page) }
                }
            }
        }
    }
}

/** Hidden content stays composed but is not placed, drawn, or touchable. */
fun Modifier.retainWhen(visible: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        if (visible) placeable.place(0, 0)
    }
}
