package org.starfall.generative.integrations.modelapis.devicecode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request
import org.starfall.generative.R
import org.starfall.generative.integrations.modelapis.account.AccountTokenState
import org.starfall.generative.integrations.modelapis.account.OAuthAccountAdapter
import org.starfall.generative.integrations.modelapis.discoveredModel
import org.starfall.generative.shared.auth.DeviceCodeChallenge
import org.starfall.generative.shared.auth.DeviceCodeSignInState
import org.starfall.generative.shared.auth.OAuthCallbackService
import org.starfall.generative.shared.files.AttachmentResolver
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.oauthCredentialId
import org.starfall.generative.shared.model.provider.streamEnabledFor
import org.starfall.generative.shared.networking.text

internal data class DeviceCodeSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val tokenEndpoint: String,
    val intervalSeconds: Long = 5,
    val expiresInSeconds: Long = 900
)

/** OAuth RFC 8628 transport shared by Grok Build and Meta Muse, not a web-cookie login. */
internal abstract class DeviceCodeAccountAdapter(
    context: Context,
    attachments: AttachmentResolver,
    providerType: ModelApiType,
    clientId: String,
    private val defaultModels: List<String>
) : OAuthAccountAdapter(context, attachments, providerType, clientId, "", "", "", 0, "") {
    protected val parser = Json { ignoreUnknownKeys = true }

    protected abstract suspend fun startDeviceAuthorization(): DeviceCodeSession
    protected abstract suspend fun completeDeviceAuthorization(payload: JsonObject, session: DeviceCodeSession): AccountTokenState

    /** Poll errors are normal OAuth responses, often returned with HTTP 400. */
    protected suspend fun formResponse(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): JsonObject {
        val form = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        val request = http.request(url, extraHeaders = headers)
            .header("Accept", "application/json")
            .post(form).build()
        return readOAuthResponse(request)
    }

    protected suspend fun readOAuthResponse(request: Request): JsonObject = http.withResponse(request) { response ->
        val json = parser.parseToJsonElement(http.readJson(response)).jsonObject
        if (!response.isSuccessful && json.text("error").isBlank()) {
            error("${providerType.displayName} authorization returned HTTP ${response.code}")
        }
        json
    }

    override suspend fun authorize(provider: ModelProvider): Result<ModelProvider> = result {
        require(provider.type == providerType)
        OAuthCallbackService.keepAlive(appContext) {
            val device = startDeviceAuthorization()
            require(device.deviceCode.isNotBlank() && device.userCode.isNotBlank()) {
                "${providerType.displayName} did not return an authorization code."
            }
            require(device.verificationUrl.startsWith("https://")) { "Insecure device verification URL" }
            val challenge = DeviceCodeChallenge(providerType, device.userCode, device.verificationUrl)
            DeviceCodeSignInState.show(challenge)
            try {
                withContext(Dispatchers.Main) {
                    (appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(appContext.getString(R.string.device_code_clip_label), device.userCode))
                    Toast.makeText(appContext, "${providerType.displayName}: ${device.userCode}", Toast.LENGTH_LONG).show()
                }
                openBrowser(device.verificationUrl)
                val tokenPayload = pollForAuthorization(device)
                val token = completeDeviceAuthorization(tokenPayload, device)
                require(token.accessToken.isNotBlank()) { "${providerType.displayName} did not return a usable credential." }
                withContext(Dispatchers.IO) { store.save(provider.oauthCredentialId, token) }
                authorized(provider, token)
            } finally {
                DeviceCodeSignInState.clear(challenge)
            }
        }
    }

    private suspend fun pollForAuthorization(session: DeviceCodeSession): JsonObject {
        var intervalMs = session.intervalSeconds.coerceAtLeast(5L) * 1000L
        // The app's foreground OAuth service holds a 16-minute session; keep polling bounded.
        val deadline = System.currentTimeMillis() + session.expiresInSeconds.coerceIn(1, 900) * 1000L
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            check(remaining > intervalMs) { "${providerType.displayName} device authorization timed out." }
            delay(intervalMs)
            val payload = formResponse(session.tokenEndpoint, mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to clientId,
                "device_code" to session.deviceCode
            ), authHeaders())
            if (payload.text("access_token").isNotBlank()) return payload
            when (payload.text("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalMs += 5_000
                "access_denied" -> error("${providerType.displayName} authorization was denied.")
                "expired_token" -> error("${providerType.displayName} authorization code expired.")
                else -> error("${providerType.displayName} authorization failed: ${payload.text("error").ifBlank { "No access token" }}")
            }
        }
    }

    protected open fun authHeaders(): Map<String, String> = emptyMap()

    override suspend fun fetchModels(provider: ModelProvider): List<String> = fetchModelCatalog(provider).map { it.id }

    override suspend fun fetchModelCatalog(provider: ModelProvider): List<DiscoveredModel> {
        val wire = prepareAuthenticatedProvider(provider)
        return try {
            val response = http.json(http.request(wire.baseUrl.trimEnd('/') + "/models", wire).get().build())
            val entries = (response["data"] ?: response["models"]) as? JsonArray
                ?: error("${providerType.displayName} model catalog returned an unsupported response.")
            entries.mapNotNull { (it as? JsonObject)?.let { model ->
                val id = model.text("id").ifBlank { model.text("name") }
                id.takeIf(String::isNotBlank)?.let { discoveredModel(it, model) }
            } }.distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // CLI accounts may support /responses without exposing an HTTP /models endpoint.
            if (e.message?.contains("HTTP 404") != true) throw e
            defaultModels.map { DiscoveredModel(it) }
        }
    }

    override fun shouldStreamUpstream(
        sourceProvider: ModelProvider,
        wireProvider: ModelProvider,
        modelName: String,
        callerStream: Boolean
    ): Boolean = true

    override fun normalizeToolRequest(
        sourceProvider: ModelProvider,
        wireProvider: ModelProvider,
        body: JsonObject,
        systemPrompt: String
    ): JsonObject {
        val values = body.toMutableMap()
        listOf("previous_response_id", "stream_options", "prompt_cache_retention",
            "safety_identifier", "stop").forEach(values::remove)
        values["store"] = JsonPrimitive(false)
        return JsonObject(values)
    }

    override suspend fun testConnection(provider: ModelProvider): Result<String> = result {
        ensureToken(provider)
        "${providerType.displayName} account credential available. Test a model to verify inference access."
    }

    /** Both upstreams use SSE Responses even when the downstream UI requests non-streaming. */
    override fun streamEvents(provider: ModelProvider, modelName: String, messages: List<StoredMessage>,
        systemPrompt: String, maxOutputTokens: Int): Flow<GenerationEvent> {
        val callerStream = provider.streamEnabledFor(modelName)
        val modelConfig = provider.config.modelConfigs[modelName]
        val streaming = provider.copy(config = provider.config.copy(
            supportStream = true,
            modelConfigs = if (modelConfig == null) provider.config.modelConfigs
                else provider.config.modelConfigs + (modelName to modelConfig.copy(supportStream = true))
        ))
        val upstream = super.streamEvents(streaming, modelName, messages, systemPrompt, maxOutputTokens)
        if (callerStream) return upstream
        return flow {
            val text = StringBuilder()
            val thinking = StringBuilder()
            var signature: String? = null
            upstream.collect { event -> when (event) {
                is GenerationEvent.Text -> text.append(event.text)
                is GenerationEvent.Reasoning -> { thinking.append(event.text); signature = event.signature ?: signature }
                else -> emit(event)
            } }
            if (thinking.isNotEmpty() || signature != null) emit(GenerationEvent.Reasoning(thinking.toString(), signature))
            if (text.isNotEmpty()) emit(GenerationEvent.Text(text.toString()))
        }
    }
}
