package org.starfall.generative.features.serverconnection.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.starfall.generative.integrations.opencode.OpenCodeApi
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.provider.ModelProvider

/** Translates OpenCode integration methods to the ordinary provider editor's callbacks. */
internal class OpenCodeProviderAuthorization private constructor(
    private val api: OpenCodeApi,
    private val integrationId: String,
    val methods: List<OpenCodeAuthorizationMethod>,
) {
    val canAuthorize: Boolean get() = methods.any { it.kind == AuthMethod.OAUTH }

    fun initialProvider(provider: ModelProvider): ModelProvider {
        val kind = methods.firstOrNull { it.kind == AuthMethod.PLATFORM_DEFAULT }?.kind ?: methods.firstOrNull()?.kind
        return if (kind == null || provider.auth.method == kind) provider else provider.copy(auth = Authorization(kind))
    }

    suspend fun connectKey(provider: ModelProvider, answers: JsonObject?) {
        require(methods.any { it.kind == AuthMethod.PLATFORM_DEFAULT } &&
            provider.auth.method == AuthMethod.PLATFORM_DEFAULT && provider.auth.token.isNotBlank())
        api.connectIntegrationKey(integrationId, provider.auth.token, answer = answers)
    }

    suspend fun authorize(
        provider: ModelProvider,
        method: OpenCodeAuthorizationMethod,
        answers: JsonObject?,
        presentChallenge: suspend (OpenCodeAuthorizationChallenge) -> String?,
    ): ModelProvider {
        require(method in methods && method.kind == AuthMethod.OAUTH)
        val attempt = api.beginIntegrationOAuth(integrationId, requireNotNull(method.id), answer = answers)
        val id = requireNotNull(attempt.text("attemptID"))
        var terminal = false
        try {
            val requiresCode = attempt.text("mode") == "code" || attempt.text("paste") == "true"
            val url = attempt.text("url")?.takeIf {
                it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
            }
            require(requiresCode || url != null)
            val status = withTimeoutOrNull(5 * 60_000L) {
                val code = presentChallenge(OpenCodeAuthorizationChallenge(url, attempt.text("instructions"), requiresCode))
                if (requiresCode) {
                    if (code.isNullOrBlank()) throw CancellationException("OpenCode sign-in cancelled")
                    api.completeIntegrationOAuth(integrationId, id, code)
                }
                var value: String?
                do {
                    delay(2_000)
                    value = api.integrationOAuthStatus(integrationId, id).text("status")
                } while (value == "pending")
                value
            }
            terminal = status in setOf("complete", "failed", "expired")
            check(status == "complete") { "OpenCode did not confirm sign-in." }
            return provider.copy(auth = Authorization(AuthMethod.OAUTH, value = "authorized"))
        } finally {
            if (!terminal) withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    try { api.cancelIntegrationOAuth(integrationId, id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* The remote attempt expires independently. */ }
                }
            }
        }
    }

    companion object {
        suspend fun load(service: ServerConnections, connectionId: String, integrationId: String): OpenCodeProviderAuthorization {
            val api = service.openCode(connectionId)
            val integration = api.integration(integrationId)
            val methods = (integration["methods"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { method ->
                val id = method.text("id")
                val kind = when (method.text("type")) {
                    "key" -> AuthMethod.PLATFORM_DEFAULT
                    "oauth" -> if (id.isNullOrBlank()) return@mapNotNull null else AuthMethod.OAUTH
                    else -> return@mapNotNull null
                }
                val form = method["form"]?.takeUnless {
                    it is JsonNull || (it is JsonObject && it.isEmpty()) || (it is JsonArray && it.isEmpty())
                }
                OpenCodeAuthorizationMethod(kind, id, method.text("label"), form)
            }
            return OpenCodeProviderAuthorization(api, integrationId, methods)
        }
    }
}

internal data class OpenCodeAuthorizationMethod(
    val kind: AuthMethod,
    val id: String?,
    val label: String?,
    val form: JsonElement?,
)

internal data class OpenCodeAuthorizationChallenge(val url: String?, val instructions: String?, val requiresCode: Boolean)

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
