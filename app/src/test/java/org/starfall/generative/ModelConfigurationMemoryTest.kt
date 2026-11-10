package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.state.preferences.ModelConfigurationMemory
import org.starfall.generative.shared.state.preferences.rememberedModelName
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.language.DEFAULT_CONTEXT_WINDOW_TOKENS
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.language.normalizedForTemplate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ModelConfigurationMemoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun configurationIsRestoredAcrossPrefixesAndStoreInstances() {
        val store = ModelConfigurationMemory(context)
        val config = ModelConfiguration(modelType = ModelType.TEXT_TO_SPEECH, supportsVision = false,
            supportsToolCalls = false, supportsThinking = false, supportsAudioInput = true,
            temperature = 0.3, topP = 0.8, supportStream = false, contextWindowTokens = 64000)
        store.remember("first-provider/Speech-Model", config)
        assertEquals("speech-model", rememberedModelName("second-provider/Speech-Model"))
        assertEquals(config.normalizedForTemplate(), ModelConfigurationMemory(context).get("second-provider/speech-model"))
        val discovered = DiscoveredModel("third-provider/speech-model", contextWindowTokens = 32000,
            displayName = "API voice model", metadata = buildJsonObject { put("owned_by", "vendor") })
        val restored = store.configurationFor(discovered)
        assertEquals(ModelType.TEXT_TO_SPEECH, restored.modelType)
        assertFalse(restored.supportsToolCalls)
        assertFalse(restored.supportsThinking)
        assertTrue(restored.supportsAudioInput)
        assertEquals(32000, restored.contextWindowTokens)
        assertEquals(DEFAULT_CONTEXT_WINDOW_TOKENS, store.configurationFor(DiscoveredModel("third-provider/speech-model")).contextWindowTokens)
        assertEquals(discovered.metadata, restored.modelJson)
        assertEquals("API voice model", restored.displayName)
        assertEquals(0.3, restored.temperature!!, 0.0)
    }

    @Test fun backgroundEditorWritesFinishInOrderBeforeBackupSnapshot() = runBlocking {
        val memory = ModelConfigurationMemory(context)
        memory.rememberInBackground("provider/background-memory-test",
            ModelConfiguration(modelType = ModelType.TEXT_GENERATION, contextWindowTokens = 2048))
        memory.rememberInBackground("provider/background-memory-test",
            ModelConfiguration(modelType = ModelType.TEXT_GENERATION, contextWindowTokens = 4096))
        withTimeout(5_000) { memory.awaitBackgroundWrites() }
        assertEquals(4096, ModelConfigurationMemory(context).get("background-memory-test")!!.contextWindowTokens)
    }

    @Test fun portableMemoryDoesNotCopyRawJsonFromAnotherProvider() {
        val store = ModelConfigurationMemory(context)
        val raw = buildJsonObject { put("owned_by", "first-provider"); put("context_window", 262144) }
        store.remember("first-provider/raw-model", ModelConfiguration(modelJson = raw, contextWindowTokens = 262144))
        val manual = store.get("raw-model")!!
        assertTrue(manual.modelJson.isEmpty())
        assertEquals(262144, manual.contextWindowTokens)
        val nextRaw = buildJsonObject { put("owned_by", "second-provider") }
        val restored = store.configurationFor(DiscoveredModel("second-provider/raw-model", metadata = nextRaw))
        assertEquals(nextRaw, restored.modelJson)
        assertEquals(262144, restored.contextWindowTokens)
        assertEquals(300000, store.configurationFor(DiscoveredModel("second-provider/raw-model", contextWindowTokens = 300000)).contextWindowTokens)
        assertEquals(1000, store.configurationFor(DiscoveredModel("second-provider/raw-model", contextWindowTokens = 1000)).contextWindowTokens)
    }

    @Test fun editsOverrideMemoryButSeedingDoesNotOverwriteIt() {
        val store = ModelConfigurationMemory(context)
        val image = ModelConfiguration(modelType = ModelType.IMAGE_GENERATION, icon = "entity-icon-private.png", displayName = "Local name")
        store.remember("vendor/image", image)
        store.remember("other/image", ModelConfiguration(), onlyIfMissing = true)
        assertEquals(ModelType.IMAGE_GENERATION, store.get("image")!!.modelType)
        assertNull(store.get("image")!!.icon)
        assertEquals("", store.get("image")!!.displayName)
        store.remember("another/image", ModelConfiguration(modelType = ModelType.VIDEO_GENERATION))
        assertEquals(ModelType.VIDEO_GENERATION, store.get("image")!!.modelType)
    }
}
