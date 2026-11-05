package org.starfall.generative.shared.subfeatures.auth.logic

/** Diagnostic details shown locally in the provider UI. Never display OAuth credentials. */
internal fun oauthErrorDetails(error: Throwable): String {
    val trace = error.stackTraceToString().take(16_000)
    return trace
        .replace(Regex("(?i)(bearer\\s+)[^\\s\\\"']+"), "$1[REDACTED]")
        .replace(Regex("(?i)([?&](?:code|access_token|refresh_token|id_token|client_secret|code_verifier)=)[^&\\s]+"), "$1[REDACTED]")
        .replace(Regex("""(?i)(["']?\b(?:access_token|refresh_token|id_token|client_secret|code_verifier|authorization_code|session_token)\b["']?\s*[:=]\s*["']?)[^\s,}"']+"""), "$1[REDACTED]")
        .replace(Regex("\\beyJ[A-Za-z0-9_-]{20,}(?:\\.[A-Za-z0-9_-]+){0,2}"), "[REDACTED_JWT]")
}
