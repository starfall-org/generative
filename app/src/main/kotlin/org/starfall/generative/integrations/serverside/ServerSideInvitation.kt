package org.starfall.generative.integrations.serverside

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** A pairing code is held in memory and must never be logged or persisted. */
class ServerSideInvitation private constructor(
    val endpoint: URI,
    val pin: String,
    internal val code: String,
) {
    override fun toString(): String = "ServerSideInvitation(endpoint=$endpoint, pin=$pin, code=[redacted])"

    companion object {
        fun parse(value: String): ServerSideInvitation {
            val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid invitation") }
            require(uri.scheme.equals("https", ignoreCase = true) && uri.host != null &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawPath == "/connect" && uri.port != 0) {
                "Invitation must use an HTTPS /connect URL"
            }
            val fragment = uri.rawFragment ?: throw IllegalArgumentException("Missing invitation fragment")
            val fields = mutableMapOf<String, String>()
            for (field in fragment.split('&')) {
                val parts = field.split('=', limit = 2)
                require(parts.size == 2) { "Invalid invitation fragment" }
                val key: String
                val decoded: String
                try {
                    key = URLDecoder.decode(parts[0], "UTF-8")
                    decoded = URLDecoder.decode(parts[1], "UTF-8")
                } catch (_: Exception) { throw IllegalArgumentException("Invalid invitation fragment") }
                require(key in setOf("code", "pin", "v") && fields.put(key, decoded) == null) { "Invalid invitation fragment" }
            }
            val code = fields["code"].orEmpty()
            val pin = fields["pin"].orEmpty()
            require(fields["v"] == "1" && code.isNotBlank() && code.length <= 1024 && pin.startsWith("sha256/")) { "Invalid invitation" }
            val digest = try { Base64.getDecoder().decode(pin.removePrefix("sha256/")) } catch (_: Exception) { throw IllegalArgumentException("Invalid invitation pin") }
            require(digest.size == 32) { "Invalid invitation pin" }
            val endpoint = URI("https", null, uri.host, uri.port, null, null, null)
            return ServerSideInvitation(endpoint, pin, code)
        }
    }
}
