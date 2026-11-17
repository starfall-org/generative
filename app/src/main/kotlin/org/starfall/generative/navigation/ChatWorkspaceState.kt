package org.starfall.generative.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder

/** Keeps composer and viewport state scoped to the selected chat workspace and paired identity. */
@Composable
internal fun ChatWorkspaceState(
    holder: SaveableStateHolder,
    remote: Boolean,
    workspaceKey: String?,
    content: @Composable () -> Unit,
) {
    holder.SaveableStateProvider(if (remote) "remote:$workspaceKey" else "local", content)
}
