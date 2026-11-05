package org.starfall.generative.shared.subfeatures.icons.logic

/**
 * Small set of durable model-family mappings.
 *
 * Most models should be resolved by their actual catalog/name candidates. Keep this list limited
 * to long-lived, widely recognized families so a temporary model name cannot steal another
 * model's logo (for example, a generic "spark" suffix).
 */
internal object LobeModelIconResolver {
    private data class Rule(val iconId: String, val pattern: Regex)

    private val rules = listOf(
        Rule("openai", Regex("(?:^|/)(?:gpt|o[134])(?:[-./]|$)|codex|dall[-e]", RegexOption.IGNORE_CASE)),
        Rule("claude", Regex("claude", RegexOption.IGNORE_CASE)),
        Rule("gemini", Regex("gemini", RegexOption.IGNORE_CASE)),
        Rule("gemma", Regex("gemma", RegexOption.IGNORE_CASE)),
        Rule("meta", Regex("(?:^|/)llama(?:[-./]|$)", RegexOption.IGNORE_CASE)),
        Rule("meta", Regex("(?:^|/)muse(?:[-./]|$)", RegexOption.IGNORE_CASE)),
        Rule("qwen", Regex("(?:^|/)(?:qwen|qwq)(?:[0-9]|[-./]|$)", RegexOption.IGNORE_CASE)),
        Rule("mistral", Regex("(?:mistral|mixtral|codestral|devstral)", RegexOption.IGNORE_CASE)),
        Rule("perplexity", Regex("(?:^|/)(?:sonar|pplx)(?:[-./]|$)", RegexOption.IGNORE_CASE)),
        Rule("moonshot", Regex("(?:^|/)(?:kimi|moonshot)(?:[-./]|$)", RegexOption.IGNORE_CASE)),
        Rule("grok", Regex("(?:^|/)grok(?:[-./]|$)", RegexOption.IGNORE_CASE)),
        Rule("cohere", Regex("(?:^|/)command(?:[-./]|$)", RegexOption.IGNORE_CASE)),
        Rule("microsoft", Regex("(?:^|/)mai|phi(?:[-./]|$)", RegexOption.IGNORE_CASE))
    )

    fun resolve(modelId: String): String? {
        val value = modelId.trim()
        if (value.isEmpty()) return null
        return rules.firstOrNull { it.pattern.containsMatchIn(value) }?.iconId
    }
}
