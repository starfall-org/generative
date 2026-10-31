package org.starfall.generative

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.integrations.modelapis.account.AccountTokenStore
import org.starfall.generative.integrations.modelapis.devin.DevinAdapter
import org.starfall.generative.integrations.modelapis.devin.DevinOAuthProtocol
import org.starfall.generative.shared.files.AttachmentResolver
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.defaultAuthorization
import org.starfall.generative.shared.model.provider.ModelProvider

/** Exercises the real Android loopback callback, Devin token exchange and credential storage. */
@RunWith(AndroidJUnit4::class)
class DevinOAuthCallbackDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun callback(path: String): String = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", 53389).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write((
                "GET $path HTTP/1.1\r\nHost: 127.0.0.1:53389\r\nConnection: close\r\n\r\n"
            ).toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
    }

    @Test fun validDevinCallbackCompletesCodeExchangeAndStoresAuthenticatedAccount() = runBlocking {
        MockWebServer().use { api ->
            api.enqueue(MockResponse().setBody("""{"token":"eyJtest_session"}"""))
            api.enqueue(MockResponse().setBody("""{"user_name":"device-test","user_id":"u123","email":"test@example.invalid"}"""))
            val opened = CompletableDeferred<String>()
            val id = "devin-callback-test-${UUID.randomUUID()}"
            val provider = ModelProvider(id, "Device test", ModelApiType.DEVIN,
                baseUrl = ModelApiType.DEVIN.defaultBaseUrl,
                auth = ModelApiType.DEVIN.defaultAuthorization())
            val adapter = DevinAdapter(context, AttachmentResolver(context),
                loginApiBaseUrl = api.url("/").toString().trimEnd('/'),
                openAuthorizationBrowser = { _, url -> opened.complete(url) })
            try {
                val pending = async(Dispatchers.IO) { adapter.authorize(provider) }
                try {
                    val url = Uri.parse(withTimeout(10_000) { opened.await() })
                    assertEquals("app.devin.ai", url.host)
                    assertEquals("S256", url.getQueryParameter("code_challenge_method"))
                    assertEquals(43, url.getQueryParameter("code_challenge")?.length)
                    assertEquals(DevinOAuthProtocol.REDIRECT_URI, url.getQueryParameter("redirect_uri"))
                    val state = requireNotNull(url.getQueryParameter("state"))
                    assertTrue(callback("/callback?state=incorrect&code=invalid").startsWith("HTTP/1.1 400"))
                    assertFalse(pending.isCompleted)
                    assertTrue(callback("/callback?state=$state&code=real_code").startsWith("HTTP/1.1 200"))
                    val authorized = withTimeout(10_000) { pending.await() }.getOrThrow()
                    assertEquals("account:devin:v1", authorized.auth.value)
                    assertEquals("device-test", authorized.auth.key)
                    val saved = AccountTokenStore(context, "devin").load(id)
                    assertEquals("devin-session-token\$eyJtest_session", saved?.accessToken)
                    assertEquals("test@example.invalid", saved?.email)
                    val exchange = api.takeRequest()
                    assertEquals("/auth/cli/token", exchange.path)
                    val request = Json.parseToJsonElement(exchange.body.readUtf8()).jsonObject
                    assertEquals("real_code", request["code"]?.jsonPrimitive?.content)
                    assertEquals(86, request["code_verifier"]?.jsonPrimitive?.content?.length)
                    val profile = api.takeRequest()
                    assertEquals("/v3/self", profile.path)
                    assertEquals("Bearer devin-session-token\$eyJtest_session", profile.getHeader("Authorization"))
                } finally { pending.cancel(); pending.join() }
            } finally { adapter.clearCredentials(id) }
        }
    }
}
