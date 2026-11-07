package org.starfall.generative

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.dialogs.ErrorDialog
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ErrorDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun displaysErrorDetailsAndDismissesFromFooter() {
        var dismissed = false
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ErrorDialog("Daemon is unavailable", { dismissed = true }, title = "Serverside CLI")
            }
        }
        compose.onNodeWithText("Serverside CLI").assertIsDisplayed()
        compose.onNodeWithText("Daemon is unavailable").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
