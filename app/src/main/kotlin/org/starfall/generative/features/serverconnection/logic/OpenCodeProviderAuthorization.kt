package org.starfall.generative.features.serverconnection.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.provider.ModelProvider

/** Translates OpenCode integration methods to the ordinary provider editor's callbacks. */
internal class OpenCodeProviderAuthorization private constructor(
    private val service: ServerConnections,
    private val connectionId: String,
    private val integrationId: String,
    private val canConnectKey: Boolean,
    private val oauthMethod: String?
) {
    val canAuthorize: Boolean get() = oauthMethod != null

    fun initialProvider(provider: ModelProvider): ModelProvider =
        if (!canConnectKey && canAuthorize) provider.copy(auth = Authorization(AuthMethod.OAUTH)) else provider

    suspend fun connectKey(provider: ModelProvider) {
        require(canConnectKey && provider.auth.method == AuthMethod.PLATFORM_DEFAULT && provider.auth.token.isNotBlank())
        service.connectIntegrationKey(connectionId, integrationId, provider.auth.token, answer = null, directory = null)
    }

    suspend fun authorize(provider: ModelProvider, openUri: (String) -> Unit): ModelProvider {
        val method = requireNotNull(oauthMethod)
        val attempt = service.beginIntegrationOAuth(connectionId, integrationId, method, answer = null, directory = null)
        val id = requireNotNull(attempt.text("attemptID"))
        var terminal = false
        try {
            require(attempt.text("mode") != "code" && attempt.text("paste") != "true") {
                "This integration requires a code to complete sign-in."
            }
            val url = requireNotNull(attempt.text("url"))
            require(url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))
            openUri(url)
            val status = withTimeoutOrNull(5 * 60_000L) {
                var value: String?
                do {
                    delay(2_000)
                    value = service.integrationOAuthStatus(connectionId, integrationId, id, directory = null).text("status")
                } while (value == "pending")
                value
            }
            terminal = status in setOf("complete", "failed", "expired")
            check(status == "complete") { "OpenCode did not confirm sign-in." }
            return provider.copy(auth = Authorization(AuthMethod.OAUTH, value = "authorized"))
        } finally {
            if (!terminal) withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    try { service.cancelIntegrationOAuth(connectionId, integrationId, id, directory = null) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* The remote attempt expires independently. */ }
                }
            }
        }
    }

    companion object {
        suspend fun load(service: ServerConnections, connectionId: String, integrationId: String): OpenCodeProviderAuthorization {
            val integration = service.integration(connectionId, integrationId, directory = null)
            val methods = (integration["methods"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .filter { it["form"] == null || it["form"] == JsonArray(emptyList()) || it["form"] == JsonObject(emptyMap()) }
            return OpenCodeProviderAuthorization(
                service, connectionId, integrationId,
                canConnectKey = methods.any { it.text("type") == "key" },
                oauthMethod = methods.firstNotNullOfOrNull { method ->
                    method.text("id")?.takeIf { method.text("type") == "oauth" }
                }
            )
        }
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
