package org.starfall.generative

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.integrations.modelapis.ModelApiAdapter
import org.starfall.generative.integrations.modelapis.ModelApiRequest
import org.starfall.generative.integrations.modelapis.anthropic.AnthropicMessagesAdapter
import org.starfall.generative.integrations.modelapis.gemini.GeminiAdapter
import org.starfall.generative.integrations.modelapis.openai.OpenAIChatCompletionsAdapter
import org.starfall.generative.integrations.modelapis.openairesponses.OpenAIResponsesAdapter
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk

/** Exercise the production protocol adapters directly rather than the former all-in-one SDK API. */
internal class ProviderAdaptersTestSupport(context: Context) {
    private val sdk = OfficialProviderSdk(AttachmentResolver(context))

    private fun adapter(type: ModelApiType): ModelApiAdapter = when (type) {
        ModelApiType.OPENAI -> OpenAIChatCompletionsAdapter(sdk)
        ModelApiType.OPENAI_RESPONSES -> OpenAIResponsesAdapter(sdk)
        ModelApiType.ANTHROPIC -> AnthropicMessagesAdapter(sdk)
        ModelApiType.GOOGLE -> GeminiAdapter(sdk)
        else -> error("No protocol adapter for $type")
    }

    suspend fun testConnection(provider: ModelProvider) = adapter(provider.type).testConnection(provider)

    fun streamOpenAi(
        provider: ModelProvider, modelName: String, messages: List<StoredMessage>, systemPrompt: String,
        temperature: Double?, topP: Double?, maxTokens: Int, reasoningEffort: String? = null
    ): Flow<GenerationEvent> = adapter(provider.type).stream(ModelApiRequest(
        provider, modelName, messages, systemPrompt, temperature, topP, null, maxTokens,
        reasoningEffort = reasoningEffort
    ))

    fun streamAnthropic(
        provider: ModelProvider, modelName: String, messages: List<StoredMessage>, systemPrompt: String,
        temperature: Double?, topP: Double?, maxTokens: Int, topK: Int? = null,
        callerSystemPrompt: String? = null, enabledModelTools: List<String> = emptyList()
    ): Flow<GenerationEvent> = adapter(ModelApiType.ANTHROPIC).stream(ModelApiRequest(
        provider, modelName, messages, systemPrompt, temperature, topP, topK, maxTokens,
        callerSystemPrompt = callerSystemPrompt, enabledModelTools = enabledModelTools
    ))

    fun streamGoogle(
        provider: ModelProvider, modelName: String, messages: List<StoredMessage>, systemPrompt: String,
        temperature: Double?, topP: Double?, maxTokens: Int, topK: Int? = null
    ): Flow<GenerationEvent> = adapter(ModelApiType.GOOGLE).stream(ModelApiRequest(
        provider, modelName, messages, systemPrompt, temperature, topP, topK, maxTokens
    ))
}
