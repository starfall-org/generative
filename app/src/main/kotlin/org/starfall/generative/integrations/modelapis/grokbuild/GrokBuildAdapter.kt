package org.starfall.generative.integrations.modelapis.grokbuild

import android.content.Context
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.starfall.generative.integrations.modelapis.account.AccountTokenState
import org.starfall.generative.integrations.modelapis.account.accountTokenFromResponse
import org.starfall.generative.integrations.modelapis.devicecode.DeviceCodeAccountAdapter
import org.starfall.generative.integrations.modelapis.devicecode.DeviceCodeSession
import org.starfall.generative.shared.files.AttachmentResolver
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.provider.oauthCredentialId
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.networking.text

/** Grok CLI OAuth login and the CLI chat-proxy Responses transport. */
internal class GrokBuildAdapter(context: Context, attachments: AttachmentResolver) : DeviceCodeAccountAdapter(
    context, attachments, ModelApiType.GROK_BUILD, CLIENT_ID, listOf("grok-4.7", "grok-4.7-fast")
) {
    private suspend fun discovery(): JsonObject =
        http.json(http.request("https://auth.x.ai/.well-known/openid-configuration").get().build())

    private fun endpoint(value: String): String {
        val url = value.trim()
        val parsed = url.toHttpUrlOrNull()
            ?: error("xAI OAuth discovery returned an invalid URL")
        require(parsed.isHttps && (parsed.host == "x.ai" || parsed.host.endsWith(".x.ai"))) {
            "xAI OAuth discovery returned an untrusted host"
        }
        return url
    }

    override suspend fun startDeviceAuthorization(): DeviceCodeSession {
        val endpoints = discovery()
        val deviceUrl = endpoint(endpoints.text("device_authorization_endpoint"))
        val tokenUrl = endpoint(endpoints.text("token_endpoint"))
        val payload = formResponse(deviceUrl, mapOf("client_id" to CLIENT_ID, "scope" to SCOPES))
        require(payload.text("error").isBlank()) { "xAI device authorization failed: ${payload.text("error")}" }
        return DeviceCodeSession(
            deviceCode = payload.text("device_code"),
            userCode = payload.text("user_code"),
            verificationUrl = payload.text("verification_uri_complete").ifBlank { payload.text("verification_uri") },
            tokenEndpoint = tokenUrl,
            intervalSeconds = payload.text("interval").toLongOrNull() ?: 5,
            expiresInSeconds = payload.text("expires_in").toLongOrNull() ?: 900
        )
    }

    override suspend fun completeDeviceAuthorization(payload: JsonObject, session: DeviceCodeSession): AccountTokenState {
        val base = accountTokenFromResponse(payload)
        val (email, subject) = jwtIdentity(payload.text("id_token"))
        return base.copy(email = email ?: base.email, accountId = subject ?: base.accountId, tokenEndpoint = session.tokenEndpoint)
    }

    override suspend fun exchange(fields: Map<String, String>, previous: AccountTokenState?): AccountTokenState {
        val tokenEndpoint = previous?.tokenEndpoint?.let(::endpoint)
            ?: endpoint(discovery().text("token_endpoint"))
        val body = formResponse(tokenEndpoint, fields + ("client_id" to CLIENT_ID))
        require(body.text("error").isBlank()) { "xAI token refresh failed: ${body.text("error")}" }
        val token = accountTokenFromResponse(body, previous)
        val (email, subject) = jwtIdentity(body.text("id_token"))
        return token.copy(email = email ?: token.email, accountId = subject ?: token.accountId,
            tokenEndpoint = tokenEndpoint)
    }

    override suspend fun prepareAuthenticatedProvider(provider: ModelProvider): ModelProvider {
        val token = ensureToken(provider)
        val version = currentGrokVersion()
        val headers = provider.config.headers + mapOf(
            "X-XAI-Token-Auth" to "xai-grok-cli",
            "x-grok-client-version" to version,
            "x-grok-client-identifier" to "grok-shell",
            "x-authenticateresponse" to "authenticate-response",
            "User-Agent" to "xai-grok-workspace/$version",
            "Connection" to "Keep-Alive"
        )
        return provider.copy(
            type = ModelApiType.OPENAI_RESPONSES,
            baseUrl = ModelApiType.GROK_BUILD.defaultBaseUrl,
            auth = Authorization(AuthMethod.BEARER_TOKEN, value = token.accessToken),
            config = provider.config.copy(headers = headers)
        )
    }

    private val refreshMutex = Mutex()

    /** A 401 can also occur before the advertised OAuth expiry; retry only before any output. */
    private suspend fun forceRefresh(provider: ModelProvider) = refreshMutex.withLock {
        val current = store.load(provider.oauthCredentialId)
            ?: error("Grok Build credentials are missing. Sign in again.")
        require(current.refreshToken.isNotBlank()) { "Grok Build authorization expired. Sign in again." }
        val refreshed = exchange(mapOf("grant_type" to "refresh_token", "refresh_token" to current.refreshToken), current)
        store.save(provider.oauthCredentialId, refreshed)
    }

    override fun streamEvents(provider: ModelProvider, modelName: String, messages: List<StoredMessage>,
        systemPrompt: String, maxOutputTokens: Int): Flow<GenerationEvent> = flow {
        var emitted = false
        try {
            super.streamEvents(provider, modelName, messages, systemPrompt, maxOutputTokens).collect {
                emitted = true; emit(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (emitted || !UNAUTHORIZED.containsMatchIn(e.message.orEmpty())) throw e
            forceRefresh(provider)
            super.streamEvents(provider, modelName, messages, systemPrompt, maxOutputTokens).collect { emit(it) }
        }
    }

    override suspend fun fetchModelCatalog(provider: ModelProvider): List<DiscoveredModel> = try {
        super.fetchModelCatalog(provider)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!UNAUTHORIZED.containsMatchIn(e.message.orEmpty())) throw e
        forceRefresh(provider)
        super.fetchModelCatalog(provider)
    }

    private suspend fun currentGrokVersion(): String = versionLock.withLock {
        val now = System.currentTimeMillis()
        if (now < nextVersionLookup) return@withLock versionCache
        // No long-lived background job; refresh lazily when an actual request needs the identity.
        nextVersionLookup = now + 3 * 60 * 60 * 1000L
        try {
            val latest = withTimeout(10_000) {
                http.json(http.request("https://registry.npmjs.org/@xai-official/grok/latest")
                    .header("User-Agent", "Generative").get().build()).text("version")
            }
            val numbers = latest.split('.').mapNotNull { it.toIntOrNull() }
            if (numbers.size == 3 && latest.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) &&
                (numbers[0] > 1 || numbers[0] == 1 && (numbers[1] > 0 || numbers[2] >= 13))) {
                versionCache = latest
            }
        } catch (_: TimeoutCancellationException) {
            // The version lookup is optional; do not block inference on an npm timeout.
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the known compatible version when the npm registry is unreachable.
        }
        versionCache
    }

    private companion object {
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val SCOPES = "openid profile email offline_access grok-cli:access api:access"
        private val UNAUTHORIZED = Regex("(?i)(?:\\b401\\b|unauthorized)")
        private val versionLock = Mutex()
        private var versionCache = "1.0.46"
        private var nextVersionLookup = 0L
    }
}

/** ID tokens only provide display identity; their claims are not used for authorization. */
private fun jwtIdentity(token: String): Pair<String?, String?> = try {
    val part = token.split('.')[1]
    val decoded = String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)
    val body = kotlinx.serialization.json.Json.parseToJsonElement(decoded).jsonObject
    body.text("email").takeIf(String::isNotBlank) to body.text("sub").takeIf(String::isNotBlank)
} catch (_: Exception) { null to null }
