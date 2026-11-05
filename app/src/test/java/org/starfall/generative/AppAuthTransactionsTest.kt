package org.starfall.generative

import org.starfall.generative.shared.subfeatures.auth.logic.*

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.auth.logic.AppAuthTransactions
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.model.auth.Authorization

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppAuthTransactionsTest {
    @Test fun authorizationCarriesResourceAndGeneratesIndependentPkceSessions() {
        fun request() = AppAuthTransactions.authorization("https://example.com/authorize", "https://example.com/token",
            "client", "http://127.0.0.1:54321/callback", "mcp:read", mapOf("resource" to "https://example.com/mcp", "prompt" to "consent"))
        val first = request()
        val second = request()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
        assertEquals("S256", first.codeVerifierChallengeMethod)
        assertEquals("consent", first.toUri().getQueryParameter("prompt"))
        assertEquals("https://example.com/mcp", first.toUri().getQueryParameter("resource"))
        assertEquals(first.codeVerifierChallenge, first.toUri().getQueryParameter("code_challenge"))
    }

    @Test fun refreshKeepsResourceAndClientSecretPostFields() {
        val request = AppAuthTransactions.token("https://example.com/token", "client", mapOf(
            "grant_type" to "refresh_token", "refresh_token" to "refresh", "resource" to "https://example.com/mcp"))
        val values = AppAuthTransactions.fields(request, AppAuthTransactions.authentication("client_secret_post", "secret"))
        assertEquals("refresh", values["refresh_token"])
        assertEquals("https://example.com/mcp", values["resource"])
        assertEquals("client", values["client_id"])
        assertEquals("secret", values["client_secret"])
        assertFalse(values.containsKey("code_verifier"))
    }

    @Test fun standardAppAuthExecutesFormRequestAndPreservesExtensionFields() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"access_token":"access","token_type":"Bearer","refresh_token":"rotated","expires_in":3600,"account_id":"account"}"""))
            val request = AppAuthTransactions.token(server.url("/token").toString(), "client", mapOf(
                "grant_type" to "refresh_token", "refresh_token" to "old", "resource" to "https://resource.example/mcp"))
            val result = async(kotlinx.coroutines.Dispatchers.Unconfined) {
                AppAuthTransactions.exchange(androidx.test.core.app.ApplicationProvider.getApplicationContext(), ToolHttp(), request,
                    AppAuthTransactions.authentication("client_secret_basic", "secret"))
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (!result.isCompleted && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(10)
            }
            assertTrue("AppAuth callback must complete", result.isCompleted)
            val payload = result.await()
            assertEquals("account", payload["account_id"]?.jsonPrimitive?.content)
            assertEquals("rotated", payload["refresh_token"]?.jsonPrimitive?.content)
            assertTrue(payload["expires_in"]!!.jsonPrimitive.content.toLong() in 3500..3600)
            val sent = server.takeRequest()
            assertTrue(sent.getHeader("Authorization")!!.startsWith("Basic "))
            val body = sent.body.readUtf8()
            assertTrue(body.contains("grant_type=refresh_token"))
            assertTrue(body.contains("refresh_token=old"))
            assertTrue(body.contains("resource="))
            assertFalse(body.contains("client_secret="))
        } finally { server.shutdown() }
    }

    @Test fun jsonTokenEndpointKeepsCompatibilityFieldsAndBearerFallback() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"access","refresh_token":"refresh","expires_in":3600,"account_id":"account"}"""))
            val request = AppAuthTransactions.token(server.url("/token").toString(), "client", mapOf(
                "grant_type" to "authorization_code", "code" to "code", "redirect_uri" to "http://127.0.0.1/callback",
                "code_verifier" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", "resource" to "resource"))
            val response = AppAuthTransactions.exchange(androidx.test.core.app.ApplicationProvider.getApplicationContext(), ToolHttp(), request, jsonBody = true)
            assertEquals("Bearer", response["token_type"]?.jsonPrimitive?.content)
            assertEquals("account", response["account_id"]?.jsonPrimitive?.content)
            val sent = server.takeRequest()
            assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
            assertTrue(sent.body.readUtf8().contains("\"resource\":\"resource\""))
        } finally { server.shutdown() }
    }
}
