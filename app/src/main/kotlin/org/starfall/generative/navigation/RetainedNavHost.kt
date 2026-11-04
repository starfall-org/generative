package org.starfall.generative.navigation

import android.annotation.SuppressLint
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.LocalOwnersProvider
import androidx.navigation.compose.composable as navComposable
import androidx.navigation.NavHostController
import androidx.navigation.createGraph
import org.starfall.generative.shared.ui.screens.retainWhen
import org.starfall.generative.shared.ui.screens.screenSlide
import org.starfall.generative.shared.ui.screens.screenSlideOffset
import org.starfall.generative.shared.ui.screens.screenSlideSpec

/**
 * Keeps the full back stack composed (including nonvisible entries), which is
 * required to retain screens across navigation transitions. The public
 * visibleEntries stream omits hidden back-stack entries, so it cannot replace
 * currentBackStack without losing the retained-screen behavior.
 */
@SuppressLint("RestrictedApi") // Navigation offers no public full-back-stack stream.
@Composable
internal fun RetainedNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    menuBackground: (@Composable () -> Unit)? = null,
    builder: RetainedNavGraphBuilder.() -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val dispatcherOwner = LocalOnBackPressedDispatcherOwner.current
    navController.setLifecycleOwner(lifecycleOwner)
    navController.setViewModelStore(viewModelStoreOwner.viewModelStore)
    dispatcherOwner?.let { navController.setOnBackPressedDispatcher(it.onBackPressedDispatcher) }

    val graph = remember(navController, startDestination) {
        navController.createGraph(startDestination = startDestination) {
            RetainedNavGraphBuilder(this, mutableMapOf()).builder()
        }
    }
    navController.graph = graph
    // Refresh callbacks from the current composition without resetting Navigation state.
    val screens = remember { mutableMapOf<String, @Composable (NavBackStackEntry) -> Unit>() }
    RetainedNavGraphBuilder(null, screens).builder()

    val entries by navController.currentBackStack.collectAsState()
    val visibleEntries by navController.visibleEntries.collectAsState()
    val navigator = remember(navController) {
        navController.navigatorProvider.getNavigator(ComposeNavigator::class.java)
    }
    val current = entries.lastOrNull { it.destination is ComposeNavigator.Destination }
    // Exiting pop entries remain in visibleEntries until this host finishes the animation.
    val retainedEntries = (entries + visibleEntries).distinctBy { it.id }
        .filter { it.destination is ComposeNavigator.Destination }
    val transition = updateTransition(targetState = current?.id, label = "screen-slide")
    val transitioning = transition.currentState != transition.targetState || transition.isRunning
    val fromRoute = retainedEntries.firstOrNull { it.id == transition.currentState }?.destination?.route
    val toRoute = retainedEntries.firstOrNull { it.id == transition.targetState }?.destination?.route
    val moveMenuBackground = movesMenuBackground(fromRoute, toRoute)
    val fromIndex = retainedEntries.indexOfFirst { it.id == transition.currentState }
    val toIndex = retainedEntries.indexOfFirst { it.id == transition.targetState }
    val returning = fromIndex >= 0 && toIndex >= 0 && fromIndex > toIndex
    // The shared Menu backdrop travels with Menu only on the direct Chat <-> Menu edge.
    // All other destinations slide over the same stationary backdrop.
    val chatEntryId = retainedEntries.firstOrNull { it.destination.route == "chat" }?.id
    val backgroundOffset = transition.animateFloat(
        transitionSpec = { if (moveMenuBackground) screenSlideSpec() else snap() },
        label = "menu-background-slide"
    ) { id -> if (id == chatEntryId) 1f else 0f }
    // The navigation library owns popped-entry lifetimes until its transition is complete.
    LaunchedEffect(transitioning, visibleEntries) {
        if (!transitioning) visibleEntries.filter { it.destination is ComposeNavigator.Destination }
            .forEach(navigator::onTransitionComplete)
    }

    val holder = rememberSaveableStateHolder()
    Box(modifier.fillMaxSize()) {
        // One persistent background for the Menu family. Only Chat <-> Menu slides it;
        // navigating between Menu-family pages slides the screen content alone.
        if (menuBackground != null) {
            Box(Modifier.fillMaxSize()
                .retainWhen(current?.destination?.route != "chat" || transitioning)
                .screenSlide { if (moveMenuBackground) backgroundOffset.value else 0f }) {
                menuBackground()
            }
        }
        retainedEntries.forEach { entry ->
            val content = screens[entry.destination.route] ?: return@forEach
            key(entry.id) {
                val offset = transition.animateFloat(
                    transitionSpec = {
                        if (transition.currentState != transition.targetState) screenSlideSpec() else snap()
                    },
                    label = "screen-${entry.id}"
                ) { id ->
                    screenSlideOffset(
                        active = id == entry.id,
                        incoming = entry.id == transition.targetState,
                        popping = returning
                    )
                }
                val visible = entry.id == current?.id ||
                    (transitioning && entry.id == transition.currentState)
                Box(Modifier.fillMaxSize().retainWhen(visible).screenSlide { offset.value }) {
                    entry.LocalOwnersProvider(holder) { content(entry) }
                }
            }
        }
    }
}

/** Only the direct Home <-> App Menu transition slides the shared Menu photo. */
internal fun movesMenuBackground(fromRoute: String?, toRoute: String?): Boolean =
    (fromRoute == "chat" && toRoute == "features") ||
        (fromRoute == "features" && toRoute == "chat")

internal class RetainedNavGraphBuilder(
    private val graph: NavGraphBuilder?,
    private val screens: MutableMap<String, @Composable (NavBackStackEntry) -> Unit>
) {
    fun composable(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        content: @Composable (NavBackStackEntry) -> Unit
    ) {
        graph?.navComposable(route = route, arguments = arguments) { }
        screens[route] = content
    }
}
