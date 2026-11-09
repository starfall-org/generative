package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.chatoptions.ui.ChatOptionsScreen
import org.starfall.generative.features.tools.ui.ToolsEditor
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.ui.RuntimeAppLocale

class ChatOptionsAndToolsEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chatOptionsSeparatesOptionsAndInstructionsIntoTabs() {
        compose.setContent {
            TestTheme {
                ChatOptionsScreen(
                    providers = emptyList(),
                    settings = ToolSettings(),
                    onSave = { _, _ -> },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Chat Configuration").assertIsDisplayed()
        compose.onNodeWithText("Options").assertIsDisplayed()
        compose.onNodeWithText("Instructions").assertIsDisplayed()
        compose.onNodeWithText("Conversation helpers").assertIsDisplayed()
        compose.onNodeWithText("Web Search Service").assertIsDisplayed()
        compose.onNodeWithTag("chat-options-list").performScrollToIndex(4)
        compose.onNodeWithText("TTS Service").assertIsDisplayed()
        compose.onNodeWithText("Image Generation").assertDoesNotExist()
        compose.onNodeWithText("Send File").assertDoesNotExist()

        compose.onNodeWithTag("chat-options-list").performScrollToIndex(0)
        compose.onNodeWithText("Conversation helpers").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Add").assertIsDisplayed()
        compose.onNodeWithText("System Prompt & Instructions").assertDoesNotExist()
        compose.onNodeWithText("Conversation helpers").assertIsNotDisplayed()

        compose.onNodeWithText("Options").performClick()
        compose.onNodeWithText("Conversation helpers").assertIsDisplayed()
        compose.onNodeWithText("Instructions").performClick()
        compose.onNodeWithContentDescription("Add").assertIsDisplayed()
    }

    @Test
    fun instructionsTabOpensDedicatedInstructionEditorAndSavesInstruction() {
        var savedLibrary = InstructionLibrary()
        compose.setContent {
            TestTheme {
                ChatOptionsScreen(
                    providers = emptyList(),
                    settings = ToolSettings(),
                    instructionLibrary = savedLibrary,
                    initialTab = "instructions",
                    onInstructionLibraryChange = { savedLibrary = it },
                    onSave = { _, _ -> },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithTag("instruction_name_input").assertIsDisplayed()
        compose.onNodeWithText("System").assertIsDisplayed()
        val editorSize = compose.onNodeWithTag("instruction_content_input").fetchSemanticsNode().boundsInRoot
        val screenSize = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Instruction editor must span the available screen width", editorSize.width > screenSize.width * 0.85f)
        assertTrue("Instruction editor must fill the available screen height", editorSize.height > screenSize.height * 0.70f)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithTag("instruction_name_input").performTextInput("Concise")
        compose.onNodeWithTag("instruction_content_input").performTextInput("Keep answers concise")
        compose.onAllNodesWithContentDescription("Back").onLast().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val saved = savedLibrary.instructions.single()
            assertEquals("Concise", saved.name)
            assertEquals("Keep answers concise", saved.content)
            assertFalse(saved.id in savedLibrary.selectedIds)
        }
        compose.onNodeWithText("Concise").assertIsDisplayed()
    }

    @Test
    fun toolsEditorContainsMediaToolModelSelectorsAndFileTool() {
        compose.setContent {
            TestTheme {
                ToolsEditor(
                    providers = emptyList(),
                    settings = ToolSettings(),
                    onSave = { _, _ -> },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Feature Tools").assertIsDisplayed()
        compose.onNodeWithTag("built-in-tools-media-tab").assertIsDisplayed()
        compose.onNodeWithTag("built-in-tools-others-tab").assertIsDisplayed()
        compose.onNodeWithText("Image Generation").assertIsDisplayed()
        compose.onNodeWithText("Video Generation").assertIsDisplayed()
        compose.onNodeWithText("Send File").assertDoesNotExist()
        compose.onNodeWithTag("built-in-tools-others-tab").performClick()
        compose.onNodeWithText("Send File").assertIsDisplayed()
        compose.onNodeWithText("Image Generation").assertDoesNotExist()
        compose.onNodeWithTag("built-in-tools-media-tab").performClick()
        compose.onNodeWithText("Image Generation").assertIsDisplayed()
        compose.onNodeWithText("Conversation helpers").assertDoesNotExist()
        compose.onNodeWithText("Web Search Service").assertDoesNotExist()
    }

    @Test
    fun ttsServiceSheetShowsFullCardsAndSelectsService() {
        var selectedId: String? = null
        compose.setContent {
            TestTheme {
                ChatOptionsScreen(
                    providers = emptyList(),
                    settings = ToolSettings(),
                    speechServices = listOf(
                        SpeechService("system", "System Voice"),
                        SpeechService(
                            id = "cloud", name = "Studio Voice",
                            engine = SpeechEngine.ELEVENLABS,
                            modelId = "eleven-v3", voice = "Rachel"
                        )
                    ),
                    selectedSpeechServiceId = "system",
                    onSetTtsProvider = { selectedId = it },
                    onSave = { _, _ -> },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithTag("chat-options-list").performScrollToIndex(4)
        compose.onNodeWithText("System Voice").performClick()
        compose.onNodeWithTag("tts-service-option-cloud").assertIsDisplayed()
        compose.onNodeWithText("ElevenLabs").assertIsDisplayed()
        compose.onNodeWithText("Model: eleven-v3 · Voice: Rachel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tts-service-option-cloud").performClick()
        compose.runOnIdle { assertEquals("cloud", selectedId) }
        compose.onNodeWithTag("tts-service-option-cloud").assertDoesNotExist()
    }

    @Test
    fun webSearchSheetShowsMcpCardsAndPreservesNoneOption() {
        var selectedId: String? = "first"
        var callbacks = 0
        compose.setContent {
            TestTheme {
                ChatOptionsScreen(
                    providers = emptyList(),
                    settings = ToolSettings(webSearchProviderId = "first", webSearchEnabled = true),
                    mcpServers = listOf(
                        McpInfo("first", "Search API", url = "https://first.example/mcp", cachedTools = emptyList()),
                        McpInfo("second", "Alternative Search", url = "https://second.example/mcp")
                    ),
                    onSetWebSearchProvider = { selectedId = it; callbacks++ },
                    onSave = { _, _ -> },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Search API").performClick()
        compose.onNodeWithTag("web-search-provider-option-second").assertIsDisplayed()
        compose.onNodeWithText("https://second.example/mcp").assertIsDisplayed()
        compose.onNodeWithTag("web-search-provider-option-first").performScrollTo()
        compose.onNodeWithText("0 cached tools").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("web-search-provider-option-second").performClick()
        compose.runOnIdle {
            assertEquals("second", selectedId)
            assertEquals(1, callbacks)
        }
        compose.onNodeWithText("Search API").performClick()
        compose.onNodeWithTag("web-search-provider-option-none").performClick()
        compose.runOnIdle {
            assertEquals(null, selectedId)
            assertEquals(2, callbacks)
        }
    }

    @Test
    fun chatOptionsOptionsTabCanChooseTtsProvider() {
        var selectedId: String? = null
        compose.setContent {
            TestTheme {
                ChatOptionsScreen(
                    providers = emptyList(),
                    settings = ToolSettings(),
                    speechServices = listOf(SpeechService("voice", "Voice")),
                    onSetTtsProvider = { selectedId = it },
                    onSave = { _, _ -> },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithTag("chat-options-list").performScrollToIndex(4)
        compose.onNodeWithText("TTS Service").assertIsDisplayed()
        compose.onNodeWithText("Voice").performClick()
        compose.onAllNodesWithText("Voice").assertCountEquals(2)
        compose.onAllNodesWithText("Voice")[1].performClick()
        compose.runOnIdle { assertEquals("voice", selectedId) }
    }
}

@Composable
internal fun TestTheme(content: @Composable () -> Unit) {
    RuntimeAppLocale(useSystemLanguage = false, selectedLanguage = "en") {
        MaterialTheme(content = content)
    }
}
