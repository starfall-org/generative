package org.starfall.generative.integrations.modelapis.devin

/** Model selection follows the Devin CLI's effort-suffix convention where the catalog declares it. */
internal fun devinModelUID(rawModel: String, requestedEffort: String?, allowedEfforts: List<String>): String {
    val model = rawModel.trim().removePrefix("devin/")
    require(model.isNotBlank()) { "Devin model ID is required" }
    val explicitSuffixes = listOf("-thinking", "-thinking-1m", "-max-1m", "-none-1m",
        "-slow", "-priority", "-low-fast", "-medium-fast", "-high-fast", "-xhigh-fast", "-max-fast",
        "-low-priority", "-medium-priority", "-high-priority", "-xhigh-priority", "-max-priority")
    if (Regex("(?i).+-(?:minimal|low|medium|high|xhigh|max|fast|none)(?:-lightning)?$").matches(model) ||
        explicitSuffixes.any(model::endsWith)) return model
    val levels = allowedEfforts.map(String::lowercase).distinct()
    val requested = requestedEffort?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    val default = when {
        model.contains("swe-2") -> "high"
        model.startsWith("gpt-5") && "none" in levels && "low" in levels -> "low"
        "high" in levels && listOf("gemini", "grok", "glm", "deepseek", "kimi", "nemotron")
            .any(model::contains) -> "high"
        "medium" in levels -> "medium"
        "high" in levels -> "high"
        "low" in levels -> "low"
        else -> levels.firstOrNull().orEmpty()
    }
    val order = listOf("minimal", "low", "medium", "high", "xhigh", "max")
    val target = when {
        requested in levels -> requested!!
        requested == null || requested == "none" -> default
        requested !in order -> default
        else -> levels.minWithOrNull(compareBy<String> { level ->
            val idx = order.indexOf(level)
            if (idx < 0) Int.MAX_VALUE else kotlin.math.abs(idx - order.indexOf(requested))
        }.thenByDescending { order.indexOf(it) }) ?: default
    }
    val thinkingEnabled = requested != "none" && target.isNotBlank()
    return when (model) {
        "claude-haiku-4-5" -> "MODEL_PRIVATE_11"
        "gpt-4-1" -> "MODEL_CHAT_GPT_4_1_2025_04_14"
        "claude-sonnet-4-5" -> if (thinkingEnabled) "MODEL_PRIVATE_3" else "MODEL_PRIVATE_2"
        "gemini-3-flash" -> "gemini-3-8-flash" + if (levels.isNotEmpty() && target.isNotBlank()) "-$target" else ""
        "claude-opus-4-6", "claude-sonnet-4-6" -> if (thinkingEnabled) "$model-thinking" else model
        "claude-opus-4-6-1m" -> if (thinkingEnabled) "claude-opus-4-6-thinking-1m" else model
        "claude-sonnet-4-6-1m" -> if (thinkingEnabled) "claude-sonnet-4-6-thinking-1m" else model
        "swe-1-6" -> model
        "swe-1-7" -> if (target == "medium") "$model-medium" else model
        "glm-5-2", "glm-5-2-1m" -> if (target in setOf("none", "max")) {
            model.replace("-1m", "") + "-$target" + (if (model.endsWith("-1m")) "-1m" else "")
        } else model
        else -> when {
            levels.isEmpty() || target.isBlank() -> model
            model.endsWith("-lightning") -> model.removeSuffix("-lightning") + "-$target-lightning"
            else -> "$model-$target"
        }
    }
}
