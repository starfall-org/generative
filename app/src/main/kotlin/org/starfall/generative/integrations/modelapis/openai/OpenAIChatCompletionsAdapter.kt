package org.starfall.generative.integrations.modelapis.openai

import kotlinx.coroutines.flow.Flow
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionStreamOptions
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.completions.CompletionUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.*
import org.starfall.generative.shared.model.auth.*
import org.starfall.generative.shared.model.generation.*
import org.starfall.generative.shared.model.language.*
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.integrations.modelapis.sdk.useClient
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.generation.requireStreamCompleted
import org.starfall.generative.shared.model.generation.OUTPUT_TOKEN_LIMIT_REACHED
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.integrations.modelapis.ModelApiAdapter
import org.starfall.generative.integrations.modelapis.ModelApiRequest
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk

internal class OpenAIChatCompletionsAdapter(private val sdk: OfficialProviderSdk) : ModelApiAdapter {
    override val modelApiType = ModelApiType.OPENAI


    override suspend fun testConnection(provider: ModelProvider): Result<String> = sdk.connectionResult(provider) {
        sdk.sdkCall { sdk.openAi(provider).useClient { it.models().list() } }
    }

    override fun stream(request: ModelApiRequest): Flow<GenerationEvent> = streamOpenAi(
        request.provider, request.modelName, request.messages, request.systemPrompt,
        request.temperature, request.topP, request.maxTokens, request.reasoningEffort, request.sendThinkingContent
    )

    private fun streamOpenAi(
        provider: ModelProvider,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int,
        reasoningEffort: String? = null,
        sendThinkingContent: Boolean = false
    ): Flow<GenerationEvent> = flow {
        sdk.openAi(provider).useClient { client ->
            val params = ChatCompletionCreateParams.builder().model(modelName).maxCompletionTokens(maxTokens.toLong())
            if (systemPrompt.isNotBlank()) params.addSystemMessage(systemPrompt)
            messages.forEach { message ->
                if (message.role == ChatRole.MODEL) {
                    val assistant = com.openai.models.chat.completions.ChatCompletionAssistantMessageParam.builder()
                        .content(message.content)
                    message.reasoningContent
                        ?.takeIf { sendThinkingContent && it.isNotBlank() }
                        ?.let { reasoning ->
                            assistant.putAdditionalProperty(
                                "reasoning_content",
                                com.openai.core.JsonValue.from(reasoning)
                            )
                        }
                    params.addMessage(assistant.build())
                } else if (message.files.isNotEmpty()) {
                    params.addMessage(
                        ChatCompletionUserMessageParam.builder()
                            .contentOfArrayOfContentParts(sdk.openAiChatContent(message))
                            .build()
                    )
                } else {
                    params.addUserMessage(message.content)
                }
            }
            temperature?.let { params.temperature(it) }
            topP?.let { params.topP(it) }
            reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { effort ->
                params.putAdditionalBodyProperty("reasoning_effort", com.openai.core.JsonValue.from(effort))
            }

            fun reasoningFrom(properties: Map<String, com.openai.core.JsonValue>): String? =
                runCatching { properties["reasoning_content"]?.convert(String::class.java) }
                    .getOrNull()?.takeIf { it.isNotBlank() }

            if (!provider.config.supportStream) {
                val response = sdk.sdkCall { client.chat().completions().create(params.build()) }
                response.choices().firstOrNull()?.message()?.let { message ->
                    reasoningFrom(message._additionalProperties())?.let { emit(GenerationEvent.Reasoning(it)) }
                    message.content().orElse(null)?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                }
                response._usage().asKnown().orElse(null)?.tokenUsage()?.let { emit(GenerationEvent.Usage(it)) }
                return@useClient
            }

            params.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
            sdk.sdkCall { client.chat().completions().createStreaming(params.build()) }.use { response ->
                val iterator = response.stream().iterator()
                var usage: TokenUsage? = null
                var completed = false
                while (sdk.sdkCall { iterator.hasNext() }) {
                    val chunk = iterator.next()
                    // The final usage chunk has no choices, so inspect it before the content early exit.
                    chunk._usage().asKnown().orElse(null)?.tokenUsage()?.let { usage = it }
                    val choice = chunk._choices().asKnown().orElse(emptyList()).firstOrNull() ?: continue
                    choice.finishReason().orElse(null)?.let { reason ->
                        completed = true
                        if (reason.asString() == "length") error(OUTPUT_TOKEN_LIMIT_REACHED)
                    }
                    val delta = choice.delta()
                    reasoningFrom(delta._additionalProperties())?.let { emit(GenerationEvent.Reasoning(it)) }
                    delta.content().orElse(null)?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                }
                requireStreamCompleted(completed)
                usage?.let { emit(GenerationEvent.Usage(it)) }
            }
        }
    }.flowOn(Dispatchers.IO)
}

private fun CompletionUsage.tokenUsage(): TokenUsage? {
    val input = _promptTokens().asKnown().orElse(null) ?: return null
    val outputField = _completionTokens()
    val output = outputField.asKnown().orElse(null)
    if (output == null && !outputField.isMissing() && !outputField.isNull()) return null
    return TokenUsage(input, output).takeIf { it.isValid }
}
