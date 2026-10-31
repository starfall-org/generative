package org.starfall.generative.shared.model.auth

import org.starfall.generative.shared.model.api.ModelApiType

fun ModelApiType.defaultAuthorization(): Authorization = when (this) {
    ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES, ModelApiType.ANTHROPIC, ModelApiType.GOOGLE ->
        Authorization(method = AuthMethod.PLATFORM_DEFAULT, value = "")

    ModelApiType.OPENAI_CODEX, ModelApiType.CLAUDE_CODE, ModelApiType.ANTIGRAVITY, ModelApiType.GITHUB_COPILOT,
    ModelApiType.GROK_BUILD, ModelApiType.META_MUSE,
    ModelApiType.GEMINI_CLI, ModelApiType.DEVIN ->
        Authorization(method = AuthMethod.OAUTH, value = "")

    ModelApiType.OLLAMA ->
        Authorization(method = AuthMethod.BEARER_TOKEN, key = "Authorization", value = "")
}

/** Preserve serialized legacy methods while presenting the unified choices. */
fun Authorization.editMethod(): AuthMethod = when (method) {
    AuthMethod.CUSTOM_HEADER -> AuthMethod.BEARER_TOKEN
    AuthMethod.OTHER -> if (token.isBlank()) AuthMethod.NONE else AuthMethod.BEARER_TOKEN
    else -> method
}

fun bearerHeaderValue(headerName: String, value: String): String {
    val trimmed = value.trim()
    return if (headerName.equals("Authorization", ignoreCase = true) &&
        trimmed.isNotEmpty() && !Regex("^Bearer(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
    ) "Bearer $trimmed" else trimmed
}

/** Empty credentials omit authentication; the remote endpoint decides whether it is required. */
fun Authorization.requestHeaders(type: ModelApiType): Map<String, String> = when (method) {
    AuthMethod.BEARER_TOKEN, AuthMethod.CUSTOM_HEADER -> {
        val name = key?.trim()?.takeIf { it.isNotBlank() } ?: "Authorization"
        token.takeIf { it.isNotBlank() }?.let { mapOf(name to bearerHeaderValue(name, it)) } ?: emptyMap()
    }
    AuthMethod.PLATFORM_DEFAULT, AuthMethod.OTHER -> token.takeIf { it.isNotBlank() }?.let {
        when (type) {
            ModelApiType.GOOGLE -> mapOf("x-goog-api-key" to it)
            ModelApiType.ANTHROPIC -> mapOf("x-api-key" to it)
            else -> mapOf("Authorization" to bearerHeaderValue("Authorization", it))
        }
    } ?: emptyMap()
    AuthMethod.QUERY_PARAM, AuthMethod.OAUTH, AuthMethod.NONE -> emptyMap()
}
