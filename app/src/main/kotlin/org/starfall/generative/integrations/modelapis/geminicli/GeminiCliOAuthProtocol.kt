package org.starfall.generative.integrations.modelapis.geminicli

/** Public OAuth installed-client identity used by Google's Gemini CLI. */
internal object GeminiCliOAuthProtocol {
    const val CLIENT_ID = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
    const val CLIENT_SECRET = "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"
    const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val SCOPE = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile"
    const val CALLBACK_PORT = 51274
    const val CALLBACK_PATH = "/oauth2callback"
    val ADDITIONAL_PARAMETERS: Map<String, String> = mapOf("access_type" to "offline", "prompt" to "consent")
}
