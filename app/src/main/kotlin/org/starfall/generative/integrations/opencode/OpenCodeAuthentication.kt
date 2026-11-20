package org.starfall.generative.integrations.opencode

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** OpenCode credentials must only be persisted through an encrypted host-side store. */
class OpenCodeCredentials(val username: String, internal val password: String) {
    init {
        require(username.isNotBlank() && ':' !in username && '\n' !in username && '\r' !in username)
        require(password.isNotEmpty() && '\n' !in password && '\r' !in password)
    }
    internal fun authorization(): String = Credentials.basic(username, password, Charsets.UTF_8)
    override fun toString(): String = "OpenCodeCredentials(username=$username, password=[redacted])"
}

/** A one-time OpenCode v2 pairing link; its code must never be stored or logged. */
class OpenCodePairingLink private constructor(internal val url: HttpUrl, val endpoint: HttpUrl) {
    override fun toString(): String = "OpenCodePairingLink(endpoint=$endpoint, code=[redacted])"

    companion object {
        fun parse(value: String): OpenCodePairingLink {
            val url = try { value.trim().toHttpUrl() } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid OpenCode pairing link")
            }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            require(url.pathSegments.size == 3 && url.pathSegments[0] == "auth" &&
                url.pathSegments[1] == "connect" && url.pathSegments[2].isNotBlank())
            val endpoint = url.newBuilder().encodedPath("/").build()
            return OpenCodePairingLink(url, endpoint)
        }
    }
}

/** OpenCode v2 API authentication, independent of the app's Generative Server RPC protocol. */
class OpenCodeAuthentication(
    endpoint: String,
    client: OkHttpClient = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(),
) {
    internal val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    val base = try { endpoint.trim().toHttpUrl() } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid OpenCode server URL")
    }.also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }

    /** Exchange a single-use pairing link for a session token used as a Basic password. */
    suspend fun pair(link: OpenCodePairingLink): OpenCodeCredentials {
        require(link.endpoint.scheme == base.scheme && link.endpoint.host == base.host && link.endpoint.port == base.port)
        val body = request(Request.Builder().url(link.url).header("Accept", "application/json").get().build())
        val token = try {
            Json.parseToJsonElement(body).jsonObject.getValue("token").jsonPrimitive
                .takeIf { it.isString }?.content ?: throw IllegalArgumentException()
        }
        catch (_: Exception) { throw OpenCodeProtocolException("Invalid pairing response") }
        if (token.isBlank()) throw OpenCodeProtocolException("Invalid pairing response")
        return OpenCodeCredentials("opencode", token)
    }

    /** Verify that the credentials reach an OpenCode v2 server. */
    suspend fun verify(credentials: OpenCodeCredentials): String {
        val url = base.newBuilder().addPathSegments("api/info").build()
        val body = request(Request.Builder().url(url).header("Accept", "application/json")
            .header("Authorization", credentials.authorization()).get().build())
        return try {
            Json.parseToJsonElement(body).jsonObject.getValue("version").jsonPrimitive
                .takeIf { it.isString && it.content.isNotBlank() }?.content ?: throw IllegalArgumentException()
        } catch (_: Exception) { throw OpenCodeProtocolException("Invalid OpenCode server response") }
    }

    internal suspend fun request(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (it.code !in 200..299) throw OpenCodeHttpException(it.code)
                        val source = it.body.source()
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) throw OpenCodeProtocolException("Response too large")
                        source.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}

class OpenCodeHttpException(val status: Int) : IOException("OpenCode HTTP error $status")
class OpenCodeProtocolException(message: String) : IOException(message)
private const val MAX_RESPONSE_BYTES = 8L shl 20
