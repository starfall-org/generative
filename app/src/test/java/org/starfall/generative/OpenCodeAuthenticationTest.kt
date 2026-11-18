package org.starfall.generative

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.opencode.OpenCodeAuthentication
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import org.starfall.generative.integrations.opencode.OpenCodePairingLink
import org.starfall.generative.integrations.opencode.OpenCodeProtocolException

class OpenCodeAuthenticationTest {
    @Test fun basicCredentialsAreSentOnlyToTheServerInfoEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"version":"2.2.5"}"""))
            val credentials = OpenCodeCredentials("alice", "secret")
            assertEquals("2.2.5", OpenCodeAuthentication(server.url("/").toString()).verify(credentials))
            val request = server.takeRequest()
            assertEquals("/api/info", request.path)
            assertEquals(Credentials.basic("alice", "secret", Charsets.UTF_8), request.getHeader("Authorization"))
            assertFalse(credentials.toString().contains("secret"))
        }
    }

    @Test fun pairingLinkIsExchangedForASessionToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"session-token"}"""))
            server.enqueue(MockResponse().setBody("""{"version":"2.2.5"}"""))
            val link = OpenCodePairingLink.parse(server.url("/auth/connect/one-time-code").toString())
            val api = OpenCodeAuthentication(link.endpoint.toString())
            val credentials = api.pair(link)
            assertEquals("2.2.5", api.verify(credentials))
            val pair = server.takeRequest()
            assertEquals("/auth/connect/one-time-code", pair.path)
            assertNull(pair.getHeader("Authorization"))
            assertEquals("application/json", pair.getHeader("Accept"))
            assertEquals(Credentials.basic("opencode", "session-token", Charsets.UTF_8),
                server.takeRequest().getHeader("Authorization"))
            assertFalse(link.toString().contains("one-time-code"))
        }
    }

    @Test fun htmlResponseIsNotTreatedAsAnOpenCodeServer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>login</html>"))
            try {
                OpenCodeAuthentication(server.url("/").toString()).verify(OpenCodeCredentials("opencode", "secret"))
                fail("Expected invalid server response")
            } catch (_: OpenCodeProtocolException) { }
        }
    }

    @Test fun rejectsLinksWithUnexpectedPathsOrEmbeddedCredentials() {
        for (url in listOf("https://user:password@example.com/auth/connect/token", "https://example.com/other/token",
            "https://example.com/auth/connect/token?extra=secret")) {
            assertThrows(IllegalArgumentException::class.java) { OpenCodePairingLink.parse(url) }
        }
    }
}
