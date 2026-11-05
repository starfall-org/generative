package org.starfall.generative.shared.subfeatures.logging.logic

/** Masks credentials before text reaches the on-screen log, which users may copy and share. */
object DebugLogRedactor {
    const val MASK = "***"

    private val sensitiveHeaders = setOf(
        "authorization", "proxy-authorization", "x-api-key", "x-goog-api-key", "api-key", "cookie", "set-cookie"
    )
    private val jsonSecret = Regex(
        "(\"(?:access_token|refresh_token|id_token|client_secret|api_key|apikey|password|code_verifier|authorization|token)\"\\s*:\\s*\")([^\"]*)(\")",
        RegexOption.IGNORE_CASE
    )
    private val formSecret = Regex(
        "\\b(access_token|refresh_token|id_token|client_secret|code_verifier|password|api_key|apikey|key)=([^&\\s\"]+)",
        RegexOption.IGNORE_CASE
    )
    private val bearer = Regex("(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}", RegexOption.IGNORE_CASE)

    fun isSensitiveHeader(name: String): Boolean = name.lowercase() in sensitiveHeaders

    fun redact(text: String): String =
        text.replace(jsonSecret) { it.groupValues[1] + MASK + it.groupValues[3] }
            .replace(formSecret) { it.groupValues[1] + "=" + MASK }
            .replace(bearer) { it.groupValues[1] + MASK }
}
