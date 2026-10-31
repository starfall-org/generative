package org.starfall.generative

import android.app.Instrumentation
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.integrations.modelapis.account.AccountProviderAdapter
import org.starfall.generative.integrations.modelapis.devin.DevinAdapter
import org.starfall.generative.integrations.modelapis.devin.DevinOAuthProtocol
import org.starfall.generative.integrations.modelapis.geminicli.GeminiCliAdapter
import org.starfall.generative.integrations.modelapis.geminicli.GeminiCliOAuthProtocol
import org.starfall.generative.shared.files.AttachmentResolver
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.defaultAuthorization
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.auth.OAuthBrowserActivity

/**
 * Tests real Android OAuth browser handoff on a connected device. These smoke checks open the
 * *actual provider* authorization URL, verify PKCE/redirect and then CANCEL the pending login.
 * No Google/Devin password or third-party consent is entered by instrumentation.
 *
 * Explicit opt-in: adb shell am instrument -w -e liveOAuth true \
 *   -e class org.starfall.generative.GeminiCliDevinLoginDeviceTest ...
 */
@RunWith(AndroidJUnit4::class)
class GeminiCliDevinLoginDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun provider(type: ModelApiType) = ModelProvider(
        id = "oauth-device-smoke-${UUID.randomUUID()}", name = "OAuth smoke",
        type = type, baseUrl = type.defaultBaseUrl, auth = type.defaultAuthorization()
    )

    private fun adapter(type: ModelApiType): AccountProviderAdapter {
        val attachments = AttachmentResolver(context)
        return when (type) {
            ModelApiType.GEMINI_CLI -> GeminiCliAdapter(context, attachments)
            ModelApiType.DEVIN -> DevinAdapter(context, attachments)
            else -> error("Unexpected provider type")
        }
    }

    private fun requireLiveDevice() {
        assumeTrue("Live provider login tests require explicit -e liveOAuth true",
            InstrumentationRegistry.getArguments().getString("liveOAuth") == "true")
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        assertFalse("Unlock the device before browser login smoke tests", keyguard.isKeyguardLocked)
    }

    @Test fun geminiCliOpensRealGoogleOAuthWithS256AndBoundLocalCallback() = runBlocking {
        requireLiveDevice()
        val uri = launchAndCancel(ModelApiType.GEMINI_CLI, GeminiCliOAuthProtocol.CALLBACK_PORT)
        assertEquals("https", uri.scheme)
        assertEquals("accounts.google.com", uri.host)
        assertEquals("offline", uri.getQueryParameter("access_type"))
        assertEquals("consent", uri.getQueryParameter("prompt"))
        assertEquals(GeminiCliOAuthProtocol.CLIENT_ID, uri.getQueryParameter("client_id"))
        assertEquals(GeminiCliOAuthProtocol.SCOPE, uri.getQueryParameter("scope"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals(43, uri.getQueryParameter("code_challenge")?.length)
        assertEquals("http://127.0.0.1:51274/oauth2callback", uri.getQueryParameter("redirect_uri"))
        assertNotNull(uri.getQueryParameter("state"))
        assertNull(uri.getQueryParameter("code_verifier"))
    }

    @Test fun devinOpensRealCognitionOAuthWithS256AndBoundLocalCallback() = runBlocking {
        requireLiveDevice()
        val uri = launchAndCancel(ModelApiType.DEVIN, 53389)
        assertEquals("https", uri.scheme)
        assertEquals("app.devin.ai", uri.host)
        assertEquals("/auth/cli/continue", uri.path)
        assertEquals("select_account", uri.getQueryParameter("prompt"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals(43, uri.getQueryParameter("code_challenge")?.length)
        assertEquals(DevinOAuthProtocol.REDIRECT_URI, uri.getQueryParameter("redirect_uri"))
        assertNotNull(uri.getQueryParameter("state"))
        assertNull(uri.getQueryParameter("code_verifier"))
    }

    private suspend fun launchAndCancel(type: ModelApiType, port: Int): Uri {
        val loginAdapter = adapter(type)
        val testProvider = provider(type)
        val monitor = instrumentation.addMonitor(OAuthBrowserActivity::class.java.name, null, false)
        val pending = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { loginAdapter.authorize(testProvider) }
        return try {
            val activity = withContext(Dispatchers.IO) {
                instrumentation.waitForMonitorWithTimeout(monitor, 20_000)
            }
            assertNotNull("OAuth browser activity did not open on the actual device", activity)
            val value = activity!!.intent.getStringExtra(OAuthBrowserActivity.URL)
            assertTrue("Authorization must go to HTTPS", value?.startsWith("https://") == true)
            assertFalse("OAuth is expected to remain pending without user consent", pending.isCompleted)
            Uri.parse(value)
        } finally {
            pending.cancelAndJoin()
            loginAdapter.clearCredentials(testProvider.id)
            instrumentation.removeMonitor(monitor)
            // Real loopback listener must be unbound after cancellation, not leaked to other sessions.
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("127.0.0.1", port))
                assertEquals(port, socket.localPort)
            }
        }
    }

    /**
     * Interactive end-to-end login is NOT part of the unattended test suite. Run only when the
     * device owner is present and explicitly enables -e completeLiveOAuth true. This verifies
     * actual OAuth callback/token persistence, not just browser handoff.
     */
    @Test fun interactiveGoogleLoginCompletesAndPersistsAccount() = runBlocking {
        requireLiveDevice()
        assumeTrue("Interactive approval requires -e completeLiveOAuth true",
            InstrumentationRegistry.getArguments().getString("completeLiveOAuth") == "true")
        completeManualLogin(ModelApiType.GEMINI_CLI)
    }

    @Test fun interactiveDevinLoginCompletesAndPersistsAccount() = runBlocking {
        requireLiveDevice()
        assumeTrue("Interactive approval requires -e completeLiveOAuth true",
            InstrumentationRegistry.getArguments().getString("completeLiveOAuth") == "true")
        completeManualLogin(ModelApiType.DEVIN)
    }

    private suspend fun completeManualLogin(type: ModelApiType) {
        val p = provider(type)
        val a = adapter(type)
        try {
            val signed = kotlinx.coroutines.withTimeout(150_000) { a.authorize(p).getOrThrow() }
            assertEquals(type, signed.type)
            assertTrue("Authorization marker must be stored", signed.auth.value?.startsWith("account:") == true)
            assertEquals(type, a.prepareAuthenticatedProvider(signed).type)
            assertTrue("Encrypted account record must be persisted", a.exportCredentials().containsKey(p.id))
        } finally {
            a.clearCredentials(p.id)
        }
    }
}
