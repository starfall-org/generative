package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.serverconnection.ui.McpCatalogEntry
import org.starfall.generative.features.serverconnection.ui.RemoteMcpScreen
import org.starfall.generative.features.serverconnection.ui.ProviderCatalogEntry
import org.starfall.generative.features.serverconnection.ui.RemoteProvidersScreen
import org.starfall.generative.features.serverconnection.ui.ProviderIntegrationScreen
import org.starfall.generative.features.serverconnection.ui.RemoteProviderEditor
import org.starfall.generative.integrations.opencode.OpenCodeProviderConfigFile
import org.starfall.generative.features.serverconnection.ui.McpRuntimeEditor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ServerCatalogScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerCatalogExposesImportAction() {
        var calls = 0
        compose.setScreenContent {
            MaterialTheme {
                RemoteProvidersScreen(catalog = emptyList(), onBack = {}, onImport = { calls++ })
            }
        }
        compose.onNodeWithContentDescription("Import from local").performClick()
        assertEquals(1, calls)
    }

    @Test fun mcpCatalogExposesImportAction() {
        var calls = 0
        compose.setScreenContent {
            MaterialTheme {
                RemoteMcpScreen(catalog = emptyList(), busy = false, onBack = {}, onImport = { calls++ })
            }
        }
        compose.onNodeWithContentDescription("Import from local").performClick()
        assertEquals(1, calls)
    }

    @Test fun providersDisplayBackendDetailsWithoutLocalEditingActions() {
        compose.setScreenContent {
            MaterialTheme {
                RemoteProvidersScreen(catalog = listOf(ProviderCatalogEntry("remote-id", "Remote provider", "custom-package")), onBack = {})
            }
        }
        compose.onNodeWithTag("provider_list").assertExists()
        compose.onNodeWithText("Remote provider").assertHasNoClickAction()
        compose.onNodeWithText("custom-package").assertExists()
        compose.onNodeWithTag("list-screen-view-toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remote provider").assertExists()
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
    }

    @Test fun providerIntegrationActionUsesServerIntegrationIdentity() {
        val calls = mutableListOf<String>()
        compose.setScreenContent {
            MaterialTheme {
                RemoteProvidersScreen(catalog = listOf(ProviderCatalogEntry("provider-id", "Remote provider", "pkg", "integration-id")),
                    onBack = {}, onConnect = { calls += it })
            }
        }
        compose.onNodeWithText("Remote provider").performClick()
        assertEquals(listOf("integration-id"), calls)
    }

    @Test fun mcpConnectActionCallsHostWithServerIdentity() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        compose.setScreenContent {
            MaterialTheme {
                RemoteMcpScreen(catalog = listOf(McpCatalogEntry("remote-id", "Remote tools", "disabled")),
                    busy = false, onToggle = { id, connected -> calls += id to connected }, onBack = {})
            }
        }
        compose.onNodeWithTag("mcp_list").assertExists()
        compose.onNodeWithText("Remote tools").assertHasNoClickAction()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Connect").performClick()
        assertEquals(listOf("remote-id" to true), calls)
    }

    @Test fun mcpAddAndDeleteAreOfferedOnlyWhenHostProvidesThem() {
        val calls = mutableListOf<String>()
        compose.setScreenContent {
            MaterialTheme {
                RemoteMcpScreen(catalog = listOf(McpCatalogEntry("remote-id", "Remote tools", "connected")),
                    busy = false, onToggle = { _, _ -> }, onBack = {},
                    onAdd = { calls += "add" }, onDelete = { calls += "delete:$it" })
            }
        }
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("add", "delete:remote-id"), calls)
    }

    @Test fun loadingInventoryHidesCatalogActions() {
        compose.setScreenContent {
            MaterialTheme {
                RemoteMcpScreen(catalog = emptyList(), busy = false, onBack = {},
                    onAdd = { error("Loading inventory cannot add") }, status = { Text("Loading") })
            }
        }
        compose.onNodeWithText("Loading").assertExists()
        compose.onNodeWithContentDescription("Add").assertDoesNotExist()
        compose.onNodeWithTag("list-screen-view-toggle").assertDoesNotExist()
        compose.onNodeWithTag("list-screen-search-button").assertDoesNotExist()
    }

    @Test fun emptyInventoryKeepsSharedCatalogControlsAndProjectHeader() {
        compose.setScreenContent {
            MaterialTheme {
                RemoteMcpScreen(catalog = emptyList(), busy = false, onBack = {},
                    header = { Text("Selected project") })
            }
        }
        compose.onNodeWithText("Selected project").assertExists()
        compose.onNodeWithTag("list-screen-view-toggle").assertExists()
        compose.onNodeWithTag("list-screen-search-button").assertExists()
        compose.onNodeWithContentDescription("Add").assertDoesNotExist()
        compose.onNodeWithContentDescription("Import from local").assertDoesNotExist()
    }

    @Test fun runtimeEditorUsesSharedMcpConnectionFormAndSavesServerConfig() {
        val saves = mutableListOf<Pair<String, JsonObject>>()
        compose.setScreenContent {
            MaterialTheme {
                McpRuntimeEditor(existingNames = emptySet(), busy = false, failed = false,
                    onBack = {}, onSave = { name, config -> saves += name to config })
            }
        }
        compose.onNodeWithTag("mcp-connection-settings").assertExists()
        compose.onNodeWithTag("mcp_name").performTextInput("runtime")
        compose.onNodeWithTag("mcp_url").performTextInput("https://example.com/mcp")
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("runtime" to Json.parseToJsonElement("""{"type":"remote","url":"https://example.com/mcp"}""").jsonObject), saves)
    }

    @Test fun addProviderUsesSharedConfigurationFieldsAndSavesCustomProvider() {
        val saves = mutableListOf<Pair<String, JsonObject>>()
        compose.setScreenContent {
            MaterialTheme {
                RemoteProviderEditor(OpenCodeProviderConfigFile("/home/server/.config/opencode/opencode.json", true),
                    existingIds = emptySet(), busy = false, failed = false, insecure = false,
                    onBack = {}, onSave = { id, config -> saves += id to config })
            }
        }
        compose.onNodeWithTag("provider-configuration-form").assertExists()
        compose.onNodeWithTag("provider-type-selector").assertExists()
        compose.onNodeWithTag("provider_name").performTextInput("My Gateway")
        compose.onNodeWithTag("remote_provider_id").performTextInput("my-gateway")
        compose.onNodeWithTag("provider_api_key").performTextInput("server-key")
        compose.onNodeWithTag("remote_provider_models").performScrollTo().performTextInput("model-a")
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("my-gateway" to Json.parseToJsonElement(
            """{"name":"My Gateway","package":"@opencode/ai/providers/openai-compatible","settings":{"baseURL":"https://api.openai.com/v1","apiKey":"server-key"},"models":{"model-a":{}}}"""
        ).jsonObject), saves)
    }

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
