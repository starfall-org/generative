package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.ui.screens.ListScreenTemplate
import org.starfall.generative.features.mcp.list.ui.ServerListScreen
import org.starfall.generative.features.speech.ui.SpeechServicesScreen
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.ToolSettings

class ListScreenTemplateDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun defaultActionsSitBetweenCustomActionAndTrailingSettings() {
        compose.setContent {
            EnglishTestTheme {
                var grid by remember { mutableStateOf(false) }
                ListScreenTemplate(
                    title = "Collection", onBack = {}, searchQuery = "", onSearchQueryChange = {},
                    isGrid = grid, onGridChange = { grid = it },
                    primaryActions = {
                        IconButton(onClick = {}, modifier = Modifier.testTag("custom-header-action")) {
                            Text("+")
                        }
                    },
                    trailingActions = {
                        IconButton(onClick = {}, modifier = Modifier.testTag("trailing-settings-action")) {
                            Text("Settings")
                        }
                    }
                ) { _ -> Text("Rows") }
            }
        }
        val custom = compose.onNodeWithTag("custom-header-action").fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNodeWithTag("list-screen-view-toggle").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("list-screen-search-button").fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithTag("trailing-settings-action").fetchSemanticsNode().boundsInRoot
        assertTrue(custom.center.x < search.center.x)
        assertTrue(search.center.x < toggle.center.x)
        assertTrue(toggle.center.x < settings.center.x)
        compose.onNodeWithTag("list-screen-view-toggle").performClick()
        compose.onNodeWithContentDescription("Switch to List View").assertIsDisplayed()
        compose.onNodeWithText("Rows").assertIsDisplayed()
    }

    @Test fun searchQueryFiltersContentAndCanBeCleared() {
        compose.setContent {
            EnglishTestTheme {
                var query by remember { mutableStateOf("") }
                ListScreenTemplate(
                    title = "Collection", onBack = {}, searchQuery = query,
                    onSearchQueryChange = { query = it }, isGrid = false,
                    onGridChange = {}
                ) { _ ->
                    if ("First".contains(query, ignoreCase = true)) Text("First")
                    if ("Second".contains(query, ignoreCase = true)) Text("Second")
                }
            }
        }
        compose.onNodeWithTag("list-screen-search-button").performClick()
        compose.onNodeWithTag("list-screen-search").assertIsDisplayed()
        compose.onNodeWithText("Collection").assertDoesNotExist()
        compose.onNodeWithTag("list-screen-view-toggle").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("list-screen-search")
            .fetchSemanticsNode().boundsInRoot.width > 100f)
        compose.onNodeWithTag("list-screen-search").performTextInput("Second")
        compose.onNodeWithText("Second").assertIsDisplayed()
        compose.onNodeWithText("First").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear").performClick()
        compose.onNodeWithText("First").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Collection").assertIsDisplayed()
        compose.onNodeWithTag("list-screen-search-button").assertIsDisplayed()
        compose.onNodeWithTag("list-screen-view-toggle").assertIsDisplayed()
    }
    @Test fun mcpSearchFiltersRealServerCards() {
        compose.setContent {
            EnglishTestTheme {
                ServerListScreen(
                    mcpServers = listOf(McpInfo("one", "Alpha MCP"), McpInfo("two", "Beta MCP")),
                    toolsCache = emptyMap(), toolErrors = emptyMap(), toolsLoading = emptySet(),
                    toolSettings = ToolSettings(), onSetToolEnabled = { _, _, _ -> },
                    onSetMcpEnabled = { _, _ -> }, onDeleteMcpServer = {},
                    onReorderMcpServers = {}, onOpenEditor = { _, _ -> }, onBack = {}
                )
            }
        }
        compose.onNodeWithTag("list-screen-search-button").performClick()
        compose.onNodeWithTag("list-screen-search").performTextInput("Beta")
        compose.onNodeWithText("Beta MCP").assertIsDisplayed()
        compose.onNodeWithText("Alpha MCP").assertDoesNotExist()
    }

    @Test fun speechSearchFiltersRealServiceCards() {
        compose.setContent {
            EnglishTestTheme {
                SpeechServicesScreen(
                    speechServices = listOf(SpeechService("one", "Alpha Voice"), SpeechService("two", "Beta Voice")),
                    providers = emptyList(), selectedSpeechServiceId = null,
                    onSetTtsProvider = {}, onSaveService = {}, onDeleteService = {},
                    onReorderServices = {}, onTestVoice = { _, _ -> }, onBack = {}
                )
            }
        }
        compose.onNodeWithTag("list-screen-search-button").performClick()
        compose.onNodeWithTag("list-screen-search").performTextInput("Beta")
        compose.onNodeWithText("Beta Voice").assertIsDisplayed()
        compose.onNodeWithText("Alpha Voice").assertDoesNotExist()
    }

}
