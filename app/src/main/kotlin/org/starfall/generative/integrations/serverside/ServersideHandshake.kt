package org.starfall.generative.integrations.serverside

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.starfall.generative.integrations.remote.RemoteCommandResult

/** SSH bootstrap only. A ticket is not an authenticated TLS connection or a running chat session. */
data class ServersideInvitation(
    val endpoint: String,
    val fingerprint: String,
    val ticket: String,
    val expires: Long,
)

class ServersideHandshakeException(val cliMissing: Boolean, message: String) : Exception(message)

fun RemoteCommandResult.isServersideMissing(): Boolean =
    exitCode == 73 && output.trim() == "GENERATIVE_SERVERSIDE_MISSING"

fun parseServersideHandshake(result: RemoteCommandResult, nowSeconds: Long): ServersideInvitation {
    if (result.exitCode != 0) {
        throw ServersideHandshakeException(
            cliMissing = result.isServersideMissing(),
            message = result.error.trim().take(4096).ifBlank { "SSH command exited with code ${result.exitCode}." }
        )
    }
    // Invitations contain short-lived secrets: never include their raw payload in errors or logs.
    return try {
        require(result.output.length <= 16384)
        val obj = Json.parseToJsonElement(result.output.trim()).jsonObject
        require(obj["protocol"]?.jsonPrimitive?.intOrNull == 4)
        val endpoint = obj["endpoint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val fingerprint = obj["fingerprint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val ticket = obj["ticket"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expires = obj["expires"]?.jsonPrimitive?.longOrNull ?: 0
        require(endpoint.isNotBlank() && endpoint.length <= 1024)
        val address = java.net.URI("tcp://$endpoint")
        require(address.host != null && address.port in 1..65535)
        require(address.rawUserInfo == null && address.rawQuery == null && address.rawFragment == null)
        require(address.rawPath.isNullOrEmpty())
        require(Regex("[0-9a-fA-F]{64}").matches(fingerprint))
        require(ticket.isNotBlank() && ticket.length <= 4096)
        require(expires > nowSeconds)
        ServersideInvitation(endpoint, fingerprint, ticket, expires)
    } catch (_: Exception) {
        throw ServersideHandshakeException(false, "Invalid, expired, or unsupported Serverside invitation.")
    }
}
