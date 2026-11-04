package org.starfall.generative.shared.ui.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionActive

/**
 * Slides horizontally between a base sheet ([page] == null) and linked detail sheets while
 * smoothly expanding or contracting the sheet height to match the active content.
 */
@Composable
fun <T : Any> SlideSheetContent(
    page: T?,
    onBack: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (T?) -> Unit,
) {
    val parentActive = LocalScreenTransitionActive.current
    val basePageState = rememberSaveableStateHolder()
    val currentOnBack by rememberUpdatedState(onBack)

    BackHandler(enabled = parentActive && page != null) {
        currentOnBack()
    }
    AnimatedContent(
        targetState = page,
        contentKey = { it },
        contentAlignment = Alignment.TopStart,
        transitionSpec = {
            val initial = initialState
            val target = targetState
            val transitioning = initial != target
            val direction = when {
                target == null -> AnimatedContentTransitionScope.SlideDirection.Right
                initial == null -> AnimatedContentTransitionScope.SlideDirection.Left
                target is Enum<*> && initial is Enum<*> && target.ordinal < initial.ordinal ->
                    AnimatedContentTransitionScope.SlideDirection.Right
                else -> AnimatedContentTransitionScope.SlideDirection.Left
            }
            (slideIntoContainer(direction, tween(300)) togetherWith
                slideOutOfContainer(direction, tween(300))) using
                SizeTransform(clip = transitioning) { _, _ ->
                    if (transitioning) tween(300) else snap()
                }
        },
        modifier = modifier.fillMaxWidth(),
        label = label,
    ) { targetPage ->
        CompositionLocalProvider(
            LocalScreenTransitionActive provides (parentActive && targetPage == page)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopStart
            ) {
                if (targetPage == null) {
                    basePageState.SaveableStateProvider("base") { content(null) }
                } else {
                    content(targetPage)
                }
            }
        }
    }
}
