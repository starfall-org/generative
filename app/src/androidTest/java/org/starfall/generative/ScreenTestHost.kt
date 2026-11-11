package org.starfall.generative

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.navigation.compose.rememberNavController
import org.starfall.generative.navigation.RetainedNavHost

/** Exercise feature screens with the same destination host used by the application. */
internal fun ComposeContentTestRule.setScreenContent(content: @Composable () -> Unit) {
    setContent {
        RetainedNavHost(rememberNavController(), startDestination = "test-root") {
            composable("test-root") { content() }
        }
    }
}
