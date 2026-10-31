package org.starfall.generative.shared.networking

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.generation.TokenUsage

/** Normalize one wire response. Cached tokens are part of context, not just billable input. */
internal fun providerTokenUsage(type: ModelApiType, response: JsonObject): TokenUsage? {
    val usage = response["usage"] as? JsonObject
    val result = when (type) {
        ModelApiType.OPENAI, ModelApiType.GITHUB_COPILOT -> usage?.counts("prompt_tokens", "completion_tokens")
        ModelApiType.OPENAI_RESPONSES, ModelApiType.OPENAI_CODEX,
        ModelApiType.GROK_BUILD, ModelApiType.META_MUSE,
        ModelApiType.DEVIN -> usage?.counts("input_tokens", "output_tokens")
        ModelApiType.ANTHROPIC, ModelApiType.CLAUDE_CODE -> usage?.let {
            val input = it.count("input_tokens") ?: return null
            val cached = it.optionalCount("cache_read_input_tokens") ?: return null
            val created = it.optionalCount("cache_creation_input_tokens") ?: return null
            val totalInput = safeTokenSum(input, cached, created) ?: return null
            val output = it.count("output_tokens")
            if (it.hasCount("output_tokens") && output == null) return null
            TokenUsage(totalInput, output)
        }
        ModelApiType.GOOGLE, ModelApiType.ANTIGRAVITY, ModelApiType.GEMINI_CLI -> (response["usageMetadata"] as? JsonObject)?.let {
            // promptTokenCount includes cachedContentTokenCount. Do not add it a second time.
            val prompt = it.count("promptTokenCount") ?: return null
            val toolInput = it.optionalCount("toolUsePromptTokenCount") ?: return null
            val input = safeTokenSum(prompt, toolInput) ?: return null
            val candidates = it.optionalCount("candidatesTokenCount") ?: return null
            val thoughts = it.optionalCount("thoughtsTokenCount") ?: return null
            val output = if (!it.hasCount("candidatesTokenCount") && !it.hasCount("thoughtsTokenCount")) null
                else safeTokenSum(candidates, thoughts) ?: return null
            TokenUsage(input, output)
        }
        ModelApiType.OLLAMA -> response.counts("prompt_eval_count", "eval_count")
    }
    return result?.takeIf { it.isValid }
}

private fun JsonObject.counts(inputKey: String, outputKey: String): TokenUsage? {
    val input = count(inputKey) ?: return null
    val output = count(outputKey)
    if (hasCount(outputKey) && output == null) return null
    return TokenUsage(input, output)
}

private fun JsonObject.hasCount(key: String) = this[key] != null && this[key] != JsonNull
private fun JsonObject.count(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
private fun JsonObject.optionalCount(key: String): Long? = if (hasCount(key)) count(key) else 0L

private fun safeTokenSum(vararg counts: Long): Long? {
    var sum = 0L
    for (count in counts) {
        if (count < 0 || count > Long.MAX_VALUE - sum) return null
        sum += count
    }
    return sum
}
