package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.mcp.list.ui.ServerListScreen
import org.starfall.generative.features.speech.ui.SpeechServicesScreen
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.ToolSettings

/** Device-side reorder coverage for DND screens outside Providers. */
@RunWith(AndroidJUnit4::class)
class UnifiedReorderDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun point(text: String): Offset =
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center -
            compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft

    private fun drag(from: Offset, to: Offset, persisted: List<List<String>>) {
        val root = compose.onRoot()
        root.performTouchInput { down(from) }
        compose.mainClock.advanceTimeBy(750)
        root.performTouchInput { moveTo(to) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertTrue("reordering must not persist before release", persisted.isEmpty())
        root.performTouchInput { up() }
        compose.waitUntil(5_000) { persisted.size == 1 }
    }

    @Test
    fun mcpGridReorderPersistsTheTouchedServer() {
        val saved = CopyOnWriteArrayList<List<String>>()
        compose.setScreenContent {
            MaterialTheme {
                ServerListScreen(
                    mcpServers = listOf(
                        McpInfo("one", "MCP One"),
                        McpInfo("two", "MCP Two"),
                        McpInfo("three", "MCP Three")
                    ),
                    isGridView = true,
                    toolsCache = emptyMap(), toolErrors = emptyMap(), toolsLoading = emptySet(),
                    toolSettings = ToolSettings(),
                    onSetToolEnabled = { _, _, _ -> }, onSetMcpEnabled = { _, _ -> },
                    onDeleteMcpServer = {}, onReorderMcpServers = { saved.add(it) },
                    onOpenEditor = { _, _ -> }, onBack = {}
                )
            }
        }
        compose.waitForIdle()
        drag(point("MCP One"), point("MCP Three"), saved)
        assertEquals(listOf("two", "three", "one"), saved.single())
    }

    @Test
    fun speechGridReorderPersistsTheTouchedService() {
        assertSpeechReorder(grid = true)
    }

    @Test
    fun speechListReorderPersistsTheTouchedService() {
        assertSpeechReorder(grid = false)
    }

    private fun assertSpeechReorder(grid: Boolean) {
        val saved = CopyOnWriteArrayList<List<String>>()
        compose.setScreenContent {
            MaterialTheme {
                SpeechServicesScreen(
                    speechServices = listOf(
                        SpeechService("one", "Voice One"),
                        SpeechService("two", "Voice Two"),
                        SpeechService("three", "Voice Three")
                    ),
                    isGridView = grid,
                    providers = emptyList(), selectedSpeechServiceId = null,
                    onSetTtsProvider = {}, onSaveService = {}, onDeleteService = {},
                    onReorderServices = { saved.add(it) },
                    onTestVoice = { _, _ -> }, onBack = {}
                )
            }
        }
        compose.waitForIdle()
        drag(point("Voice One"), point("Voice Three"), saved)
        assertEquals(listOf("two", "three", "one"), saved.single())
    }
}
