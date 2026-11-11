package org.starfall.generative.integrations.modelapis.sdk

import com.openai.client.OpenAIClientImpl
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.google.genai.Client
import com.google.genai.types.ClientOptions
import com.google.genai.types.HttpOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.*
import org.starfall.generative.shared.model.auth.*
import org.starfall.generative.shared.model.generation.*
import org.starfall.generative.shared.model.language.*
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import java.time.Duration
import org.starfall.generative.integrations.modelapis.attachments.PreparedAttachment
import org.starfall.generative.integrations.modelapis.attachments.AttachmentMessages

internal inline fun <R> com.openai.client.OpenAIClient.useClient(block: (com.openai.client.OpenAIClient) -> R): R =
    try { block(this) } finally { close() }

internal inline fun <R> com.anthropic.client.AnthropicClient.useClient(block: (com.anthropic.client.AnthropicClient) -> R): R =
    try { block(this) } finally { close() }

/** Each operation owns its client and immutable provider snapshot, including credentials.
 * Switching providers cannot redirect an in-flight request or reuse another provider's key.
 */
internal class OfficialProviderSdk(
    private val attachments: AttachmentResolver,
    messages: AttachmentMessages = AttachmentMessages()
) {
    // SDKs wrap an interrupted socket in their own IOException type. Preserve
    // coroutine cancellation instead of displaying it as a generation failure.
    internal suspend fun <T> sdkCall(block: () -> T): T = try {
        runInterruptible(block = block)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        throw e
    }

    private val content = SdkAttachmentContent(attachments, messages)
    internal fun openAiChatContent(message: StoredMessage, prepared: Map<String, PreparedAttachment> = emptyMap()) = content.openAiChatContent(message, prepared)
    internal fun openAiResponseContent(message: StoredMessage, prepared: Map<String, PreparedAttachment> = emptyMap()) = content.openAiResponseContent(message, prepared)
    internal fun anthropicContent(message: StoredMessage, prepared: Map<String, PreparedAttachment> = emptyMap()) = content.anthropicContent(message, prepared)
    internal suspend fun googleParts(client: Client, message: StoredMessage, prepared: Map<String, PreparedAttachment> = emptyMap()) = content.googleParts(client, message, prepared)

    internal fun baseUrl(provider: ModelProvider): String {
        var base = provider.baseUrl.trim().trimEnd('/')
        when (provider.type) {
            ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES -> for (suffix in listOf("/chat/completions", "/responses", "/models")) {
                if (base.endsWith(suffix)) base = base.removeSuffix(suffix)
            }
            // Anthropic SDK adds /v1/messages itself.
            ModelApiType.ANTHROPIC -> {
                base = base.removeSuffix("/messages").removeSuffix("/models").removeSuffix("/v1")
            }
            else -> Unit
        }
        return base
    }

    internal fun headers(provider: ModelProvider): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val auth = provider.auth
        result.putAll(auth.requestHeaders(provider.type))
        result.putAll(provider.config.headers)
        return result
    }

    internal fun openAi(provider: ModelProvider) = OpenAIClientImpl(com.openai.core.ClientOptions.builder().apply {
        val transport = com.openai.client.okhttp.OkHttpClient.builder().timeout(Duration.ofMinutes(2)).build()
        httpClient(object : com.openai.core.http.HttpClient by transport {
            override fun execute(request: com.openai.core.http.HttpRequest,
                                 requestOptions: com.openai.core.RequestOptions): com.openai.core.http.HttpResponse {
                // Interrupting a blocking socket read does not reliably close the socket.
                // The official async transport cancels its OkHttp call when this future is cancelled.
                val filesIndex = request.pathSegments.indexOf("files")
                val generation = request.pathSegments.lastOrNull() in setOf("responses", "completions")
                val endpoint = if (filesIndex >= 0) provider.config.customEndpoints["files"]
                    else if (generation) provider.config.customEndpoints[if (provider.type == ModelApiType.OPENAI_RESPONSES) "responses" else "chat"] else null
                val suffix = if (filesIndex >= 0) request.pathSegments.drop(filesIndex + 1) else emptyList()
                val routed = if (endpoint.isNullOrBlank()) request else request.toBuilder().baseUrl(endpoint).pathSegments(suffix).build()
                val future = transport.executeAsync(routed, requestOptions)
                try {
                    return future.get()
                } catch (e: InterruptedException) {
                    future.cancel(true)
                    throw e
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw (e.cause ?: e)
                }
            }
        })
        baseUrl(baseUrl(provider))
        timeout(Duration.ofMinutes(2))
        maxRetries(0)
        // The SDK requires an explicit authentication strategy. Gateways can use
        // headers or query parameters rather than its built-in bearer credential.
        if (provider.auth.method == AuthMethod.PLATFORM_DEFAULT && provider.auth.token.isNotBlank()) {
            apiKey(provider.auth.token)
            provider.config.headers.forEach { (key, value) -> putHeader(key, value) }
        } else httpRequestAuthenticator(object : com.openai.core.http.HttpRequestAuthenticator {
            override fun authenticate(request: com.openai.core.http.HttpRequest) = request.toBuilder().apply {
                headers(provider).forEach { (key, value) -> putHeader(key, value) }
                if (provider.auth.method == AuthMethod.QUERY_PARAM && provider.auth.token.isNotBlank()) {
                    putQueryParam(provider.auth.key?.takeIf { it.isNotBlank() } ?: "key", provider.auth.token)
                }
            }.build()
        })
    }.build())

    internal fun anthropic(provider: ModelProvider) = AnthropicOkHttpClient.builder().apply {
        baseUrl(baseUrl(provider))
        val anthropicBase = provider.baseUrl.trim().trimEnd('/').removeSuffix("/messages").removeSuffix("/models")
        run {
            addInterceptor(com.anthropic.core.http.Interceptor { transport ->
                object : com.anthropic.core.http.HttpClient by transport {
                    fun route(request: com.anthropic.core.http.HttpRequest): com.anthropic.core.http.HttpRequest {
                        val filesIndex = request.pathSegments.indexOf("files")
                        if (filesIndex >= 0) {
                            val endpoint = provider.config.customEndpoints["files"]?.takeIf { it.isNotBlank() } ?: return request
                            return request.toBuilder().baseUrl(endpoint)
                                .replaceAllPathSegments(*request.pathSegments.drop(filesIndex + 1).toTypedArray()).build()
                        }
                        val catalog = request.pathSegments.lastOrNull() == "models"
                        val key = if (catalog) "catalog" else "messages"
                        val endpoint = provider.config.customEndpoints[key]?.takeIf { it.isNotBlank() }
                            ?: if (catalog) provider.config.customListModelsUrl?.takeIf { it.isNotBlank() } ?: "$anthropicBase/models" else "$anthropicBase/messages"
                        return request.toBuilder().baseUrl(endpoint).replaceAllPathSegments().build()
                    }
                    override fun execute(request: com.anthropic.core.http.HttpRequest, requestOptions: com.anthropic.core.RequestOptions) =
                        transport.execute(route(request), requestOptions)
                    override fun executeAsync(request: com.anthropic.core.http.HttpRequest, requestOptions: com.anthropic.core.RequestOptions) =
                        transport.executeAsync(route(request), requestOptions)
                }
            })
        }
        timeout(Duration.ofMinutes(2))
        maxRetries(0)
        if (provider.auth.method == AuthMethod.PLATFORM_DEFAULT && provider.auth.token.isNotBlank()) apiKey(provider.auth.token)
        (if (provider.auth.method == AuthMethod.PLATFORM_DEFAULT) provider.config.headers else headers(provider))
            .forEach { (key, value) -> putHeader(key, value) }
        if (provider.auth.method == AuthMethod.QUERY_PARAM && provider.auth.token.isNotBlank()) {
            putQueryParam(provider.auth.key?.takeIf { it.isNotBlank() } ?: "key", provider.auth.token)
        }
    }.build()

    internal fun google(provider: ModelProvider): Client {
        val base = baseUrl(provider).removeSuffix("/models")
        val version = Regex("/(v1(?:beta|alpha)?)$").find(base)?.groupValues?.get(1)
        // Supply auth in the transport so custom headers/query auth work with gateways too.
        val transport = OkHttpClient.Builder().addInterceptor(org.starfall.generative.shared.subfeatures.logging.logic.DebugLogInterceptor()).addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
            if (version == null) {
                val basePath = base.toHttpUrlOrNull()?.encodedPath?.trimEnd('/') ?: error("Invalid Gemini base URL")
                val prefix = "$basePath/v1beta/"
                if (original.url.encodedPath.startsWith(prefix)) {
                    request.url(original.url.newBuilder().encodedPath(basePath + "/" + original.url.encodedPath.removePrefix(prefix)).build())
                }
            }
            provider.config.customEndpoints["generate"]?.takeIf { it.isNotBlank() }?.let { template ->
                val match = Regex("/models/([^/:]+):([^/?]+)$").find(original.url.encodedPath)
                if (match != null) {
                    val target = template.replace("{model}", match.groupValues[1]).replace("{method}", match.groupValues[2])
                    val url = target.toHttpUrlOrNull()
                        ?: error("Invalid custom Gemini endpoint")
                    val routed = url.newBuilder()
                    for (name in original.url.queryParameterNames) for (value in original.url.queryParameterValues(name)) routed.addQueryParameter(name, value)
                    request.url(routed.build())
                }
            }
            if (provider.auth.method == AuthMethod.PLATFORM_DEFAULT && provider.auth.token.isNotBlank()) {
                provider.config.headers.forEach { (key, value) -> request.header(key, value) }
            } else {
                request.removeHeader("x-goog-api-key")
                headers(provider).forEach { (key, value) -> request.header(key, value) }
            }
            if (provider.auth.method == AuthMethod.QUERY_PARAM && provider.auth.token.isNotBlank()) {
                request.url(request.build().url.newBuilder()
                    .setQueryParameter(provider.auth.key?.takeIf { it.isNotBlank() } ?: "key", provider.auth.token).build())
            }
            chain.proceed(request.build())
        }.build()
        return Client.builder()
            .apiKey(if (provider.auth.method == AuthMethod.PLATFORM_DEFAULT && provider.auth.token.isNotBlank()) provider.auth.token else "gateway-auth-configured-in-transport")
            .httpOptions(HttpOptions.builder()
                .baseUrl(if (version == null) base else base.removeSuffix("/$version"))
                .apiVersion(version ?: "v1beta").timeout(120_000).build())
            .clientOptions(ClientOptions.builder().customHttpClient(transport).build())
            .build()
    }

    internal suspend fun connectionResult(provider: ModelProvider, operation: suspend () -> Unit): Result<String> = try {
        withContext(Dispatchers.IO) { operation() }
        Result.success("${provider.type.displayName} connection successful!")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }

}
