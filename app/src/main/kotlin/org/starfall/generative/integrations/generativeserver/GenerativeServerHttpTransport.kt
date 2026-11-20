package org.starfall.generative.integrations.generativeserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private fun validatedEndpoint(endpoint: String): HttpUrl = endpoint.toHttpUrl().also {
    require(it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null)
}

/** Validate persisted trust settings without creating a transport or HTTP client. */
fun validateGenerativeServerHttpConnection(endpoint: String, pin: String) {
    CertificatePinner.Builder().add(validatedEndpoint(endpoint).host, pin).build()
}

/** Pinned HTTPS; suspending calls are cancellable and never replay ambiguous writes. */
class GenerativeServerHttpTransport private constructor(endpoint: String, pin: String, private val invitation: GenerativeServerInvitation?) : GenerativeServerWire {
    constructor(invitation: GenerativeServerInvitation) : this(invitation.endpoint.toString(), invitation.pin, invitation)
    constructor(endpoint: String, pin: String) : this(endpoint, pin, null)
    private val base = validatedEndpoint(endpoint)
    internal val certificatePinner = CertificatePinner.Builder().add(base.host, pin).build()
    private val client = OkHttpClient.Builder()
        .certificatePinner(certificatePinner)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .readTimeout(30, TimeUnit.SECONDS).build()

    fun pair(): PairedIdentity = decodePair(post("/v1/pair", pairingBody()))
    suspend fun pairAsync(): PairedIdentity = decodePair(postAsync("/v1/pair", pairingBody()))

    private fun pairingBody(): String {
        val invitation = requireNotNull(invitation) { "Pairing requires an invitation" }
        return GenerativeServerCodec.json.encodeToString(PairRequest(invitation.code))
    }

    private fun decodePair(raw: String): PairedIdentity {
        val invitation = requireNotNull(invitation) { "Pairing requires an invitation" }
        val result = decode<PairResponse>(raw)
        val returned = try { result.endpoint.toHttpUrl() } catch (_: Exception) { throw GenerativeServerProtocolException("Invalid pairing endpoint") }
        if (result.protocol != 5 || result.clientId.isBlank() || result.secret.isBlank() || result.serverId.isBlank() || result.tlsPin != invitation.pin ||
            returned.scheme != "https" || returned.host != base.host || returned.port != base.port ||
            returned.username.isNotEmpty() || returned.password.isNotEmpty() || returned.encodedPath != "/" ||
            returned.query != null || returned.fragment != null) {
            throw GenerativeServerProtocolException("Pairing identity does not match invitation")
        }
        return PairedIdentity(result.clientId, result.secret, result.serverId)
    }

    override fun revoke(token: AccessToken) = decodeRevocation(execute(revokeRequest(token)))
    override suspend fun revokeAsync(token: AccessToken) = decodeRevocation(executeAsync(revokeRequest(token)))

    private fun revokeRequest(token: AccessToken): Request = Request.Builder()
        .url(base.newBuilder().encodedPath("/v1/revoke").build())
        .header("Authorization", "Bearer ${token.value}")
        .post("".toRequestBody("application/json".toMediaType())).build()

    private fun decodeRevocation(response: HttpResult) {
        if (response.status !in 200..299) throw GenerativeServerHttpException(response.status)
        if (!decode<RevokeResponse>(response.body).revoked) {
            throw GenerativeServerProtocolException("Server did not confirm revocation")
        }
    }

    override fun authenticate(identity: PairedIdentity): AccessToken = decodeAuth(post("/v1/auth", authBody(identity)), identity)
    override suspend fun authenticateAsync(identity: PairedIdentity): AccessToken = decodeAuth(postAsync("/v1/auth", authBody(identity)), identity)

    private fun authBody(identity: PairedIdentity): String =
        GenerativeServerCodec.json.encodeToString(AuthRequest(identity.clientId, identity.secret))

    private fun decodeAuth(raw: String, identity: PairedIdentity): AccessToken {
        val result = decode<AuthResponse>(raw)
        if (result.clientId != identity.clientId || result.token.isBlank() || result.expiresIn <= 0) throw GenerativeServerProtocolException("Invalid authentication response")
        return AccessToken(result.token, result.expiresIn)
    }

    override fun rpc(token: AccessToken, request: RpcRequest): RpcReply = decodeRpc(execute(rpcRequest(token, request)), request)
    override suspend fun rpcAsync(token: AccessToken, request: RpcRequest): RpcReply = decodeRpc(executeAsync(rpcRequest(token, request)), request)

    private fun rpcRequest(token: AccessToken, request: RpcRequest): Request = Request.Builder()
        .url(base.newBuilder().encodedPath("/v1/rpc").build())
        .header("Authorization", "Bearer ${token.value}")
        .post(GenerativeServerCodec.encode(request).toRequestBody("application/json".toMediaType())).build()

    private fun decodeRpc(response: HttpResult, request: RpcRequest): RpcReply {
        if (response.status == 401 && isTransportUnauthorized(response.body)) throw GenerativeServerHttpException(401)
        return GenerativeServerCodec.decodeReply(response.body, request.id)
    }

    override fun events(token: AccessToken, sessionId: String, after: Long): List<SessionEvent> =
        decodeEvents(execute(eventsRequest(token, sessionId, after)))
    override suspend fun eventsAsync(token: AccessToken, sessionId: String, after: Long): List<SessionEvent> =
        decodeEvents(executeAsync(eventsRequest(token, sessionId, after)))

    private fun eventsRequest(token: AccessToken, sessionId: String, after: Long): Request {
        require(sessionId.isNotBlank() && after >= 0)
        val url = base.newBuilder().encodedPath("/v1/events").addQueryParameter("session_id", sessionId)
            .addQueryParameter("after", after.toString()).build()
        return Request.Builder().url(url).header("Authorization", "Bearer ${token.value}")
            .header("Accept", "application/json").get().build()
    }

    private fun decodeEvents(response: HttpResult): List<SessionEvent> {
        if (response.status == 401) throw GenerativeServerHttpException(401)
        if (response.status !in 200..299) {
            val error = try { GenerativeServerCodec.json.decodeFromString<RpcReply>(response.body).error } catch (_: Exception) { null }
            if (error != null) throw GenerativeServerRpcException(error)
            throw GenerativeServerHttpException(response.status)
        }
        return decode<EventPage>(response.body).events
    }

    private fun isTransportUnauthorized(body: String): Boolean = try {
        val reply = GenerativeServerCodec.json.decodeFromString<RpcReply>(body)
        reply.id.isEmpty() && !reply.ok && reply.error?.code == "unauthorized"
    } catch (_: Exception) { false }

    private data class HttpResult(val status: Int, val body: String)

    private fun postRequest(path: String, payload: String): Request = Request.Builder()
        .url(base.newBuilder().encodedPath(path).build())
        .post(payload.toRequestBody("application/json".toMediaType())).build()

    private fun requireHttpSuccess(response: HttpResult): String {
        if (response.status !in 200..299) throw GenerativeServerHttpException(response.status)
        return response.body
    }

    private fun post(path: String, payload: String): String = requireHttpSuccess(execute(postRequest(path, payload)))
    private suspend fun postAsync(path: String, payload: String): String = requireHttpSuccess(executeAsync(postRequest(path, payload)))

    private fun readResponse(response: Response): HttpResult = response.use {
        val source = it.body.source()
        source.request((4L shl 20) + 1)
        if (source.buffer.size > 4L shl 20) throw GenerativeServerProtocolException("Response too large")
        HttpResult(it.code, source.readUtf8())
    }

    private fun execute(request: Request): HttpResult = readResponse(client.newCall(request).execute())

    /** Coroutine cancellation closes the socket, including a waiting 20-second event poll. */
    private suspend fun executeAsync(request: Request): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = readResponse(response)
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private inline fun <reified T> decode(raw: String): T = try {
        GenerativeServerCodec.json.decodeFromString<T>(raw)
    } catch (_: Exception) { throw GenerativeServerProtocolException("Invalid server response") }
}

class PairedIdentity(val clientId: String, internal val secret: String, val serverId: String) {
    override fun toString(): String = "PairedIdentity(clientId=$clientId, serverId=$serverId, secret=[redacted])"
}
class AccessToken(internal val value: String, val expiresInSeconds: Long) {
    override fun toString(): String = "AccessToken([redacted])"
}
class GenerativeServerHttpException(val status: Int) : Exception("Generative Server HTTP error $status")

@Serializable private data class PairRequest(val code: String)
@Serializable private data class PairResponse(val protocol: Int, @SerialName("client_id") val clientId: String,
    val secret: String, @SerialName("server_id") val serverId: String, val endpoint: String, @SerialName("tls_pin") val tlsPin: String)
@Serializable private data class RevokeResponse(val revoked: Boolean)
@Serializable private data class AuthRequest(@SerialName("client_id") val clientId: String, val secret: String)
@Serializable private data class AuthResponse(@SerialName("client_id") val clientId: String, val token: String, @SerialName("expires_in") val expiresIn: Long)

@Serializable private data class EventPage(val events: List<SessionEvent>)
