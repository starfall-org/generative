@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.home.chat.ui.GenerationLoadingIndicator

/** Verify that chat generation uses the uncontained Material indicator without a logo. */
class GenerationLoadingIndicatorDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun generationIndicatorHasNoLogoOrContainerAndDisappearsWhenStopped() {
        var generating by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (generating) GenerationLoadingIndicator()
            }
        }

        val indicator = compose.onNodeWithTag("generation-loading-indicator", useUnmergedTree = true)
        indicator.assertIsDisplayed()
        compose.onNodeWithTag("generation-model-logo", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("generation-logo-fallback", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("generation-logo-mask", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("generation-indicator-container", useUnmergedTree = true).assertDoesNotExist()

        compose.runOnIdle { generating = false }
        indicator.assertDoesNotExist()
    }
}
