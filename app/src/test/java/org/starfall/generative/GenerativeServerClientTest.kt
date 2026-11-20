package org.starfall.generative

import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.generativeserver.*

class GenerativeServerClientTest {
    private class Fake : GenerativeServerWire {
        var auth = 0
        var calls = 0
        var eventCalls = 0
        var rejectToken = false
        var failNetwork = false
        var gap = false
        var badAuth = false
        var revocations = 0
        var failRevocation = false
        override fun revoke(token: AccessToken) {
            revocations++
            if (failRevocation) throw IOException("revocation receipt lost")
        }
        override fun authenticate(identity: PairedIdentity): AccessToken { auth++; if (badAuth) throw GenerativeServerHttpException(401); return AccessToken("token$auth", 900) }
        override fun rpc(token: AccessToken, request: RpcRequest): RpcReply {
            calls++
            if (rejectToken && calls == 1) throw GenerativeServerHttpException(401)
            if (failNetwork) throw IOException("uncertain delivery")
            return RpcReply(request.id, true)
        }
        override fun events(token: AccessToken, sessionId: String, after: Long): List<SessionEvent> {
            eventCalls++
            if (gap) throw GenerativeServerRpcException(RpcError("event_gap", "gap"))
            return listOf(SessionEvent(sessionId, after + 1, kind = "delta"))
        }
    }
    private fun client(fake: Fake) = GenerativeServerClient(fake, PairedIdentity("client", "secret", "server"))
    @Test fun renewsOnlyRejectedBearerAndDoesNotReplayUncertainWrite() = runBlocking {
        val fake = Fake(); fake.rejectToken = true
        assertTrue(client(fake).rpc(RpcRequest("id", "session.run")).ok)
        assertEquals(2, fake.auth); assertEquals(2, fake.calls)
        val uncertain = Fake(); uncertain.failNetwork = true
        try { client(uncertain).rpc(RpcRequest("id", "session.run")); fail() } catch (_: IOException) { }
        assertEquals(1, uncertain.calls)
    }
    @Test fun revocationDoesNotReplayAfterAmbiguousDeliveryAndClearsCachedTokenOnSuccess() = runBlocking {
        val uncertain = Fake().apply { failRevocation = true }
        val failed = client(uncertain)
        try { failed.revoke(); fail("ambiguous revocation was silently accepted") } catch (_: IOException) { }
        assertEquals(1, uncertain.revocations)
        // Revocation failure must not invent a confirmed identity deletion.
        assertEquals(1, uncertain.auth)

        val confirmed = Fake()
        val successful = client(confirmed)
        assertTrue(successful.rpc(RpcRequest("before", "capabilities.get")).ok)
        assertEquals(1, confirmed.auth)
        successful.revoke()
        assertEquals(1, confirmed.revocations)
        // No bearer from the revoked identity may be reused.
        assertTrue(successful.rpc(RpcRequest("after", "capabilities.get")).ok)
        assertEquals(2, confirmed.auth)
    }

    @Test fun eventCursorAndGapRequireSnapshot() = runBlocking {
        val fake = Fake(); assertEquals(8, client(fake).events("session", 7).first().seq)
        fake.gap = true
        try { client(fake).events("session", 7).first(); fail() } catch (_: GenerativeServerEventGapException) { }
    }
    @Test fun rejectsStaleRevisions() {
        val rev = GenerativeServerRevision(3); assertFalse(rev.accept(2)); assertFalse(rev.accept(3)); assertTrue(rev.accept(4))
    }
    @Test fun invalidCredentialsDoNotSendRpc() = runBlocking {
        val fake = Fake(); fake.badAuth = true
        try { client(fake).rpc(RpcRequest("id", "session.run")); fail() } catch (_: GenerativeServerHttpException) { }
        assertEquals(0, fake.calls); assertEquals(1, fake.auth)
    }
    @Test fun cancellingEventObservationCancelsTheUnderlyingAsyncTransport() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val wire = object : GenerativeServerWire {
            override fun authenticate(identity: PairedIdentity) = AccessToken("bearer", 900)
            override fun rpc(token: AccessToken, request: RpcRequest) = RpcReply(request.id, true)
            override fun events(token: AccessToken, sessionId: String, after: Long) = error("Blocking transport should not be used")
            override suspend fun eventsAsync(token: AccessToken, sessionId: String, after: Long): List<SessionEvent> =
                suspendCancellableCoroutine { continuation ->
                    started.complete(Unit)
                    continuation.invokeOnCancellation { cancelled.complete(Unit) }
                }
        }
        val stream = launch { GenerativeServerClient(wire, PairedIdentity("client", "secret", "server")).events("chat", 0).first() }
        withTimeout(5000) { started.await() }
        stream.cancelAndJoin()
        withTimeout(5000) { cancelled.await() }
    }

    @Test fun wrongPinRejectsCertificate() {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(null as KeyStore?)
        val cert = (trust.trustManagers.filterIsInstance<X509TrustManager>().first()).acceptedIssuers.first()
        val transport = GenerativeServerHttpTransport("https://example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        try { transport.certificatePinner.check("example.com", listOf(cert)); fail("accepted wrong pin") }
        catch (_: SSLPeerUnverifiedException) { }
    }
}
