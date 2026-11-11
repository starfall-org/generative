package org.starfall.generative

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.providers.shared.ui.ProviderModelsPane
import org.starfall.generative.features.mcp.list.ui.ServerListScreen
import org.starfall.generative.features.speech.ui.SpeechServicesScreen
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.language.ModelConfiguration

/** Reordering outside ProvidersScreen must use the same DND gesture and persist on release. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
class UnifiedReorderRobolectricTest {
    @get:Rule val compose = createComposeRule()

    private fun drag(from: Offset, to: Offset) {
        val root = compose.onRoot()
        root.performTouchInput { down(from) }
        compose.mainClock.advanceTimeBy(700)
        root.performTouchInput { moveTo(to) }
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
        root.performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun providerModelsReorderWithSharedDndLibrary() {
        val order = mutableStateOf(listOf("First", "Second", "Third"))
        val models = order.value.associateWith { ModelConfiguration() }
        val saved = mutableListOf<List<String>>()
        compose.setScreenContent {
            MaterialTheme {
                ProviderModelsPane(
                    models = order.value, configurations = models,
                    listState = rememberLazyListState(),
                    onOrderChange = { order.value = it },
                    onDrop = { _, reordered -> saved += reordered },
                    onEdit = {}, onDelete = {}, onOpenCatalog = {}
                )
            }
        }
        compose.waitForIdle()
        val from = compose.onNodeWithText("First").fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNodeWithText("Third").fetchSemanticsNode().boundsInRoot.center
        drag(from, to)
        assertEquals(listOf("Second", "Third", "First"), saved.single())
    }

    @Test
    fun mcpServersReorderWithSharedDndLibrary() {
        val saved = mutableListOf<List<String>>()
        compose.setScreenContent {
            MaterialTheme {
                ServerListScreen(
                    mcpServers = listOf(
                        McpInfo("first", "MCP First"),
                        McpInfo("second", "MCP Second"),
                        McpInfo("third", "MCP Third")
                    ),
                    toolsCache = emptyMap(), toolErrors = emptyMap(), toolsLoading = emptySet(),
                    toolSettings = ToolSettings(),
                    onSetToolEnabled = { _, _, _ -> }, onSetMcpEnabled = { _, _ -> },
                    onDeleteMcpServer = {}, onReorderMcpServers = { saved += it },
                    onOpenEditor = { _, _ -> }, onBack = {}
                )
            }
        }
        compose.waitForIdle()
        val from = compose.onNodeWithText("MCP First").fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNodeWithText("MCP Third").fetchSemanticsNode().boundsInRoot.center
        drag(from, to)
        assertEquals(listOf("second", "third", "first"), saved.single())
    }

    @Test
    fun speechServicesReorderWithSharedDndLibrary() {
        val saved = mutableListOf<List<String>>()
        compose.setScreenContent {
            MaterialTheme {
                SpeechServicesScreen(
                    speechServices = listOf(
                        SpeechService("first", "Voice First"),
                        SpeechService("second", "Voice Second"),
                        SpeechService("third", "Voice Third")
                    ),
                    providers = emptyList(), selectedSpeechServiceId = null,
                    onSetTtsProvider = {}, onSaveService = {}, onDeleteService = {},
                    onReorderServices = { saved += it },
                    onTestVoice = { _, _ -> }, onBack = {}
                )
            }
        }
        compose.waitForIdle()
        val from = compose.onNodeWithText("Voice First").fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNodeWithText("Voice Third").fetchSemanticsNode().boundsInRoot.center
        drag(from, to)
        assertEquals(listOf("second", "third", "first"), saved.single())
    }
}
