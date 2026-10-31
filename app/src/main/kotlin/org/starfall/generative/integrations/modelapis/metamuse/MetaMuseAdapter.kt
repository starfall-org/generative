package org.starfall.generative.integrations.modelapis.metamuse

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.starfall.generative.integrations.modelapis.account.AccountTokenState
import org.starfall.generative.integrations.modelapis.devicecode.DeviceCodeAccountAdapter
import org.starfall.generative.integrations.modelapis.devicecode.DeviceCodeSession
import org.starfall.generative.shared.files.AttachmentResolver
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.oauthCredentialId
import org.starfall.generative.shared.networking.text

/** Muse Code Device Client Access -> minted API key -> Meta Responses API. */
internal class MetaMuseAdapter(context: Context, attachments: AttachmentResolver) : DeviceCodeAccountAdapter(
    context, attachments, ModelApiType.META_MUSE, CLIENT_ID,
    listOf("muse-spark-1.3", "muse-wonder", "muse-wonder-full")
) {
    override fun authHeaders(): Map<String, String> = mapOf("User-Agent" to "muse-code/1.0.2")

    override suspend fun startDeviceAuthorization(): DeviceCodeSession {
        val payload = formResponse("https://auth.meta.com/oidc/device/authorization/",
            mapOf("client_id" to CLIENT_ID), authHeaders())
        require(payload.text("error").isBlank()) { "Meta authorization failed: ${payload.text("error")}" }
        return DeviceCodeSession(
            deviceCode = payload.text("device_code"),
            userCode = payload.text("user_code"),
            verificationUrl = payload.text("verification_uri_complete").ifBlank { payload.text("verification_uri") },
            tokenEndpoint = "https://auth.meta.com/oidc/device/token/",
            intervalSeconds = payload.text("interval").toLongOrNull() ?: 5,
            expiresInSeconds = payload.text("expires_in").toLongOrNull() ?: 900
        )
    }

    override suspend fun completeDeviceAuthorization(payload: JsonObject, session: DeviceCodeSession): AccountTokenState {
        val dca = payload.text("access_token")
        require(dca.isNotBlank()) { "Meta login did not return a device token." }
        val expiry = payload.text("expires_in").toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 }
        return mintKey(dca).copy(deviceToken = dca, deviceTokenExpiresAt = expiry)
    }

    private suspend fun mintKey(deviceToken: String): AccountTokenState {
        val body = buildJsonObject { put("dca_token", deviceToken) }.toString()
        val request = http.request("https://api.meta.ai/muse-code/key",
            extraHeaders = mapOf("Authorization" to "Bearer $deviceToken", "User-Agent" to "muse-code/1.0.2"))
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val minted = http.json(request)
        val key = minted.text("api_key")
        require(key.isNotBlank()) { "Meta did not issue an API key for this Muse account." }
        val base = minted.text("base_url").ifBlank { "https://api.meta.ai/v1" }
        require(base.startsWith("https://")) { "Meta issued an insecure API base URL." }
        return AccountTokenState(accessToken = key, refreshToken = "", expiresAt = null,
            email = minted.text("user_email").takeIf(String::isNotBlank), apiBaseUrl = base)
    }

    override suspend fun prepareAuthenticatedProvider(provider: ModelProvider): ModelProvider {
        val token = ensureToken(provider)
        return provider.copy(
            type = ModelApiType.OPENAI_RESPONSES,
            baseUrl = token.apiBaseUrl ?: ModelApiType.META_MUSE.defaultBaseUrl,
            auth = Authorization(AuthMethod.BEARER_TOKEN, value = token.accessToken),
            config = provider.config.copy(headers = provider.config.headers + mapOf(
                "User-Agent" to "muse-build/1.3.0 (interactive; macos-aarch64; build ac7280f2aca67769d1455a8847bb502b617d50f6)",
                "X-Client-Id" to "tbh:tui"
            ))
        )
    }

    override fun normalizeToolRequest(sourceProvider: ModelProvider, wireProvider: ModelProvider,
        body: JsonObject, systemPrompt: String): JsonObject {
        val values = super.normalizeToolRequest(sourceProvider, wireProvider, body, systemPrompt).toMutableMap()
        listOf("generate", "client_metadata").forEach(values::remove)
        return JsonObject(values)
    }

    private suspend fun refreshMintedKey(provider: ModelProvider) = refreshMutex.withLock {
        val previous = store.load(provider.oauthCredentialId)
            ?: error("Meta account credentials are missing. Sign in again.")
        val dca = previous.deviceToken?.takeIf(String::isNotBlank)
            ?: error("Meta account has no device token. Sign in again.")
        require(previous.deviceTokenExpiresAt == null || previous.deviceTokenExpiresAt > System.currentTimeMillis()) {
            "Meta device token expired. Sign in again."
        }
        val minted = mintKey(dca)
        store.save(provider.oauthCredentialId, previous.copy(
            accessToken = minted.accessToken, apiBaseUrl = minted.apiBaseUrl,
            email = minted.email ?: previous.email
        ))
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
            refreshMintedKey(provider)
            super.streamEvents(provider, modelName, messages, systemPrompt, maxOutputTokens).collect { emit(it) }
        }
    }

    override suspend fun fetchModelCatalog(provider: ModelProvider): List<DiscoveredModel> = try {
        super.fetchModelCatalog(provider)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!UNAUTHORIZED.containsMatchIn(e.message.orEmpty())) throw e
        refreshMintedKey(provider)
        super.fetchModelCatalog(provider)
    }

    private val refreshMutex = Mutex()
    private val UNAUTHORIZED = Regex("(?i)(?:\\b401\\b|unauthorized)")
    private companion object {
        const val CLIENT_ID = "1031625952748946"
    }
}
