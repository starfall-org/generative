package org.starfall.generative.shared.ui.layout

import org.starfall.generative.shared.ui.inputs.InputFocusHost
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.starfall.generative.shared.ui.theme.modalScrimColor

/**
 * A full-window cover with a tappable scrim. Content is deliberately not click-consuming:
 * interactive media and controls consume their own gestures, while uncovered areas dismiss.
 */
@Composable
fun ScreenCover(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    scrimColor: Color = modalScrimColor(Color.Black.copy(alpha = 0.60f)),
    content: @Composable BoxScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        InputFocusHost(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
                .testTag("screen-cover")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = modifier, content = content)
        }
    }
}
