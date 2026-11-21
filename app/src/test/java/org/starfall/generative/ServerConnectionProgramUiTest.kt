package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.repositories.ServerConnectionProfile
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
                    onBack = {}, onSave = {}, error = null,
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

    @Test fun savedOpenCodeUsesItsExistingEndpointWhenUpdatingAccess() {
        val profile = ServerConnectionProfile(
            id = "work", name = "Work", endpoint = "https://example.com/", pin = "",
            clientId = "opencode", serverId = "work", selected = false, transport = "opencode",
        )
        var savedEndpoint: String? = null
        compose.setContent {
            MaterialTheme {
                ServerConnectionEditor(
                    profile = profile, machines = emptyList(), busy = false, repairing = false,
                    onBack = {},
                    onSave = { savedEndpoint = it.endpoint },
                    error = null,
                    catalogContent = { Text("Catalog shortcut") },
                )
            }
        }

        compose.onNodeWithText(profile.endpoint).assertExists()
        compose.onNodeWithText("Server Program").assertDoesNotExist()
        compose.onNodeWithText("Server URL").assertDoesNotExist()
        compose.onNodeWithText("Catalog shortcut").assertExists()
        compose.onNodeWithContentDescription("Save").assertIsEnabled()

        compose.onNode(hasSetTextAction() and hasText("Password")).performTextInput("new-password")
        compose.onNodeWithText("Catalog shortcut").assertDoesNotExist()
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle { assertEquals(profile.endpoint, savedEndpoint) }
    }
}
