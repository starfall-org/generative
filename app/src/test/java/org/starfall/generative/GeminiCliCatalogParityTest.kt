package org.starfall.generative

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.modelapis.geminicli.GeminiCliModelCatalog

/** Parity with cpa-plugin-gemini-cli/internal/models/models.go:StaticModels/ModelsForAuth. */
class GeminiCliCatalogParityTest {
    private val catalog = GeminiCliModelCatalog.models()

    @Test fun matchesTheEightUpstreamModelsInOrder() {
        assertEquals(listOf(
            "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite",
            "gemini-3-pro-preview", "gemini-3.1-pro-preview", "gemini-3-flash-preview",
            "gemini-3.1-flash-lite-preview", "gemini-3.5-flash"
        ), catalog.map { it.id })
        assertEquals(8, catalog.distinctBy { it.id }.size)
    }

    @Test fun allModelsCarryTheUpstreamLimitsAndModalities() {
        catalog.forEach { model ->
            assertEquals(1_048_576, model.contextWindowTokens)
            val info = model.metadata
            assertEquals("google", info["owned_by"]!!.jsonPrimitive.content)
            assertEquals(65_536, info["max_completion_tokens"]!!.jsonPrimitive.int)
            assertEquals(65_536, info["outputTokenLimit"]!!.jsonPrimitive.int)
            assertEquals(listOf("generateContent", "streamGenerateContent", "countTokens"),
                info["supportedGenerationMethods"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("text", "image", "audio", "video"),
                info["supportedInputModalities"]!!.jsonArray.map { it.jsonPrimitive.content })
            val config = model.configuration()
            assertEquals(1_048_576, config.contextWindowTokens)
            assertTrue(config.supportsVision)
            assertTrue(config.supportsVideoInput)
            assertTrue(config.supportsAudioInput)
            assertTrue(config.supportsToolCalls)
            assertTrue(config.supportsThinking)
            assertEquals(true, config.supportStream)
            assertEquals(model.displayName, config.displayName)
        }
    }

    @Test fun thinkingMetadataReflectsUpstreamGenerationDifferences() {
        fun thinking(id: String) = catalog.first { it.id == id }.metadata["thinking"]!!.jsonObject
        val pro25 = thinking("gemini-2.5-pro")
        assertEquals(128, pro25["min"]!!.jsonPrimitive.int)
        assertEquals(32768, pro25["max"]!!.jsonPrimitive.int)
        assertFalse(pro25["zeroAllowed"]!!.jsonPrimitive.boolean)
        assertTrue(pro25["dynamicAllowed"]!!.jsonPrimitive.boolean)
        val flash25 = thinking("gemini-2.5-flash")
        assertEquals(24576, flash25["max"]!!.jsonPrimitive.int)
        assertTrue(flash25["zeroAllowed"]!!.jsonPrimitive.boolean)
        val preview = catalog.first { it.id == "gemini-3-pro-preview" }
        val previewThinking = preview.metadata["thinking"]!!.jsonObject
        assertEquals(65536, previewThinking["max"]!!.jsonPrimitive.int)
        assertEquals(listOf("none", "auto", "low", "medium", "high", "xhigh"),
            previewThinking["levels"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("low", "medium", "high", "xhigh"), preview.configuration().reasoningEfforts)
    }
}
