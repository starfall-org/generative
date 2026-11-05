package org.starfall.generative.integrations.modelapis.ollama

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.*
import org.starfall.generative.shared.model.auth.*
import org.starfall.generative.shared.model.generation.*
import org.starfall.generative.shared.model.language.*
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.integrations.modelapis.ModelApiAdapter
import org.starfall.generative.integrations.modelapis.ModelApiRequest
import org.starfall.generative.shared.helpers.networking.providerTokenUsage
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal class OllamaAdapter(
    private val context: Context,
    private val httpClient: HttpClient,
    private val attachments: AttachmentResolver,
    private val json: Json
) : ModelApiAdapter {
    override val modelApiType = ModelApiType.OLLAMA
    private val modelCache = OllamaModelCache(context)
    private val discoveryLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun cachedModels(baseUrl: String): List<String>? = modelCache.read(baseUrl)?.models

    suspend fun fetchModels(baseUrl: String): List<String> =
        discoveryLocks.getOrPut(resolveTagsUrl(baseUrl)) { Mutex() }.withLock {
            val cached = modelCache.read(baseUrl)
            if (cached != null && modelCache.isFresh(cached)) return@withLock cached.models
            try {
                val response = httpClient.get(resolveTagsUrl(baseUrl))
                if (!response.status.isSuccess()) return@withLock cached?.models.orEmpty()
                val models = json.parseToJsonElement(response.bodyAsText()).jsonObject["models"]?.jsonArray?.mapNotNull {
                    it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                }?.distinct().orEmpty()
                modelCache.write(baseUrl, models)
                models
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cached?.models.orEmpty()
            }
        }

    override suspend fun testConnection(provider: ModelProvider): Result<String> = try {
        val response = httpClient.get(provider.config.customEndpoints["catalog"]?.takeIf { it.isNotBlank() } ?: provider.config.customListModelsUrl?.takeIf { it.isNotBlank() } ?: resolveTagsUrl(provider.baseUrl)) {
            provider.auth.requestHeaders(provider.type).forEach { (key, value) -> header(key, value) }
            if (provider.auth.method == AuthMethod.QUERY_PARAM && provider.auth.token.isNotBlank()) {
                parameter(provider.auth.key?.takeIf { it.isNotBlank() } ?: "key", provider.auth.token)
            }
            provider.config.headers.forEach { (key, value) -> header(key, value) }
        }
        check(response.status.isSuccess()) { "HTTP ${response.status.value}: ${response.status.description}" }
        val models = json.parseToJsonElement(response.bodyAsText()).jsonObject["models"]?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        Result.success(if (models.isEmpty()) "Connected to Ollama! (0 models installed)" else
            "Connected! Found ${models.size} models: ${models.take(4).joinToString(", ")}${if (models.size > 4) "..." else ""}")
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun stream(request: ModelApiRequest): Flow<GenerationEvent> = flow {
        val provider = request.provider
        val ollamaMessages = buildList<JsonObject> {
            if (request.systemPrompt.isNotBlank()) add(buildJsonObject {
                put("role", "system")
                put("content", request.systemPrompt)
            })
            request.messages.forEach { message -> add(buildJsonObject {
                put("role", if (message.role == ChatRole.MODEL) "assistant" else "user")
                put("content", message.content)
                val images = message.files.mapNotNull { reference ->
                    val meta = attachments.metadata(reference) ?: return@mapNotNull null
                    if (!meta.isImage) return@mapNotNull null
                    attachments.readBytes(reference)?.let { Base64.getEncoder().encodeToString(it) }
                }
                if (images.isNotEmpty()) put("images", JsonArray(images.map(::JsonPrimitive)))
            }) }
        }
        val body = buildJsonObject {
            put("model", request.modelName)
            put("messages", JsonArray(ollamaMessages))
            put("stream", provider.config.supportStream)
            put("options", buildJsonObject {
                put("num_predict", request.maxTokens)
                request.temperature?.let { put("temperature", it) }
                request.topP?.let { put("top_p", it) }
                request.topK?.let { put("top_k", it) }
            })
        }
        try {
            val response = httpClient.post(provider.config.customEndpoints["chat"]?.takeIf { it.isNotBlank() } ?: resolveChatUrl(provider.baseUrl)) {
                contentType(ContentType.Application.Json)
                provider.auth.requestHeaders(provider.type).forEach { (key, value) -> header(key, value) }
                if (provider.auth.method == AuthMethod.QUERY_PARAM && provider.auth.token.isNotBlank()) {
                    parameter(provider.auth.key?.takeIf { it.isNotBlank() } ?: "key", provider.auth.token)
                }
                provider.config.headers.forEach { (key, value) -> header(key, value) }
                setBody(body.toString())
            }
            check(response.status.isSuccess()) { "Ollama request failed: HTTP ${response.status.value}" }
            if (!provider.config.supportStream) {
                val parsed = json.parseToJsonElement(response.bodyAsText()).jsonObject
                parsed["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                parsed["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
                providerTokenUsage(modelApiType, parsed)?.let { emit(GenerationEvent.Usage(it)) }
                return@flow
            }
            val channel = response.bodyAsChannel()
            var usage: TokenUsage? = null
            var completed = false
            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                if (line.isBlank()) continue
                val parsed = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                providerTokenUsage(modelApiType, parsed)?.let { usage = it }
                if (parsed["done_reason"]?.jsonPrimitive?.contentOrNull == "length")
                    error(OUTPUT_TOKEN_LIMIT_REACHED)
                if (parsed["done"]?.jsonPrimitive?.booleanOrNull == true) completed = true
                parsed["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                parsed["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
            }
            requireStreamCompleted(completed)
            usage?.let { emit(GenerationEvent.Usage(it)) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        }
    }

    private fun resolveTagsUrl(baseUrl: String): String {
        var clean = baseUrl.trim().trimEnd('/')
        if (clean.endsWith("/tags")) return clean
        if (clean.endsWith("/chat")) clean = clean.removeSuffix("/chat").trimEnd('/')
        if (clean.endsWith("/generate")) clean = clean.removeSuffix("/generate").trimEnd('/')
        return if (clean.endsWith("/api")) "$clean/tags" else "$clean/api/tags"
    }

    private fun resolveChatUrl(baseUrl: String): String {
        var clean = baseUrl.trim().trimEnd('/')
        listOf("/tags", "/chat", "/generate").forEach { suffix ->
            if (clean.endsWith(suffix)) clean = clean.removeSuffix(suffix).trimEnd('/')
        }
        return if (clean.endsWith("/api")) "$clean/chat" else "$clean/api/chat"
    }
}
