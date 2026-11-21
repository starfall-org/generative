package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.serverconnection.ui.ProviderIntegrationScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ServerCatalogScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerIntegrationUsesSharedEditorAndCredentialField() {
        val keys = mutableListOf<String>()
        compose.setScreenContent {
            MaterialTheme {
                ProviderIntegrationScreen(
                    integration = Json.parseToJsonElement("""{"name":"Remote integration","methods":[{"type":"key","label":"API key"}]}""").jsonObject,
                    onBack = {}, connectKey = { key, _ -> keys += key },
                    beginOAuth = { _, _ -> error("Not supported") }, oauthStatus = { error("Not supported") },
                    completeOAuth = { _, _ -> error("Not supported") }, cancelOAuth = { error("Not supported") })
            }
        }
        compose.onNodeWithTag("provider-configuration-form").assertExists()
        compose.onNodeWithTag("provider_api_key").performTextInput("server-key")
        compose.onNodeWithText("Connect").performClick()
        compose.waitForIdle()
        assertEquals(listOf("server-key"), keys)
    }

}
