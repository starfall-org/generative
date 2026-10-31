package org.starfall.generative.integrations.modelapis.devin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Testable Devin CLI PKCE/auth URL construction, independent of the Android browser. */
internal object DevinOAuthProtocol {
    const val REDIRECT_URI = "http://127.0.0.1:53389/callback"
    const val AUTH_URL = "https://app.devin.ai/auth/cli/continue"
    const val TOKEN_URL = "https://api.devin.ai/auth/cli/token"

    data class Challenge(val state: String, val verifier: String, val challenge: String, val authorizationUrl: String)

    fun begin(redirectUri: String = REDIRECT_URI, random: SecureRandom = SecureRandom()): Challenge {
        val bytes = ByteArray(32).also(random::nextBytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        val state = UUID.randomUUID().toString()
        val url = AUTH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .addQueryParameter("prompt", "select_account")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build().toString()
        return Challenge(state, verifier, challenge, url)
    }

    fun sessionToken(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "Devin login returned no session token" }
        return if (trimmed.startsWith("devin-session-token\$") || !trimmed.startsWith("eyJ")) trimmed
        else "devin-session-token\$" + trimmed
    }
}
