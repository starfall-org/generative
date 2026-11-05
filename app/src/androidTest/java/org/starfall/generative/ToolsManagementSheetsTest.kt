package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.home.shared.ui.FeatureToolsSheet
import org.starfall.generative.features.home.shared.ui.McpConnectorsSheet
import org.starfall.generative.features.home.shared.ui.QuickActionsSheet
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.features.home.shared.ui.LocalToolControls
import org.starfall.generative.features.home.shared.ui.ToolControls

class ToolsManagementSheetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun quickActionsOpenFeatureToolsAndMcpConnectorsSeparately() {
        var featureToolsOpened = false
        var mcpConnectorsOpened = false
        compose.setContent {
            MaterialTheme {
                QuickActionsSheet(
                    onPickImage = {},
                    onPickDocument = {},
                    onTakePhoto = {},
                    onCreateImage = {},
                    onCreateVideo = {},
                    onOpenFeatureTools = { featureToolsOpened = true },
                    onOpenMcpConnectors = { mcpConnectorsOpened = true },
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText("Feature Tools").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(featureToolsOpened)
            assertTrue(!mcpConnectorsOpened)
        }
        compose.onNodeWithText("MCP Connectors").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(mcpConnectorsOpened) }
    }

    @Test
    fun featureToolsSheetContainsBuiltInToolSwitches() {
        val updates = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalToolControls provides ToolControls(
                        settings = ToolSettings(),
                        setSystem = { name, config -> updates.add(name to config.enabled) },
                        setSendFile = { updates.add("send_file" to it) },
                        setAgentSkillsSearch = { updates.add("agent_skills_search" to it) }
                    )
                ) {
                    FeatureToolsSheet(onDismiss = {})
                }
            }
        }

        compose.onNodeWithText("Feature Tools").assertIsDisplayed()
        compose.onNodeWithText("Image Generation").assertIsDisplayed()
        compose.onNodeWithText("Video Generation").assertIsDisplayed()
        compose.onNodeWithText("MCP Connectors").assertDoesNotExist()
        val toggleableNodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)
        )
        toggleableNodes.assertCountEquals(7)
        toggleableNodes[1].performClick()
        compose.runOnIdle { assertEquals(listOf("generate_image" to true), updates) }
        compose.onNodeWithText("Web Search").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No service selected").assertIsDisplayed()
        toggleableNodes[0].assertIsNotEnabled()
        compose.onNodeWithText("Send File").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Agent Skills Search").performScrollTo().assertIsDisplayed()
        toggleableNodes[5].performClick()
        compose.runOnIdle { assertEquals(listOf("generate_image" to true, "agent_skills_search" to true), updates) }
    }

    @Test
    fun webSearchSwitchUsesPinnedMcpProviderState() {
        val updates = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalToolControls provides ToolControls(
                        servers = listOf(McpInfo(id = "search", name = "Search MCP")),
                        settings = ToolSettings(webSearchProviderId = "search"),
                        setWebSearch = { updates.add(it) }
                    )
                ) {
                    FeatureToolsSheet(onDismiss = {})
                }
            }
        }

        compose.onNodeWithText("Web Search").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Search MCP").assertIsDisplayed()
        val switches = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)
        )
        switches[0].assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(true), updates) }
    }

    @Test
    fun mcpConnectorsSheetShowsOnlyConnectorManagement() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolControls provides ToolControls()) {
                    McpConnectorsSheet(onDismiss = {})
                }
            }
        }

        compose.onNodeWithText("MCP Connectors").assertIsDisplayed()
        compose.onNodeWithText("No MCP Servers").assertIsDisplayed()
        compose.onNodeWithText("Feature Tools").assertDoesNotExist()
    }
}
