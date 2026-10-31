package org.starfall.generative.integrations.modelapis.devin

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.starfall.generative.integrations.modelapis.account.*
import org.starfall.generative.shared.auth.OAuthBrowser
import org.starfall.generative.shared.auth.OAuthCallbackService
import org.starfall.generative.shared.auth.awaitLoopbackAuthorizationCode
import org.starfall.generative.shared.files.AttachmentResolver
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.wireReasoningEffort
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.oauthCredentialId
import org.starfall.generative.shared.model.provider.streamEnabledFor
import org.starfall.generative.shared.networking.ToolHttp
import org.starfall.generative.shared.networking.obj
import org.starfall.generative.shared.networking.str
import org.starfall.generative.shared.networking.text

/** Devin CLI account against native Cognition Connect-RPC, not a fictitious OpenAI endpoint. */
internal class DevinAdapter(
    context: Context,
    private val attachments: AttachmentResolver,
    private val loginApiBaseUrl: String = "https://api.devin.ai",
    private val openAuthorizationBrowser: suspend (Context, String) -> Unit = OAuthBrowser::open
) : AccountProviderAdapter {
    override val providerType = ModelApiType.DEVIN
    private val appContext = context.applicationContext
    private val http = ToolHttp()
    private val store = AccountTokenStore(appContext, "devin")
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionIndex = ConcurrentHashMap<String, AtomicInteger>()
    private val sessionsLock = Mutex()
    private val marker = "account:devin:v1"
    private val modelCatalog: List<DiscoveredModel> by lazy {
        val source = appContext.assets.open("devin_models_catalog.json").bufferedReader().use { it.readText() }
        (json.parseToJsonElement(source).jsonObject["devin"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { item -> item.text("id").takeIf(String::isNotBlank)?.let { id ->
                DiscoveredModel(id, (item["context_length"] as? JsonPrimitive)?.intOrNull,
                    item.text("display_name"), item)
            } }
    }

    override suspend fun authorize(provider: ModelProvider): Result<ModelProvider> = try {
        require(provider.type == providerType)
        Result.success(OAuthCallbackService.keepAlive(appContext) {
            val challenge = DevinOAuthProtocol.begin()
            val code = awaitLoopbackAuthorizationCode(
                DevinOAuthProtocol.REDIRECT_URI, challenge.authorizationUrl, challenge.state
            ) { openAuthorizationBrowser(appContext, it) }
            val response = http.post(loginApiBaseUrl.trimEnd('/') + "/auth/cli/token", buildJsonObject {
                put("code", code); put("code_verifier", challenge.verifier)
            })
            val token = DevinOAuthProtocol.sessionToken(response.text("token"))
            val profile = try {
                http.json(http.request(loginApiBaseUrl.trimEnd('/') + "/v3/self",
                    extraHeaders = mapOf("Authorization" to "Bearer $token")).get().build())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            val identity = profile?.text("user_name")?.takeIf(String::isNotBlank)
                ?: profile?.text("user_id")?.takeIf(String::isNotBlank)
            store.save(provider.oauthCredentialId, AccountTokenState(token, "", accountId = identity,
                email = profile?.text("email")?.takeIf(String::isNotBlank)))
            provider.copy(auth = Authorization(AuthMethod.OAUTH, identity, marker, provider.auth.oauthAccountId))
        })
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }

    private fun credential(provider: ModelProvider): AccountTokenState {
        require(provider.type == providerType && provider.auth.method == AuthMethod.OAUTH && provider.auth.value == marker) {
            "Devin is not authorized. Sign in from Provider settings."
        }
        return store.load(provider.oauthCredentialId)
            ?: error("Devin credentials are missing. Sign in again.")
    }

    override suspend fun prepareAuthenticatedProvider(provider: ModelProvider): ModelProvider {
        credential(provider)
        return provider
    }

    override suspend fun testConnection(provider: ModelProvider): Result<String> = try {
        val token = credential(provider)
        val profile = http.json(http.request(loginApiBaseUrl.trimEnd('/') + "/v3/self",
            extraHeaders = mapOf("Authorization" to "Bearer ${token.accessToken}")).get().build())
        Result.success("Devin account ready: " + profile.text("user_name").ifBlank {
            profile.text("user_id").ifBlank { "authenticated" }
        })
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }

    override suspend fun fetchModels(provider: ModelProvider): List<String> = fetchModelCatalog(provider).map { it.id }

    override suspend fun fetchModelCatalog(provider: ModelProvider): List<DiscoveredModel> {
        credential(provider)
        // Static catalog is a discoverability aid, not a guarantee of account entitlements.
        return withContext(Dispatchers.IO) { modelCatalog }
    }

    override fun clearCredentials(providerId: String) = store.delete(providerId)
    override fun exportCredentials(): Map<String, JsonObject> = store.exportAll()
    override fun importCredentials(credentials: Map<String, JsonObject>) = store.importAll(credentials)

    override fun streamEvents(provider: ModelProvider, modelName: String,
        messages: List<StoredMessage>, systemPrompt: String, maxOutputTokens: Int): Flow<GenerationEvent> =
        channelFlow {
            val token = credential(provider).accessToken
            val modelConfig = provider.config.modelConfigs[modelName] ?: ModelConfiguration()
            val turns = messages.filter { it.role != ChatRole.SYSTEM }.map { message ->
                val media = message.files.mapNotNull { attachment ->
                    val metadata = attachments.metadata(attachment) ?: error("Devin attachment metadata is missing")
                    require(metadata.mimeType.startsWith("image/")) { "Devin supports only image attachments" }
                    val bytes = attachments.readBytes(attachment) ?: error("Devin attachment data is missing")
                    metadata.mimeType to Base64.getEncoder().encodeToString(bytes)
                }
                DevinWire.Turn(
                    source = if (message.role == ChatRole.MODEL) 2 else 1,
                    content = message.content,
                    id = message.id,
                    thinking = message.reasoningContent.orEmpty(), images = media
                )
            }
            var bufferedText = StringBuilder()
            var bufferedReasoning = StringBuilder()
            val result = generate(provider, modelName, token, turns, emptyList(), systemPrompt,
                modelConfig, maxOutputTokens, messages.firstOrNull()?.id,
                onText = { if (provider.streamEnabledFor(modelName)) send(GenerationEvent.Text(it)) else bufferedText.append(it) },
                onReasoning = { if (provider.streamEnabledFor(modelName)) send(GenerationEvent.Reasoning(it)) else bufferedReasoning.append(it) },
                onUsage = { send(GenerationEvent.Usage(it)) }
            )
            if (!provider.streamEnabledFor(modelName)) {
                if (bufferedReasoning.isNotEmpty()) send(GenerationEvent.Reasoning(bufferedReasoning.toString()))
                if (bufferedText.isNotEmpty()) send(GenerationEvent.Text(bufferedText.toString()))
            }
            result.text("reasoning_signature").takeIf(String::isNotBlank)?.let { signature ->
                send(GenerationEvent.Reasoning("", signature))
            }
            if ((result["tool_calls"] as? JsonArray).orEmpty().isNotEmpty()) {
                error("Devin returned tool calls without enabled tools. Enable the required tools in Generative.")
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun requestToolTurn(provider: ModelProvider, modelName: String,
        history: List<JsonObject>, tools: List<ToolDefinition>, systemPrompt: String,
        sendThinking: Boolean, onText: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit, onUsage: suspend (TokenUsage) -> Unit,
        sessionKey: String?): JsonObject {
        val token = credential(provider).accessToken
        val config = provider.config.modelConfigs[modelName] ?: ModelConfiguration()
        val turns = history.filter { it.text("role") in setOf("user", "assistant", "tool") }.map { message ->
            val source = when (message.text("role")) { "assistant" -> 2; "tool" -> 4; else -> 1 }
            val calls = (message["tool_calls"] as? JsonArray).orEmpty().map { raw ->
                val call = raw.jsonObject
                val fn = call["function"] as? JsonObject ?: error("Invalid Devin tool call")
                DevinWire.Call(call.text("id"), fn.text("name"), fn["arguments"]?.let {
                    if (it is JsonPrimitive) it.content else it.toString()
                } ?: "{}")
            }
            val images = (message["_attachments"] as? JsonArray).orEmpty().map { raw ->
                val value = raw.jsonObject
                val mime = value.text("mimeType")
                require(mime.startsWith("image/")) { "Devin supports only image attachments" }
                mime to value.text("data")
            }
            DevinWire.Turn(source, message.text("content"),
                toolCalls = calls, toolCallId = message.text("tool_call_id"),
                thinking = if (sendThinking) message.text("reasoning_content") else "",
                images = images,
                signature = message.text("reasoning_signature").takeIf { it.isNotBlank() }
                    ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: byteArrayOf()
            )
        }
        val streaming = provider.streamEnabledFor(modelName)
        return generate(provider, modelName, token, turns, tools, systemPrompt, config,
            provider.config.maxTokens, sessionKey ?: history.firstOrNull { it.text("role") == "user" }?.text("id"),
            onText = { if (streaming) onText(it) },
            onReasoning = { if (streaming) onReasoning(it) }, onUsage = onUsage)
    }

    private suspend fun generate(provider: ModelProvider, model: String, token: String,
        turns: List<DevinWire.Turn>, tools: List<ToolDefinition>, systemPrompt: String,
        config: ModelConfiguration, maxOutput: Int, conversationKey: String?,
        onText: suspend (String) -> Unit, onReasoning: suspend (String) -> Unit,
        onUsage: suspend (TokenUsage) -> Unit): JsonObject {
        val sessionID = UUID.nameUUIDFromBytes(
            "devin:${provider.id}:${conversationKey ?: turns.firstOrNull()?.id ?: UUID.randomUUID()}".toByteArray()).toString()
        val ordinal = sessionsLock.withLock {
            if (sessionIndex.size >= 400 && !sessionIndex.containsKey(sessionID)) sessionIndex.clear()
            sessionIndex.computeIfAbsent(sessionID) { AtomicInteger() }.getAndIncrement()
        }
        val available = modelCatalog.firstOrNull { it.id == model.removePrefix("devin/") }
        val levels = ((available?.metadata?.get("thinking") as? JsonObject)?.get("levels") as? JsonArray)
            .orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val uid = devinModelUID(model, config.wireReasoningEffort, levels)
        val proto = DevinWire.buildChat(token, uid, systemPrompt, turns, tools,
            config.temperature, maxOutput, sessionID, ordinal)
        val payload = DevinWire.envelope(proto)
        val sentryTrace = buildString {
            val random = ByteArray(24).also(SecureRandom()::nextBytes)
            random.forEachIndexed { i, b ->
                if (i == 16) append('-')
                append("%02x".format(b.toInt() and 0xff))
            }
            append("-1")
        }
        val request = http.request("${ModelApiType.DEVIN.defaultBaseUrl}${DevinWire.CHAT_PATH}",
            extraHeaders = mapOf(
                "Authorization" to "Basic $token-$token",
                "Content-Type" to "application/connect+proto",
                "Connect-Protocol-Version" to "1",
                "Accept" to "*/*",
                "User-Agent" to "",
                "Sentry-Trace" to sentryTrace
            )).post(payload.toRequestBody("application/connect+proto".toMediaType())).build()
        return withContext(Dispatchers.IO) {
            http.withResponse(request) { response ->
                http.requireSuccess(response)
                val text = StringBuilder()
                val thinking = StringBuilder()
                val activeCalls = linkedMapOf<String, Array<String>>()
                var lastCallID: String? = null
                var usage: DevinWire.Usage? = null
                var sig = byteArrayOf()
                var sigType = ""
                var ended = false
                val input = response.body.byteStream()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val frame = DevinWire.nextFrame(input) ?: break
                    if (frame.flag and 2 == 2) {
                        ended = true
                        val trailer = runCatching { json.parseToJsonElement(String(frame.payload)).jsonObject }.getOrNull()
                        trailer?.get("error")?.let { error("Devin Connect RPC error: ${it.toString().take(300)}") }
                        trailer?.text("code")?.takeIf { it.isNotBlank() && it != "ok" }?.let { code ->
                            error("Devin Connect RPC $code: ${trailer.text("message").take(300)}")
                        }
                        break
                    }
                    val delta = DevinWire.decodeDelta(frame.payload)
                    if (delta.text.isNotEmpty()) {
                        text.append(delta.text)
                        require(text.length <= 512_000) { "Devin output exceeds size limit" }
                        onText(delta.text)
                    }
                    if (delta.reasoning.isNotEmpty()) {
                        thinking.append(delta.reasoning)
                        require(thinking.length <= 512_000) { "Devin reasoning exceeds size limit" }
                        onReasoning(delta.reasoning)
                    }
                    if (delta.signature.isNotEmpty()) sig += delta.signature
                    if (delta.signatureType.isNotBlank()) sigType = delta.signatureType
                    delta.usage?.let { usage = it }
                    delta.calls.forEach { chunk ->
                        val id = chunk.id.ifBlank { lastCallID ?: error("Devin tool delta lacks a call ID") }
                        lastCallID = id
                        val parts = activeCalls.getOrPut(id) { arrayOf("", "", "") }
                        if (parts[0].isEmpty()) parts[0] = chunk.name else if (chunk.name.isNotEmpty() && parts[0] != chunk.name) {
                            parts[0] += chunk.name
                        }
                        parts[1] += chunk.arguments
                        parts[2] += chunk.invalidArguments
                        require(activeCalls.size <= 128 && parts[1].length + parts[2].length <= 65_536) {
                            "Devin tool call exceeds limits"
                        }
                    }
                }
                check(ended) { "Devin Connect stream terminated before its final trailer" }
                usage?.let { u ->
                    val input = u.input + u.cached
                    if (input >= 0 && u.output >= 0) onUsage(TokenUsage(input, u.output))
                }
                val calls = JsonArray(activeCalls.map { (id, parts) ->
                    val name = parts[0]
                    require(name.isNotBlank()) { "Devin returned a nameless tool call" }
                    val args = parts[1].ifBlank { parts[2] }.ifBlank { "{}" }
                    val parsed = runCatching { json.parseToJsonElement(args) as? JsonObject }.getOrNull()
                        ?: error("Devin tool $name returned malformed JSON arguments")
                    obj("id" to str(id), "type" to str("function"),
                        "function" to obj("name" to str(name), "arguments" to parsed))
                })
                buildJsonObject {
                    put("role", "assistant"); put("content", text.toString()); put("tool_calls", calls)
                    if (thinking.isNotEmpty()) put("reasoning_content", thinking.toString())
                    if (sig.isNotEmpty()) put("reasoning_signature", Base64.getEncoder().encodeToString(sig))
                    if (sigType.isNotBlank()) put("signature_type", sigType)
                }
            }
        }
    }

}
