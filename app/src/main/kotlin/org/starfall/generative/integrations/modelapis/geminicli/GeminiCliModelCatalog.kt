package org.starfall.generative.integrations.modelapis.geminicli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.starfall.generative.shared.model.language.DiscoveredModel

/**
 * Port of cpa-plugin-gemini-cli/internal/models/models.go (StaticModels / ModelsForAuth).
 * CLIProxyAPI serves the same static catalog for every login; Cloud Code does not expose a
 * per-account models listing in this implementation. Keep definitions in sync with that plugin.
 */
internal object GeminiCliModelCatalog {
    private const val INPUT_TOKENS = 1_048_576
    private const val OUTPUT_TOKENS = 65_536

    private data class Thinking(
        val min: Int = 0,
        val max: Int,
        val zeroAllowed: Boolean = false,
        val dynamicAllowed: Boolean = true,
        val levels: List<String> = emptyList()
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("min", min)
            put("max", max)
            put("zeroAllowed", zeroAllowed)
            put("dynamicAllowed", dynamicAllowed)
            put("levels", JsonArray(levels.map { JsonPrimitive(it) }))
        }
    }

    private data class Entry(val id: String, val displayName: String, val thinking: Thinking)

    private val generalThinking = Thinking(max = OUTPUT_TOKENS, zeroAllowed = true,
        levels = listOf("none", "auto", "low", "medium", "high", "xhigh"))
    private val pro25Thinking = Thinking(min = 128, max = 32_768)
    private val flash25Thinking = Thinking(max = 24_576, zeroAllowed = true)

    // Same ordering, IDs, display names, and limits as the CLIProxyAPI Gemini CLI plugin.
    private val entries = listOf(
        Entry("gemini-2.5-pro", "Gemini 2.5 Pro", pro25Thinking),
        Entry("gemini-2.5-flash", "Gemini 2.5 Flash", flash25Thinking),
        Entry("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", flash25Thinking),
        Entry("gemini-3-pro-preview", "Gemini 3 Pro Preview", generalThinking),
        Entry("gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview", generalThinking),
        Entry("gemini-3-flash-preview", "Gemini 3 Flash Preview", generalThinking),
        Entry("gemini-3.1-flash-lite-preview", "Gemini 3.1 Flash Lite Preview", generalThinking),
        Entry("gemini-3.5-flash", "Gemini 3.5 Flash", generalThinking)
    )

    val modelIds: List<String> = entries.map(Entry::id)

    fun models(): List<DiscoveredModel> = entries.map { entry ->
        val methods = listOf("generateContent", "streamGenerateContent", "countTokens")
        val inputs = listOf("text", "image", "audio", "video")
        val outputs = listOf("text")
        val parameters = listOf("temperature", "top_p", "top_k", "max_output_tokens", "stop", "tools", "thinking")
        val metadata = buildJsonObject {
            put("id", entry.id)
            put("object", "model")
            put("owned_by", "google")
            put("type", "chat")
            put("display_name", entry.displayName)
            put("name", entry.id)
            put("description", "${entry.displayName} via Gemini CLI")
            put("inputTokenLimit", INPUT_TOKENS)
            put("outputTokenLimit", OUTPUT_TOKENS)
            put("context_length", INPUT_TOKENS)
            put("max_completion_tokens", OUTPUT_TOKENS)
            put("supportedGenerationMethods", JsonArray(methods.map { JsonPrimitive(it) }))
            put("supportedInputModalities", JsonArray(inputs.map { JsonPrimitive(it) }))
            put("supportedOutputModalities", JsonArray(outputs.map { JsonPrimitive(it) }))
            put("supportedParameters", JsonArray(parameters.map { JsonPrimitive(it) }))
            put("thinking", entry.thinking.json())
        }
        DiscoveredModel(entry.id, INPUT_TOKENS, entry.displayName, metadata)
    }
}
