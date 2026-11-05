package org.starfall.generative.integrations.modelapis.account

import java.util.Base64
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.starfall.generative.integrations.modelapis.account.AccountProviderAdapter
import org.starfall.generative.shared.subfeatures.auth.logic.AppAuthTransactions
import org.starfall.generative.shared.subfeatures.auth.logic.*
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.*
import org.starfall.generative.shared.model.auth.*
import org.starfall.generative.shared.model.generation.*
import org.starfall.generative.shared.model.language.*
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.R
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text
import org.starfall.generative.shared.helpers.networking.providerBase
import org.starfall.generative.shared.helpers.networking.modelResponse
import org.starfall.generative.shared.helpers.networking.providerTokenUsage
import org.starfall.generative.integrations.modelapis.ModelApiAdapter
import org.starfall.generative.integrations.modelapis.ModelApiRequest
import org.starfall.generative.integrations.modelapis.openai.OpenAIChatCompletionsAdapter
import org.starfall.generative.integrations.modelapis.openairesponses.OpenAIResponsesAdapter
import org.starfall.generative.integrations.modelapis.anthropic.AnthropicMessagesAdapter
import org.starfall.generative.integrations.modelapis.gemini.GeminiAdapter
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk

/** Shared PKCE and encrypted persistence; protocol details stay in each adapter. */
internal abstract class OAuthAccountAdapter(
    context: Context,
    private val attachments: AttachmentResolver,
    final override val providerType: ModelApiType,
    protected val clientId: String,
    private val authorizationUrl: String,
    protected val tokenUrl: String,
    private val scope: String,
    private val callbackPort: Int,
    private val callbackPath: String,
    protected val clientSecret: String? = null,
    private val jsonTokens: Boolean = false,
    private val callbackHost: String = "localhost",
    private val registeredRedirectUri: String? = null
) : AccountProviderAdapter {
    protected val appContext = context.applicationContext
    protected val http = ToolHttp()
    protected val store = AccountTokenStore(context, providerType.name.lowercase())
    private val refreshLock = Mutex()
    protected open val useOfficialSdkForGeneration: Boolean = true
    private val officialSdk by lazy { OfficialProviderSdk(attachments) }
    private val officialAdapters: Map<ModelApiType, ModelApiAdapter> by lazy {
        val sdk = officialSdk
        listOf(
            OpenAIChatCompletionsAdapter(sdk),
            OpenAIResponsesAdapter(sdk),
            AnthropicMessagesAdapter(sdk),
            GeminiAdapter(sdk)
        ).associateBy { it.modelApiType }
    }
    private val redirectUri get() = registeredRedirectUri ?: "http://$callbackHost:$callbackPort$callbackPath"
    protected val marker get() = "account:${providerType.name.lowercase()}:v1"

    protected suspend fun <T> result(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }

    protected open suspend fun openBrowser(url: String) = withContext(Dispatchers.Main) {
        OAuthBrowser.open(appContext, url)
    }

    override suspend fun authorize(provider: ModelProvider): Result<ModelProvider> = result {
        require(provider.type == providerType)
        require(clientId.isNotBlank() && (clientSecret == null || clientSecret.isNotBlank())) {
            "${providerType.displayName} OAuth client configuration is missing from this build."
        }
        val extra = authorizationAdditionalParameters()
        val request = AppAuthTransactions.authorization(authorizationUrl, tokenUrl, clientId, redirectUri, scope, extra)
        OAuthCallbackService.keepAlive(appContext) {
            val code = if (request.redirectUri.scheme == "generative-oauth") {
                StandardAppAuthAuthorization.authorize(appContext, request).authorizationCode
                    ?: error("OAuth authorization code is missing")
            } else {
                require(request.redirectUri.scheme == "http" && request.redirectUri.host in setOf("localhost", "127.0.0.1")) {
                    "Unsupported registered OAuth redirect"
                }
                awaitCode(request.toUri().toString(), request.state!!)
            }
            val token = enrichToken(exchange(mapOf("grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to redirectUri, "code_verifier" to request.codeVerifier!!, "state" to request.state!!)))
            withContext(Dispatchers.IO) { store.save(provider.oauthCredentialId, token) }
            authorized(provider, token)
        }
    }

    protected fun authorized(provider: ModelProvider, token: AccountTokenState) = provider.copy(
        auth = Authorization(AuthMethod.OAUTH, key = token.email, value = marker, oauthAccountId = provider.auth.oauthAccountId))

    protected open suspend fun enrichToken(token: AccountTokenState): AccountTokenState = token

    protected open fun authorizationAdditionalParameters(): Map<String, String> =
        if (jsonTokens) mapOf("code" to "true")
        else mapOf("access_type" to "offline", "prompt" to "consent")

    protected suspend fun ensureToken(provider: ModelProvider): AccountTokenState = refreshLock.withLock {
        require(provider.type == providerType && provider.auth.method == AuthMethod.OAUTH && provider.auth.value == marker) {
            "${providerType.displayName} is not authorized. Sign in from Provider settings."
        }
        val current = withContext(Dispatchers.IO) { store.load(provider.oauthCredentialId) }
            ?: error("Credentials are missing. Sign in again.")
        if (current.expiresAt != null && current.expiresAt <= System.currentTimeMillis() + 60_000) {
            require(current.refreshToken.isNotBlank()) { "Authorization expired. Sign in again." }
            exchange(mapOf("grant_type" to "refresh_token", "refresh_token" to current.refreshToken), current)
                .also { withContext(Dispatchers.IO) { store.save(provider.oauthCredentialId, it) } }
        } else current
    }

    protected open suspend fun exchange(fields: Map<String, String>, previous: AccountTokenState? = null): AccountTokenState {
        val payload = AppAuthTransactions.exchange(appContext, http, AppAuthTransactions.token(tokenUrl, clientId, fields),
            AppAuthTransactions.authentication(if (clientSecret == null) "none" else "client_secret_post", clientSecret),
            jsonBody = jsonTokens)
        return accountTokenFromResponse(payload, previous)
    }

    private suspend fun awaitCode(url: String, state: String): String =
        awaitLoopbackAuthorizationCode(redirectUri, url, state, ::openBrowser)

    override suspend fun testConnection(provider: ModelProvider): Result<String> = result {
        check(fetchModels(provider).isNotEmpty()) { appContext.getString(R.string.provider_no_models_returned) }
        appContext.getString(R.string.provider_connection_ready, providerType.displayName)
    }
    override fun clearCredentials(providerId: String) = store.delete(providerId)

    override fun exportCredentials(): Map<String, JsonObject> = store.exportAll()

    override fun importCredentials(credentials: Map<String, JsonObject>) = store.importAll(credentials)

    protected open fun sdkSystemPrompt(systemPrompt: String): String = systemPrompt

    /** Original caller instructions for account protocols with a separate conversation system slot. */
    protected open fun sdkCallerSystemPrompt(systemPrompt: String): String? = null

    override fun streamEvents(provider: ModelProvider, modelName: String, messages: List<StoredMessage>,
        systemPrompt: String, maxOutputTokens: Int): Flow<GenerationEvent> =
        if (useOfficialSdkForGeneration) streamEventsWithSdk(provider, modelName, messages, systemPrompt, maxOutputTokens)
        else streamEventsWithHttp(provider, modelName, messages, systemPrompt, maxOutputTokens)

    private fun streamEventsWithSdk(provider: ModelProvider, modelName: String, messages: List<StoredMessage>,
        systemPrompt: String, maxOutputTokens: Int): Flow<GenerationEvent> = flow {
        val wire = prepareModelProvider(provider, modelName)
        val modelConfig = provider.config.modelConfigs[modelName] ?: ModelConfiguration()
        val requestContext = buildJsonObject {
            put("model", modelName)
            if (wire.type == ModelApiType.ANTHROPIC) {
                modelConfig.claudeThinkingParameters(modelName, maxOutputTokens).forEach { (key, value) -> put(key, value) }
            }
            put("messages", JsonArray(messages.filter { it.role != ChatRole.SYSTEM }.map { message ->
                buildJsonObject {
                    put("role", if (message.role == ChatRole.MODEL) "assistant" else "user")
                    if (message.files.isEmpty()) put("content", message.content)
                    else put("content", buildJsonArray {
                        if (message.content.isNotBlank()) add(buildJsonObject {
                            put("type", "text"); put("text", message.content)
                        })
                        message.files.forEach { reference ->
                            val mimeType = attachments.metadata(reference)?.mimeType
                                ?: error("Attachment metadata is missing")
                            val image = mimeType.startsWith("image/")
                            if (wire.type != ModelApiType.ANTHROPIC && wire.type != ModelApiType.GOOGLE) {
                                require(image) { "Unsupported ${wire.type.displayName} attachment" }
                            }
                            add(buildJsonObject {
                                put("type", when {
                                    !image -> "document"
                                    wire.type == ModelApiType.OPENAI_RESPONSES -> "input_image"
                                    else -> "image_url"
                                })
                            })
                        }
                    })
                }
            }))
        }
        val normalizedPrompt = sdkSystemPrompt(systemPrompt)
        val normalizedContext = normalizeToolRequest(provider, wire, requestContext, normalizedPrompt)
        val requestProvider = prepareRequestProvider(provider, wire, normalizedContext)
        val adapter = officialAdapters[requestProvider.type]
            ?: error("No official SDK adapter is registered for ${requestProvider.type.displayName}.")
        emitAll(adapter.stream(ModelApiRequest(
            provider = requestProvider,
            modelName = modelName,
            messages = messages.filter { it.role != ChatRole.SYSTEM },
            systemPrompt = normalizedPrompt,
            temperature = modelConfig.temperature,
            topP = modelConfig.topP,
            topK = modelConfig.topK,
            maxTokens = maxOutputTokens,
            reasoningEffort = modelConfig.wireReasoningEffort,
            sendThinkingContent = modelConfig.sendsThinkingBack(messages.hasToolCalls()),
            enabledModelTools = modelConfig.enabledModelTools
                .filter { it in modelConfig.supportedModelTools }.distinct(),
            callerSystemPrompt = sdkCallerSystemPrompt(systemPrompt)
        )))
    }.flowOn(Dispatchers.IO)

    private fun streamEventsWithHttp(provider: ModelProvider, modelName: String, messages: List<StoredMessage>,
        systemPrompt: String, maxOutputTokens: Int): Flow<GenerationEvent> = channelFlow {
        val wire = prepareModelProvider(provider, modelName)
        val body = chatBody(wire.type, modelName, messages, systemPrompt, maxOutputTokens, provider.config.modelConfigs[modelName] ?: ModelConfiguration())
        val base = providerBase(wire)
        val url = when (wire.type) {
            ModelApiType.ANTHROPIC -> base.removeSuffix("/v1") + "/v1/messages"
            ModelApiType.GOOGLE -> "$base/models/$modelName:generateContent"
            ModelApiType.OPENAI_RESPONSES -> "$base/responses"
            else -> "$base/chat/completions"
        }
        val callerStream = provider.streamEnabledFor(modelName)
        val upstreamStream = shouldStreamUpstream(provider, wire, modelName, callerStream)
        val response = http.modelResponse(requestUrl(provider, wire, url),
            normalizeToolRequest(provider, wire, body, systemPrompt), prepareRequestProvider(provider, wire, body), upstreamStream,
            { chunk -> if (callerStream) send(GenerationEvent.Text(chunk)) },
            { chunk -> if (callerStream) send(GenerationEvent.Reasoning(chunk)) }, ::unwrapResponse)
        if (callerStream && wire.type == ModelApiType.ANTHROPIC) {
            (response["content"] as? JsonArray).orEmpty().forEach {
                val part = it.jsonObject
                part.text("signature").takeIf(String::isNotBlank)?.let { signature ->
                    send(GenerationEvent.Reasoning("", signature))
                }
            }
        }
        if (!callerStream) {
            when (wire.type) {
                ModelApiType.ANTHROPIC -> (response["content"] as? JsonArray).orEmpty().forEach {
                    val part = it.jsonObject
                    if (part.text("type") == "thinking") send(GenerationEvent.Reasoning(part.text("thinking"), part.text("signature").takeIf(String::isNotBlank)))
                    else if (part.text("type") == "text") send(GenerationEvent.Text(part.text("text")))
                }
                ModelApiType.GOOGLE -> (response["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty().forEach {
                        val part = it.jsonObject
                        if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) send(GenerationEvent.Reasoning(part.text("text")))
                        else send(GenerationEvent.Text(part.text("text")))
                    }
                ModelApiType.OPENAI_RESPONSES -> (response["output"] as? JsonArray).orEmpty().forEach { item ->
                    if (item.jsonObject.text("type") == "reasoning")
                        (item.jsonObject["summary"] as? JsonArray).orEmpty().forEach {
                            send(GenerationEvent.Reasoning(it.jsonObject.text("text")))
                        }
                    (item.jsonObject["content"] as? JsonArray).orEmpty().forEach { part ->
                        if (part.jsonObject.text("type") == "output_text") send(GenerationEvent.Text(part.jsonObject.text("text")))
                    }
                }
                else -> (response["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.let {
                    if (it.text("reasoning_content").isNotEmpty()) send(GenerationEvent.Reasoning(it.text("reasoning_content")))
                    send(GenerationEvent.Text(it.text("content")))
                }
            }
        }
        providerTokenUsage(wire.type, response)?.takeIf { it.isValid }?.let { send(GenerationEvent.Usage(it)) }
    }.flowOn(Dispatchers.IO)

    private fun chatBody(type: ModelApiType, model: String, messages: List<StoredMessage>, prompt: String, maxTokens: Int, config: ModelConfiguration): JsonObject {
        fun parts(message: StoredMessage): JsonArray = buildJsonArray {
            if (message.content.isNotBlank()) add(if (type == ModelApiType.ANTHROPIC) obj("type" to str("text"), "text" to str(message.content)) else obj("text" to str(message.content)))
            message.files.forEach { reference ->
                val meta = attachments.metadata(reference) ?: error("Attachment metadata is missing")
                val bytes = attachments.readBytes(reference) ?: error("Attachment data is missing")
                val encoded = Base64.getEncoder().encodeToString(bytes)
                when (type) {
                    ModelApiType.GOOGLE -> add(obj("inlineData" to obj("mimeType" to str(meta.mimeType), "data" to str(encoded))))
                    ModelApiType.ANTHROPIC -> {
                        require(meta.mimeType.startsWith("image/") || meta.mimeType == "application/pdf") { "Unsupported Claude attachment" }
                        add(obj("type" to str(if (meta.mimeType == "application/pdf") "document" else "image"),
                            "source" to obj("type" to str("base64"), "media_type" to str(meta.mimeType), "data" to str(encoded))))
                    }
                    else -> { require(meta.mimeType.startsWith("image/")) { "Unsupported Copilot attachment" }
                        add(obj("type" to str("image_url"), "image_url" to obj("url" to str("data:${meta.mimeType};base64,$encoded")))) }
                }
            }
        }
        val requestEffort = config.wireReasoningEffort
        val body = buildJsonObject {
            if (type == ModelApiType.GOOGLE) {
                put("model", model)
                put("systemInstruction", obj("parts" to JsonArray(listOf(obj("text" to str(prompt))))))
                put("contents", JsonArray(messages.filter { it.role != ChatRole.SYSTEM }.map {
                    obj("role" to str(if (it.role == ChatRole.MODEL) "model" else "user"), "parts" to parts(it)) }))
                put("generationConfig", buildJsonObject {
                    put("maxOutputTokens", maxTokens); config.temperature?.let { put("temperature", it) }
                    config.topP?.let { put("topP", it) }; config.topK?.let { put("topK", it) }
                    config.googleThinkingConfig()?.let { put("thinkingConfig", it) }
                })
            } else {
                put("model", model); put("max_tokens", maxTokens)
                if (type != ModelApiType.ANTHROPIC || config.claudeAllowsSampling(model, maxTokens)) {
                    config.temperature?.let { put("temperature", it) }; config.topP?.let { put("top_p", it) }
                }
                if (type == ModelApiType.ANTHROPIC && config.claudeAllowsSampling(model, maxTokens)) config.topK?.let { put("top_k", it) }
                if (type == ModelApiType.ANTHROPIC) {
                    put("system", prompt)
                    config.claudeThinkingParameters(model, maxTokens).forEach { (key, value) -> put(key, value) }
                }
                if (type == ModelApiType.OPENAI && config.supportsThinking)
                    requestEffort?.let { put("reasoning_effort", it) }
                val sendThinking = config.sendsThinkingBack(messages.hasToolCalls())
                val native = messages.filter { it.role != ChatRole.SYSTEM }.map {
                    val content = if (type == ModelApiType.ANTHROPIC) JsonArray(
                        (if (it.role == ChatRole.MODEL && sendThinking &&
                            !it.reasoningContent.isNullOrBlank() && !it.reasoningSignature.isNullOrBlank())
                            listOf(obj("type" to str("thinking"), "thinking" to str(it.reasoningContent!!),
                                "signature" to str(it.reasoningSignature!!))) else emptyList()) + parts(it)
                    ) else buildJsonArray {
                        add(obj("type" to str("text"), "text" to str(it.content))); parts(it).filter { p -> p.jsonObject["type"] != null }.forEach { add(it) }
                    }
                    obj("role" to str(if (it.role == ChatRole.MODEL) "assistant" else "user"), "content" to content)
                }
                put("messages", JsonArray((if (type != ModelApiType.ANTHROPIC && prompt.isNotBlank()) listOf(obj("role" to str("system"), "content" to str(prompt))) else emptyList()) + native))
            }
        }
        return if (type == ModelApiType.OPENAI_RESPONSES) JsonObject(body.filterKeys { it != "messages" && it != "max_tokens" } +
            mapOf("input" to JsonArray(body.getValue("messages").jsonArray.map { message ->
                val item = message.jsonObject
                val content = item["content"]
                JsonObject(item + ("content" to if (content is JsonArray) JsonArray(content.map { part ->
                    val value = part.jsonObject
                    if (value.text("type") == "image_url") obj("type" to str("input_image"), "image_url" to value.getValue("image_url").jsonObject.getValue("url"))
                    else obj("type" to str(if (item.text("role") == "assistant") "output_text" else "input_text"), "text" to value.getValue("text"))
                }) else content!!))
            }), "max_output_tokens" to JsonPrimitive(maxTokens)) +
            (if (config.supportsThinking && requestEffort != null)
                mapOf("reasoning" to obj("effort" to str(requestEffort))) else emptyMap())) else body
    }


}
