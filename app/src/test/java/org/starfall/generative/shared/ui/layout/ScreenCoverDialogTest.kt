package org.starfall.generative.shared.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionActive

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = org.starfall.generative.LibraryTestApplication::class)
class ScreenCoverDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullScreenCoverDismissesThroughItsScrim() {
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible) ScreenCover(onDismiss = { visible = false }) {
                    Box(Modifier.size(40.dp))
                }
            }
        }
        compose.onNodeWithTag("screen-cover").assertIsDisplayed().performClick()
        compose.onNodeWithTag("screen-cover").assertDoesNotExist()
    }

    @Test fun coverHidesWhileAnotherScreenIsActiveAndReturnsWithItsOwner() {
        var active by mutableStateOf(true)
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalScreenTransitionActive provides active) {
                    if (visible) ScreenCover(onDismiss = { visible = false }) {
                        Box(Modifier.size(40.dp))
                    }
                }
            }
        }
        compose.onNodeWithTag("screen-cover").assertIsDisplayed()
        compose.runOnIdle { active = false }
        compose.onNodeWithTag("screen-cover").assertDoesNotExist()
        compose.runOnIdle { active = true }
        compose.onNodeWithTag("screen-cover").assertIsDisplayed().performClick()
        compose.onNodeWithTag("screen-cover").assertDoesNotExist()
    }
}
