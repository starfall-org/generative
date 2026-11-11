package org.starfall.generative.navigation

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.mutableStateMapOf
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import org.starfall.generative.shared.ui.screens.ScreenNavigator

internal const val RegisteredScreenRoute = "screen/{screenId}"

/** Feature-owned content, hosted as real destinations in the one application back stack. */
@SuppressLint("RestrictedApi") // The retained host also needs hidden back-stack entries.
internal class RegisteredScreens(private val navController: NavHostController) : ScreenNavigator {
    class Screen(val context: CompositionLocalContext, val onClosed: () -> Unit, val content: @Composable () -> Unit) {
        var closed = false
    }

    private val screens = mutableStateMapOf<String, Screen>()

    override fun open(id: String, context: CompositionLocalContext, onClosed: () -> Unit, content: @Composable () -> Unit) {
        screens[id] = Screen(context, onClosed, content)
        // A restored destination is already present; do not push it a second time.
        if (navController.currentBackStack.value.none { it.screenId == id }) {
            navController.navigate("screen/$id")
        }
    }

    override fun close(id: String) {
        if (navController.currentBackStack.value.any { it.screenId == id }) {
            navController.popBackStack("screen/$id", inclusive = true)
        }
    }

    fun screen(entry: NavBackStackEntry): Screen? = screens[entry.screenId]

    fun synchronize(retained: List<NavBackStackEntry>) {
        // Registration effects can navigate before the host collects its next stack snapshot.
        val entries = navController.currentBackStack.value
        val activeIds = entries.mapNotNull { it.screenId }.toSet()
        screens.toMap().forEach { (id, screen) ->
            if (id !in activeIds && !screen.closed) {
                screen.closed = true
                screen.onClosed()
            }
        }
        val retainedIds = (entries + retained).mapNotNull { it.screenId }.toSet()
        screens.keys.toList().filterNot { it in retainedIds }.forEach(screens::remove)
        // Transient drafts are deliberately not restored. A saved Navigation entry without its
        // feature-owned draft must return to its parent instead of displaying an empty screen.
        entries.lastOrNull()?.let { entry ->
            val id = entry.screenId
            if (id != null && id !in screens) close(id)
        }
    }
}

private val NavBackStackEntry.screenId: String?
    get() = if (destination.route == RegisteredScreenRoute) arguments?.getString("screenId") else null
