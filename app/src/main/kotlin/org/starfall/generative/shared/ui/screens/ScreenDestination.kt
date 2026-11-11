package org.starfall.generative.shared.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import java.util.UUID

/** Only the foreground destination owns screen-level Back actions. */
val LocalScreenTransitionActive = compositionLocalOf { true }

/** Dialog windows owned by a screen can appear once its entrance has finished. */
val LocalScreenTransitionSettled = compositionLocalOf { true }

/** Implemented by the navigation host; features do not own a second stack or animation host. */
interface ScreenNavigator {
    fun open(id: String, context: CompositionLocalContext, onClosed: () -> Unit, content: @Composable () -> Unit)
    fun close(id: String)
}

val LocalScreenNavigator = compositionLocalOf<ScreenNavigator?> { null }

/**
 * Registers a feature-owned screen in the application's navigation back stack.
 * The parent stays composed at its own destination, retaining drafts and live callbacks. The
 * navigation host renders, animates and disposes the registered screen just like any other screen.
 * [onClosed] synchronizes the feature's selection when Navigation pops the destination directly.
 */
@Composable
fun <T : Any> ScreenDestination(
    page: T?,
    onClosed: () -> Unit,
    content: @Composable (T?) -> Unit,
) {
    val navigator = LocalScreenNavigator.current
    val context = currentCompositionLocalContext
    val currentPage by rememberUpdatedState(page)
    val currentContent by rememberUpdatedState(content)
    val currentOnClosed by rememberUpdatedState(onClosed)
    val id = rememberSaveable(page) { UUID.randomUUID().toString() }
    DisposableEffect(navigator, id, page) {
        if (page != null) {
            checkNotNull(navigator) { "ScreenDestination requires the application navigation host" }
            navigator.open(
                id = id,
                context = context,
                onClosed = { if (currentPage == page) currentOnClosed() },
                content = { currentContent(page) }
            )
        }
        onDispose { if (page != null) navigator?.close(id) }
    }
    content(null)
}
