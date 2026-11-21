package org.starfall.generative.integrations.generativeserver

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An authenticated wire exposes 401 only when the transport rejected the token before dispatch. */
interface GenerativeServerWire {
    fun close() = Unit
    suspend fun <T> withConnection(block: suspend () -> T): T = block()
    fun authenticate(identity: PairedIdentity): AccessToken
    fun rpc(token: AccessToken, request: RpcRequest): RpcReply
    fun events(token: AccessToken, sessionId: String, after: Long): List<SessionEvent>
    // Non-HTTP adapters can supply their own suspending I/O. HTTP cancels the
    // underlying OkHttp call when the owning coroutine is cancelled.
    suspend fun authenticateAsync(identity: PairedIdentity): AccessToken = withContext(Dispatchers.IO) { authenticate(identity) }
    suspend fun rpcAsync(token: AccessToken, request: RpcRequest): RpcReply = withContext(Dispatchers.IO) { rpc(token, request) }
    suspend fun eventsAsync(token: AccessToken, sessionId: String, after: Long): List<SessionEvent> = withContext(Dispatchers.IO) { events(token, sessionId, after) }
    suspend fun revokeAsync(token: AccessToken) = withContext(Dispatchers.IO) { revoke(token) }
    /** An unsupported transport must fail closed and never silently remove local credentials. */
    fun revoke(token: AccessToken): Unit = throw UnsupportedOperationException("Revocation unsupported by transport")
}

/** Credentials and bearer live only in memory; durable storage is owned by the caller. */
class GenerativeServerClient(private val wire: GenerativeServerWire, private val identity: PairedIdentity,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutex = Mutex()
    private var cached: AccessToken? = null
    private var refreshAt = 0L

    private suspend fun token(invalidate: AccessToken? = null): AccessToken = mutex.withLock {
        if (invalidate != null && cached === invalidate) { cached = null; refreshAt = 0 }
        val current = cached
        if (current != null && clockMillis() < refreshAt) return@withLock current
        val fresh = wire.authenticateAsync(identity)
        require(fresh.expiresInSeconds > 0)
        val lifetime = fresh.expiresInSeconds.coerceAtMost(86400) * 1000
        refreshAt = clockMillis() + (lifetime - minOf(30000, lifetime / 10))
        cached = fresh
        fresh
    }

    private suspend fun <T> authenticated(block: suspend (AccessToken) -> T): T {
        val first = token()
        try { return block(first) }
        catch (e: GenerativeServerHttpException) {
            if (e.status != 401) throw e
            val replacement = token(first)
            return block(replacement)
        }
    }

    /** Network errors are propagated, never replayed: the operation may have executed. */
    suspend fun rpc(request: RpcRequest): RpcReply = authenticated { wire.rpcAsync(it, request) }

    fun close() = wire.close()

    internal suspend fun <T> withConnection(block: suspend () -> T): T = wire.withConnection(block)

    /** Destructive, non-replayable identity revocation; only a confirmed reply is success. */
    suspend fun revoke() {
        val bearer = token()
        wire.revokeAsync(bearer)
        mutex.withLock {
            if (cached === bearer) { cached = null; refreshAt = 0 }
        }
    }

    /** Reconnects only the read-only event poll. A gap requires a fresh authoritative snapshot. */
    fun events(sessionId: String, after: Long): Flow<SessionEvent> = flow {
        require(sessionId.isNotBlank() && after >= 0)
        var cursor = after
        var retryDelay = 500L
        while (true) {
            val batch = try { authenticated { wire.eventsAsync(it, sessionId, cursor) } }
            catch (e: CancellationException) { throw e }
            catch (e: SSLException) { throw e }
            catch (e: IOException) { delay(retryDelay); retryDelay = (retryDelay * 2).coerceAtMost(15_000); continue }
            catch (e: GenerativeServerRpcException) {
                if (e.rpcError.code == "event_gap") throw GenerativeServerEventGapException()
                throw e
            }
            retryDelay = 500
            for (event in batch) {
                if (event.sessionId != sessionId || event.seq != cursor + 1 || event.kind == "event_gap") {
                    throw GenerativeServerEventGapException()
                }
                emit(event)
                cursor = event.seq
            }
        }
    }
}

class GenerativeServerEventGapException : Exception("Event history is incomplete; reload the session")

/** Reject stale or duplicate snapshots; never replace a newer durable revision. */
class GenerativeServerRevision(initial: Long = 0) {
    var value: Long = initial
        private set
    fun accept(revision: Long): Boolean {
        require(revision >= 0)
        if (revision <= value) return false
        value = revision
        return true
    }
}
