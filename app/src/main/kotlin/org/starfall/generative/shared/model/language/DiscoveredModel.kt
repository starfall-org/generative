package org.starfall.generative.shared.model.language

import kotlinx.serialization.json.*
import org.starfall.generative.shared.model.api.ModelApiType

data class DiscoveredModel(
    val id: String,
    val contextWindowTokens: Int? = null,
    val displayName: String = "",
    val metadata: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
) {
    fun configuration(): ModelConfiguration {
        val supports = (metadata["capabilities"] as? JsonObject)?.get("supports") as? JsonObject
        fun supported(key: String) = (supports?.get(key) as? JsonPrimitive)?.booleanOrNull
        val thinking = supports?.let {
            supported("adaptive_thinking") == true || (it["reasoning_effort"] as? JsonArray)?.isNotEmpty() == true ||
                listOf("min_thinking_budget", "max_thinking_budget").any { key ->
                    (it[key] as? JsonPrimitive)?.longOrNull?.let { budget -> budget > 0 } == true
                }
        }
        // Some account providers (Gemini CLI / Devin) expose flat capability fields rather
        // than the nested capabilities.supports shape. Preserve both representations.
        fun stringSet(key: String): Set<String>? = (metadata[key] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }?.toSet()
        val modalities = stringSet("supportedInputModalities")
        val parameters = stringSet("supportedParameters")
        val methods = stringSet("supportedGenerationMethods")
        val modelThinking = metadata["thinking"] as? JsonObject
        val levels = (modelThinking?.get("levels") as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.takeIf(SELECTABLE_REASONING_EFFORTS::contains)
        }
        return ModelConfiguration(displayName = displayName,
            contextWindowTokens = contextWindowTokens ?: DEFAULT_CONTEXT_WINDOW_TOKENS,
            supportsVision = supported("vision") ?: (modalities?.contains("image") ?: true),
            supportsVideoInput = modalities?.contains("video") ?: false,
            supportsAudioInput = modalities?.contains("audio") ?: false,
            supportsThinking = thinking ?: true,
            reasoningEfforts = levels.ifEmpty { DEFAULT_REASONING_EFFORTS },
            supportsToolCalls = supported("tool_calls") ?: (parameters?.contains("tools") ?: true),
            modelTools = modelToolsFromMetadata(metadata),
            supportStream = supported("streaming") ?: methods?.contains("streamGenerateContent"),
            modelJson = metadata)
    }
}


/** Native model tools commonly available when a provider does not expose tool metadata. */
fun defaultNativeModelTools(providerType: ModelApiType, modelId: String): List<String> {
    val id = modelId.lowercase()
    return when (providerType) {
        ModelApiType.GOOGLE -> if (id.contains("gemini")) {
            listOf("google_search", "google_maps", "url_context", "code_execution")
        } else emptyList()
        ModelApiType.OPENAI_RESPONSES -> if (id.contains("gpt")) {
            listOf("web_search", "code_interpreter", "image_generation")
        } else emptyList()
        ModelApiType.ANTHROPIC -> if (id.contains("claude")) {
            listOf("web_search", "web_fetch", "code_execution")
        } else emptyList()
        else -> emptyList()
    }
}


internal fun modelToolsFromMetadata(metadata: JsonObject): List<String> {
    val value = metadata["model_tools"]
        ?: (metadata["metadata"] as? JsonObject)?.get("model_tools")
        ?: (metadata["capabilities"] as? JsonObject)?.get("model_tools")
    return (value as? JsonArray).orEmpty().mapNotNull { entry ->
        when (entry) {
            is JsonPrimitive -> entry.contentOrNull?.takeIf { it.isNotBlank() }?.let(::canonicalModelToolName)
            is JsonObject -> ((entry["name"] ?: entry["type"] ?: entry["id"]) as? JsonPrimitive)
                ?.contentOrNull?.takeIf { it.isNotBlank() }?.let(::canonicalModelToolName)
            else -> null
        }
    }.distinct()
}

private fun canonicalModelToolName(name: String): String = when {
    name.startsWith("web_search_") -> "web_search"
    name.startsWith("web_fetch_") -> "web_fetch"
    name.startsWith("code_execution_") -> "code_execution"
    name == "google_search_retrieval" -> "google_search"
    else -> name
}
