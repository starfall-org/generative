package org.starfall.generative

import org.starfall.generative.shared.subfeatures.auth.logic.*

import org.starfall.generative.integrations.modelapis.account.*

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.helpers.networking.text
import java.net.ServerSocket
import java.net.Socket
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.auth.defaultAuthorization

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class OAuthLoopbackTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun prepareKeystore() = installTestAndroidKeyStore()

    private fun adapter(port: Int, tokenUrl: String, browser: CompletableDeferred<String>) =
        object : OAuthAccountAdapter(context, AttachmentResolver(context), ModelApiType.ANTIGRAVITY,
            "test-client", "https://example.com/authorize", tokenUrl, "scope",
            port, "/oauth-callback", callbackHost = "127.0.0.1") {
            override suspend fun openBrowser(url: String) { browser.complete(url) }
            override suspend fun prepareAuthenticatedProvider(provider: ModelProvider) = provider
            override suspend fun fetchModels(provider: ModelProvider) = listOf("chat")
        }

    private fun provider() = ModelProvider("loopback", "Test", ModelApiType.ANTIGRAVITY,
        baseUrl = ModelApiType.ANTIGRAVITY.defaultBaseUrl, auth = ModelApiType.ANTIGRAVITY.defaultAuthorization())

    private suspend fun request(port: Int, path: String): String = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
    }

    @Test fun callbackIsListeningBeforeBrowserOpensAndUsesSameRedirectForExchange() = runBlocking {
        MockWebServer().use { tokenServer ->
            tokenServer.enqueue(MockResponse().setBody("""{"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600}"""))
            val port = ServerSocket(0).use { it.localPort }
            val browser = CompletableDeferred<String>()
            val adapter = adapter(port, tokenServer.url("/token").toString(), browser)
            val pending = async(Dispatchers.IO) { adapter.authorize(provider()) }
            val url = Uri.parse(withTimeout(5000) { browser.await() })
            val redirect = Uri.parse(url.getQueryParameter("redirect_uri"))
            assertEquals("127.0.0.1", redirect.host)
            assertEquals(port, redirect.port)
            assertEquals("/oauth-callback", redirect.path)
            assertTrue(url.getQueryParameter("code_verifier") == null)
            assertEquals(43, url.getQueryParameter("code_challenge")!!.length)
            // The browser URL was issued only after bind; invalid requests do not consume authorization.
            assertTrue(request(port, "/favicon.ico").startsWith("HTTP/1.1 400"))
            assertTrue(request(port, "/oauth-callback?state=wrong&code=bad").startsWith("HTTP/1.1 400"))
            val state = url.getQueryParameter("state")!!
            assertTrue(request(port, "/oauth-callback?state=$state&code=valid-code").startsWith("HTTP/1.1 200"))
            val authorized = withTimeout(5000) { pending.await() }.getOrThrow()
            assertEquals("account:antigravity:v1", authorized.auth.value)
            val exchange = tokenServer.takeRequest().body.readUtf8()
            val fields = exchange.split("&").associate {
                val pair = it.split("=", limit = 2)
                java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
            }
            assertEquals(redirect.toString(), fields["redirect_uri"])
            assertEquals("valid-code", fields["code"])
            assertTrue(fields["code_verifier"]!!.length in 43..128)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(fields.getValue("code_verifier").toByteArray(Charsets.US_ASCII))
            assertEquals(url.getQueryParameter("code_challenge"),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress("127.0.0.1", port))
            }.use { assertEquals(port, it.localPort) }
            adapter.clearCredentials(provider().id)
        }
    }

    @Test fun cancellationClosesTheListeningPort() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val browser = CompletableDeferred<String>()
        val adapter = adapter(port, "https://example.com/token", browser)
        val pending = async(Dispatchers.IO) { adapter.authorize(provider()) }
        withTimeout(5000) { browser.await() }
        assertTrue(request(port, "/favicon.ico").startsWith("HTTP/1.1 400"))
        pending.cancel()
        try { withTimeout(5000) { pending.await() }; fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        // await() on a cancelled Deferred need not wait for finally, so join before rebinding.
        pending.join()
        ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress("127.0.0.1", port))
            }.use { assertEquals(port, it.localPort) }
    }
}
