@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.starfall.generative.features.home.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Uncontained Material loading indicator using its built-in default size. */
@Composable
internal fun GenerationLoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        LoadingIndicator(modifier = Modifier.testTag("generation-loading-indicator"))
    }
}
