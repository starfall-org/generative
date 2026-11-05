package org.starfall.generative.shared.ui.screens

import androidx.compose.runtime.Composable

/** Chat is the root screen; it owns its own app bar and scrolling content. */
@Composable
fun MainScreenTemplate(content: @Composable () -> Unit) {
    content()
}
