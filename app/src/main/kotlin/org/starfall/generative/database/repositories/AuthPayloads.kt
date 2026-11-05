package org.starfall.generative.database.repositories

import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import org.starfall.generative.shared.model.McpAuthorization
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.provider.ProviderApiKey
import org.starfall.generative.shared.model.provider.ProviderOAuthAccount

/** Encrypted provider secrets. Non-secret model configuration is stored in relational rows. */
@Serializable
internal data class ProviderAuthPayload(
    val authorization: Authorization,
    val headers: Map<String, String>,
    val httpProxy: Map<String, String>,
    val socksProxy: Map<String, String>,
    val apiKeys: List<ProviderApiKey>,
    val oauthAccounts: List<ProviderOAuthAccount>,
    // Version 2 stores the exact original endpoint, never just a query/fragment suffix.
    val privateBaseUrl: String? = null,
    val privateCustomListModelsUrl: String? = null
)

/** Encrypted MCP headers, authorization and any credential-bearing URL component. */
@Serializable
internal data class McpAuthPayload(
    val headers: Map<String, String>,
    val authorization: McpAuthorization,
    val privateUrl: String? = null
)

internal inline fun <reified T> decodeEndpointAuth(
    owner: String,
    encryptedPayload: String,
    payloadVersion: Int,
    cipherVersion: Int,
    state: String
): T = try {
    check(state == "READY") { "Authentication record is not ready." }
    check(payloadVersion in 1..2 && cipherVersion == 1) { "Unsupported authentication format." }
    json.decodeFromString<T>(org.starfall.generative.integrations.security.SecretCipher.decryptStrict(encryptedPayload))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw IllegalStateException("$owner credentials are unreadable. Re-enter credentials or restore access on the original device. Saved data has been retained.", e)
}
