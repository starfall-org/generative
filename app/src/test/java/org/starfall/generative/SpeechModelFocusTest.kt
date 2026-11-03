package org.starfall.generative

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.speech.ui.SpeechModelField
import org.starfall.generative.integrations.speech.SpeechSynthesisModel
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi", application = LibraryTestApplication::class)
class SpeechModelFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fetchingAndOpeningModelCatalogNeverStealsApiKeyFocusForAnyDedicatedEngine() {
        var engine by mutableStateOf(SpeechEngine.ELEVENLABS)
        var apiKey by mutableStateOf("")
        var modelId by mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme {
            Column {
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.testTag("test-api-key"))
                SpeechModelField(SpeechService("speech", "Voice", engine = engine, apiKey = apiKey, modelId = modelId),
                    onChange = { modelId = it }, fetchModels = { listOf(SpeechSynthesisModel("catalog-model", "Catalog model")) })
            }
        } }
        SpeechEngine.entries.filter { it.standalone }.forEach { next ->
            compose.runOnIdle { engine = next; apiKey = ""; modelId = null }
            compose.onNodeWithTag("test-api-key").performClick().performTextReplacement("key-${next.name}")
            compose.waitUntil(5_000) { modelId == "catalog-model" }
            compose.onNodeWithTag("test-api-key").assertIsFocused()
            compose.onNodeWithTag("speech-model-id").assertIsNotFocused()
            compose.onNodeWithTag("speech-model-dropdown").performClick()
            compose.onNodeWithTag("speech-model-menu").assertExists()
            compose.onNodeWithTag("speech-model-id").assertIsNotFocused()
            compose.onNodeWithTag("speech-model-option:catalog-model").performClick()
        }
        compose.onNodeWithTag("speech-model-id").performClick().performTextReplacement("manual-model")
        compose.onNodeWithTag("speech-model-id").assertIsFocused().assertTextContains("manual-model")
    }
}
