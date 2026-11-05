package org.starfall.generative.integrations.modelapis

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.integrations.modelapis.anthropic.AnthropicMessagesAdapter
import org.starfall.generative.integrations.modelapis.gemini.GeminiAdapter
import org.starfall.generative.integrations.modelapis.ollama.OllamaAdapter
import org.starfall.generative.integrations.modelapis.openai.OpenAIChatCompletionsAdapter
import org.starfall.generative.integrations.modelapis.openairesponses.OpenAIResponsesAdapter
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk

internal class ModelApiAdapterRegistry(
    context: Context,
    sdk: OfficialProviderSdk,
    httpClient: HttpClient,
    attachments: AttachmentResolver,
    json: Json
) {
    val modelCatalog = ModelCatalogAdapter(httpClient, json)

    private val adapters: Map<ModelApiType, ModelApiAdapter> = listOf(
        OpenAIChatCompletionsAdapter(sdk),
        OpenAIResponsesAdapter(sdk),
        AnthropicMessagesAdapter(sdk),
        GeminiAdapter(sdk),
        OllamaAdapter(context, httpClient, attachments, json)
    ).associateBy(ModelApiAdapter::modelApiType)

    fun get(type: ModelApiType): ModelApiAdapter? = adapters[type]

    suspend fun testConnection(provider: ModelProvider): Result<String> =
        get(provider.type)?.testConnection(provider)
            ?: Result.failure(IllegalArgumentException("No connection adapter is registered for ${provider.type.displayName}."))

    suspend fun cachedOllamaModels(baseUrl: String): List<String>? =
        (adapters[ModelApiType.OLLAMA] as OllamaAdapter).cachedModels(baseUrl)

    suspend fun fetchOllamaModels(baseUrl: String): List<String> =
        (adapters[ModelApiType.OLLAMA] as OllamaAdapter).fetchModels(baseUrl)
}
