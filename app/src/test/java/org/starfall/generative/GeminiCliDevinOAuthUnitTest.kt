package org.starfall.generative

import java.security.MessageDigest
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.integrations.modelapis.devin.DevinOAuthProtocol
import org.starfall.generative.integrations.modelapis.geminicli.GeminiCliOAuthProtocol
import org.starfall.generative.shared.auth.AppAuthTransactions

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeminiCliDevinOAuthUnitTest {
    @Test fun devinAuthorizationUsesUniquePkceS256AndLocalhostRedirect() {
        val first = DevinOAuthProtocol.begin()
        val second = DevinOAuthProtocol.begin()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
        assertEquals(86, first.verifier.length)
        assertEquals(43, first.challenge.length)
        val derived = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(first.verifier.toByteArray(Charsets.US_ASCII)))
        assertEquals(derived, first.challenge)
        val request = first.authorizationUrl.toHttpUrl()
        assertEquals("app.devin.ai", request.host)
        assertEquals("/auth/cli/continue", request.encodedPath)
        assertEquals(DevinOAuthProtocol.REDIRECT_URI, request.queryParameter("redirect_uri"))
        assertEquals(first.state, request.queryParameter("state"))
        assertEquals(first.challenge, request.queryParameter("code_challenge"))
        assertEquals("S256", request.queryParameter("code_challenge_method"))
        assertEquals("select_account", request.queryParameter("prompt"))
        assertFalse(first.authorizationUrl.contains(first.verifier))
    }

    @Test fun devinSessionTokenNormalizationKeepsExistingPrefix() {
        assertEquals("devin-session-token\$eyJpayload", DevinOAuthProtocol.sessionToken("eyJpayload"))
        assertEquals("devin-session-token\$eyJpayload", DevinOAuthProtocol.sessionToken("devin-session-token\$eyJpayload"))
        assertEquals("manual-session", DevinOAuthProtocol.sessionToken("manual-session"))
        try { DevinOAuthProtocol.sessionToken("  "); fail("Blank tokens must fail") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun geminiCliAuthorizationUsesOfflineGooglePkceScopesAndRedirect() {
        fun create() = AppAuthTransactions.authorization(
            GeminiCliOAuthProtocol.AUTH_URL, GeminiCliOAuthProtocol.TOKEN_URL,
            GeminiCliOAuthProtocol.CLIENT_ID,
            "http://127.0.0.1:${GeminiCliOAuthProtocol.CALLBACK_PORT}${GeminiCliOAuthProtocol.CALLBACK_PATH}",
            GeminiCliOAuthProtocol.SCOPE, GeminiCliOAuthProtocol.ADDITIONAL_PARAMETERS)
        val first = create()
        val second = create()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
        assertEquals("S256", first.codeVerifierChallengeMethod)
        val uri = first.toUri()
        assertEquals("accounts.google.com", uri.host)
        assertEquals("offline", uri.getQueryParameter("access_type"))
        assertEquals("consent", uri.getQueryParameter("prompt"))
        assertEquals(GeminiCliOAuthProtocol.CLIENT_ID, uri.getQueryParameter("client_id"))
        assertEquals(GeminiCliOAuthProtocol.SCOPE, uri.getQueryParameter("scope"))
        assertEquals(first.codeVerifierChallenge, uri.getQueryParameter("code_challenge"))
        assertEquals("http://127.0.0.1:51274/oauth2callback", uri.getQueryParameter("redirect_uri"))
        assertNotNull(first.codeVerifier)
        assertFalse(uri.toString().contains(first.codeVerifier!!))
    }

    @Test fun geminiCliAuthorizationAndRefreshSendCorrectTokenFields() {
        val verifier = "A".repeat(43) // RFC 7636 requires 43–128 legal ASCII characters.
        val req = AppAuthTransactions.token(GeminiCliOAuthProtocol.TOKEN_URL, GeminiCliOAuthProtocol.CLIENT_ID,
            mapOf("grant_type" to "authorization_code", "code" to "example-code",
                "redirect_uri" to "http://127.0.0.1:51274/oauth2callback", "code_verifier" to verifier))
        val fields = AppAuthTransactions.fields(req,
            AppAuthTransactions.authentication("client_secret_post", GeminiCliOAuthProtocol.CLIENT_SECRET))
        assertEquals("authorization_code", fields["grant_type"])
        assertEquals("example-code", fields["code"])
        assertEquals(verifier, fields["code_verifier"])
        assertEquals(GeminiCliOAuthProtocol.CLIENT_ID, fields["client_id"])
        assertEquals(GeminiCliOAuthProtocol.CLIENT_SECRET, fields["client_secret"])
        val refresh = AppAuthTransactions.token(GeminiCliOAuthProtocol.TOKEN_URL, GeminiCliOAuthProtocol.CLIENT_ID,
            mapOf("grant_type" to "refresh_token", "refresh_token" to "previous"))
        val refreshFields = AppAuthTransactions.fields(refresh,
            AppAuthTransactions.authentication("client_secret_post", GeminiCliOAuthProtocol.CLIENT_SECRET))
        assertEquals("refresh_token", refreshFields["grant_type"])
        assertEquals("previous", refreshFields["refresh_token"])
        assertFalse(refreshFields.containsKey("code_verifier"))
    }
}
