package org.starfall.generative.shared.subfeatures.auth.logic

import android.net.Uri
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.*

internal suspend fun awaitLoopbackAuthorizationCode(
    redirectUri: String,
    url: String,
    state: String,
    openBrowser: suspend (String) -> Unit
): String = withContext(Dispatchers.IO) {
    val redirect = Uri.parse(redirectUri)
    val callbackPort = redirect.port
    // A redirect such as http://127.0.0.1:PORT has no path, but browsers request "/"; compare against "/".
    val callbackPath = redirect.path?.takeIf { it.isNotEmpty() } ?: "/"
    ServerSocket().use { server ->
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), callbackPort))
        server.soTimeout = 1000
        openBrowser(url)
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                it.soTimeout = 2000
                val line = try {
                    val reader = it.getInputStream().bufferedReader()
                    val readDeadline = minOf(deadline, System.currentTimeMillis() + 2000)
                    var total = 0
                    suspend fun readLine(): String = buildString {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            require(System.currentTimeMillis() < readDeadline) { "OAuth callback read timed out" }
                            val c = reader.read()
                            require(c >= 0) { "Incomplete OAuth callback" }
                            if (c == 10) break
                            if (c != 13) append(c.toChar())
                            require(++total <= 16384 && length <= 8192) { "OAuth callback is too large" }
                        }
                    }
                    val requestLine = readLine()
                    while (readLine().isNotEmpty()) { /* Consume bounded HTTP headers. */ }
                    requestLine
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } ?: return@use
                val fields = line.split(' ')
                val uri = Uri.parse("http://127.0.0.1" + fields.getOrNull(1).orEmpty())
                val valid = fields.size == 3 && fields[0] == "GET" &&
                    fields[2] in listOf("HTTP/1.0", "HTTP/1.1") && uri.path == callbackPath && uri.getQueryParameter("state") == state
                val code = uri.getQueryParameter("code")
                val success = valid && !code.isNullOrBlank() && uri.getQueryParameter("error") == null
                val body = if (success) "Authorization complete. Return to Generative." else "Invalid authorization callback."
                runCatching { it.getOutputStream().write("HTTP/1.1 ${if (success) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray()) }
                if (valid && uri.getQueryParameter("error") != null) error("OAuth authorization was denied")
                if (success) return@withContext code
            }
        }
        error("Authorization timed out")
    }
}

