package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.speech.ui.SpeechEditor
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.SpeechEngine

class SpeechEditorDefaultsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun defaultNameTracksEngineAndAllCloudEnginesUseSharedKeyVisibility() {
        compose.setContent { MaterialTheme {
            SpeechEditor(SpeechService("new", "Custom TTS"), true, emptyList(), false,
                onTest = { _, _ -> }, onDismiss = {}, onSave = {})
        } }
        listOf("ELEVENLABS" to "ElevenLabs", "FISH_AUDIO" to "Fish Audio",
            "CARTESIA" to "Cartesia", "SPEECHIFY" to "Speechify").forEachIndexed { index, (id, label) ->
            compose.onNodeWithTag("speech-engine-selector").performScrollTo().performClick()
            compose.onNodeWithTag("app-bottom-sheet").assertExists()
            compose.onNodeWithTag("speech-platform-list").performScrollToIndex(index + 1)
            compose.onNodeWithTag("speech-engine-option-$id").performClick()
            compose.onNodeWithTag("speech-service-name").assertTextContains(label)
            compose.onNodeWithTag("speech-model-id").performScrollTo().assertTextEquals("")
            compose.onNodeWithContentDescription("Show API key").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Hide API key").assertExists()
        }
        compose.onNodeWithTag("speech-service-name").performScrollTo().performTextReplacement("My narrator")
        compose.onNodeWithTag("speech-engine-selector").performScrollTo().performClick()
        compose.onNodeWithTag("speech-engine-option-ELEVENLABS").performScrollTo().performClick()
        compose.onNodeWithTag("speech-service-name").assertTextContains("My narrator")
        compose.onNodeWithTag("speech-engine-selector").performScrollTo().performClick()
        compose.onNodeWithTag("speech-engine-option-SYSTEM").performScrollTo().performClick()
        compose.onNodeWithTag("speech-service-name").assertTextContains("My narrator")
    }

    @Test fun platformSheetShowsFullCardsAndProviderModels() {
        val provider = org.starfall.generative.shared.model.provider.ModelProvider(
            "tts-provider", "My provider",
            org.starfall.generative.shared.model.api.ModelApiType.OPENAI,
            baseUrl = "https://example.test/v1",
            config = org.starfall.generative.shared.model.provider.ProviderConfiguration(
                modelConfigs = mapOf("voice-model" to
                    org.starfall.generative.shared.model.language.ModelConfiguration(
                        modelType = org.starfall.generative.shared.model.language.ModelType.TEXT_TO_SPEECH))
            )
        )
        compose.setContent { MaterialTheme {
            SpeechEditor(SpeechService("new", "Custom TTS"), true, listOf(provider), false,
                onTest = { _, _ -> }, onDismiss = {}, onSave = {})
        } }
        compose.onNodeWithTag("speech-engine-selector").performScrollTo().performClick()
        listOf("SYSTEM", "ELEVENLABS", "FISH_AUDIO", "CARTESIA", "SPEECHIFY")
            .forEachIndexed { index, id ->
                compose.onNodeWithTag("speech-platform-list").performScrollToIndex(index)
                compose.onNodeWithTag("speech-engine-option-$id").assertExists()
            }
        compose.onNodeWithTag("speech-platform-list").performScrollToIndex(5)
        compose.onNodeWithTag("speech-provider-option-tts-provider").performClick()
        compose.onNodeWithTag("app-bottom-sheet").assertDoesNotExist()
        compose.onNodeWithTag("speech-service-name").assertTextContains("My provider")
    }

    @Test fun everyStandaloneSpeechEngineSavesPlaybackSpeed() {
        val selectedEngine = mutableStateOf(SpeechEngine.ELEVENLABS)
        var saved: SpeechService? = null
        compose.setContent { MaterialTheme {
            key(selectedEngine.value) {
                val engine = selectedEngine.value
                SpeechEditor(
                    SpeechService("speech-${engine.name}", "Narrator", engine = engine,
                        modelId = "selected-model", voice = "voice-id", apiKey = "test-key",
                        responseFormat = when (engine) {
                            SpeechEngine.ELEVENLABS -> "mp3_44100_128"
                            SpeechEngine.CARTESIA -> "wav"
                            else -> "mp3"
                        }),
                    isNew = false, providers = emptyList(), isPlaying = false,
                    onTest = { _, _ -> }, onDismiss = {}, onSave = { saved = it },
                    fetchModels = { listOf(org.starfall.generative.integrations.speech.SpeechSynthesisModel(
                        "selected-model", "Selected")) }
                )
            }
        } }
        SpeechEngine.entries.filter { it.standalone }.forEach { engine ->
            compose.runOnIdle { selectedEngine.value = engine; saved = null }
            compose.onNodeWithTag("speech-speed-slider").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(1.6f) }
            compose.onNodeWithText("Save").performClick()
            compose.runOnIdle {
                org.junit.Assert.assertEquals(engine, saved?.engine)
                org.junit.Assert.assertEquals(1.6f, saved?.speed ?: 0f, 0.001f)
            }
        }
    }

    @Test fun wordCountDefaultsToOneHundredAndIsSavedWithTheService() {
        val provider = org.starfall.generative.shared.model.provider.ModelProvider("p", "Provider",
            org.starfall.generative.shared.model.api.ModelApiType.OPENAI, baseUrl = "https://example.test/v1",
            config = org.starfall.generative.shared.model.provider.ProviderConfiguration(modelConfigs = mapOf(
                "tts" to org.starfall.generative.shared.model.language.ModelConfiguration(
                    modelType = org.starfall.generative.shared.model.language.ModelType.TEXT_TO_SPEECH))))
        var saved: SpeechService? = null
        compose.setContent { MaterialTheme {
            SpeechEditor(SpeechService("s", "Voice", provider = "p", modelId = "tts"), false, listOf(provider), false,
                onTest = { _, _ -> }, onDismiss = {}, onSave = { saved = it })
        } }
        compose.onNodeWithTag("speech-chunk-size").performScrollTo().assertTextContains("100")
            .performTextReplacement("6")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(6, saved?.maxWordsPerChunk) }
    }

}
