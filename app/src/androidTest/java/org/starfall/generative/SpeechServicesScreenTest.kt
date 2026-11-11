package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.speech.ui.SpeechServicesScreen
import org.starfall.generative.shared.ui.RuntimeAppLocale
import org.starfall.generative.shared.model.SpeechService

class SpeechServicesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tappingSpeechServiceOpensEditorWithoutSelectingIt() {
        var selectedId: String? = null
        compose.setScreenContent {
            EnglishTestTheme {
                SpeechServicesScreen(
                    speechServices = listOf(SpeechService("voice", "Voice")),
                    providers = emptyList(),
                    selectedSpeechServiceId = null,
                    onSetTtsProvider = { selectedId = it },
                    onSaveService = {},
                    onDeleteService = {},
                    onReorderServices = {},
                    onTestVoice = { _, _ -> },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Voice").performClick()
        compose.onNodeWithText("Edit Speech Service").assertIsDisplayed()
        compose.runOnIdle { assertNull(selectedId) }
    }

    @Test
    fun speechEditorIsAFullScreenPageWithSaveAndBackNavigation() {
        var saved: SpeechService? = null
        compose.setScreenContent {
            EnglishTestTheme {
                SpeechServicesScreen(
                    speechServices = listOf(SpeechService("voice", "Voice", provider = "system")),
                    providers = emptyList(),
                    selectedSpeechServiceId = null,
                    onSetTtsProvider = {},
                    onSaveService = { saved = it },
                    onDeleteService = {},
                    onReorderServices = {},
                    onTestVoice = { _, _ -> },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Voice").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Edit Speech Service").assertIsDisplayed()
        compose.onNodeWithText("Speech synthesis services").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("Voice", saved?.name) }
        compose.waitForIdle()
        compose.onNodeWithText("Speech synthesis services").assertIsDisplayed()

        compose.onNodeWithContentDescription("Add speech service").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add speech service").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Speech synthesis services").assertIsDisplayed()
    }

    @Test
    fun speechServiceContextMenuCanSetTheTtsProvider() {
        var selectedId: String? = null
        compose.setScreenContent {
            EnglishTestTheme {
                SpeechServicesScreen(
                    speechServices = listOf(
                        SpeechService("system", "System", provider = "system"),
                        SpeechService("voice", "Voice", provider = "provider", modelId = "tts")
                    ),
                    providers = emptyList(),
                    selectedSpeechServiceId = null,
                    onSetTtsProvider = { selectedId = it },
                    onSaveService = {},
                    onDeleteService = {},
                    onReorderServices = {},
                    onTestVoice = { _, _ -> },
                    onBack = {}
                )
            }
        }

        val menus = compose.onAllNodesWithContentDescription("More options")
        menus.assertCountEquals(2)
        menus[1].performClick()
        compose.onNodeWithText("Set as TTS Service").performClick()
        compose.runOnIdle { assertEquals("voice", selectedId) }
    }
}

@Composable
internal fun EnglishTestTheme(content: @Composable () -> Unit) {
    RuntimeAppLocale(useSystemLanguage = false, selectedLanguage = "en") {
        MaterialTheme(content = content)
    }
}
