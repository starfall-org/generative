package org.starfall.generative.features.home.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.starfall.generative.features.home.shared.ui.ToolDetailsSheetHost
import org.starfall.generative.features.home.shared.ui.ToolSheetState
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionSettled

/** Suspend the current tool sheet while its code preview is foreground, and reset for other navigation. */
@Composable
internal fun rememberChatToolDetails(messageId: String, activities: List<ToolActivity>): ToolSheetState {
    val foreground = LocalChatForeground.current
    val keep = foreground || LocalChatKeepOverlayState.current
    val state = remember(messageId, keep) { ToolSheetState() }
    if (keep) ToolDetailsSheetHost(
        state, activities, visible = foreground && LocalScreenTransitionSettled.current
    )
    return state
}
