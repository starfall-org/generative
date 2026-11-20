package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.serverconnection.ui.ServerConnectionEditor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ServerConnectionProgramUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transportChoiceAppearsOnlyForGenerativeServer() {
        compose.setContent {
            MaterialTheme {
                ServerConnectionEditor(
                    profile = null, machines = emptyList(), busy = false, repairing = false,
                    onBack = {}, onSave = { _, _, _, _, _, _, _, _, _ -> }, error = null,
                )
            }
        }
        compose.onNodeWithText("Connection protocol").assertExists()
        compose.onNodeWithText("Model provider source").assertExists()
        compose.onNodeWithText("Generative Server").performClick()
        compose.onNodeWithText("OpenCode").performClick()
        compose.onNodeWithText("Connection protocol").assertDoesNotExist()
        compose.onNodeWithText("Model provider source").assertDoesNotExist()
        compose.onNodeWithText("Authentication").assertExists()
        compose.onNodeWithText("OpenCode").performClick()
        compose.onNodeWithText("Generative Server").performClick()
        compose.onNodeWithText("Connection protocol").assertExists()
        compose.onNodeWithText("Model provider source").assertExists()
    }
}
