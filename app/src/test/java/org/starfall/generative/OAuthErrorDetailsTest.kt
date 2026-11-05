package org.starfall.generative

import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.subfeatures.auth.logic.oauthErrorDetails

class OAuthErrorDetailsTest {
    @Test fun oauthFailureKeepsExceptionDetailsButRedactsCredentials() {
        val error = IllegalStateException(
            "OAuth failed: Bearer aSecretToken, access_token=myAccessToken " +
                "refresh_token=hiddenRefresh https://example.org/callback?code=privateCode&state=ok " +
                "code_verifier=hiddenVerifier and {\"refresh_token\":\"jsonHidden\"}"
        )
        val details = oauthErrorDetails(error)
        assertTrue(details.contains("java.lang.IllegalStateException"))
        assertTrue(details.contains("OAuth failed"))
        for (secret in listOf("aSecretToken", "myAccessToken", "hiddenRefresh", "privateCode", "hiddenVerifier", "jsonHidden")) {
            assertFalse("OAuth error details must hide credentials: $secret", details.contains(secret))
        }
        assertTrue(details.contains("[REDACTED]"))
        assertTrue(details.contains("OAuthErrorDetailsTest"))
    }
}
