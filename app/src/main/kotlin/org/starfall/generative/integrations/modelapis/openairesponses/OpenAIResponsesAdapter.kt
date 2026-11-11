package org.starfall.generative.integrations.modelapis.openairesponses

import org.starfall.generative.integrations.modelapis.attachments.PreparedAttachment
import kotlinx.coroutines.flow.Flow
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseIncludable
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseUsage
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
import org.starfall.generative.shared.model.generation.UNEXPECTED_STREAM_END
import org.starfall.generative.shared.model.generation.OUTPUT_TOKEN_LIMIT_REACHED
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.integrations.modelapis.ModelApiAdapter
import org.starfall.generative.integrations.modelapis.ModelApiRequest
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk

internal class OpenAIResponsesAdapter(private val sdk: OfficialProviderSdk) : ModelApiAdapter {
    override val modelApiType = ModelApiType.OPENAI_RESPONSES


    override suspend fun testConnection(provider: ModelProvider): Result<String> = sdk.connectionResult(provider) {
        sdk.sdkCall { sdk.openAi(provider).useClient { it.models().list() } }
    }

    override fun stream(request: ModelApiRequest): Flow<GenerationEvent> = streamResponses(
        request.provider, request.modelName, request.messages, request.systemPrompt,
        request.temperature, request.topP, request.maxTokens, request.reasoningEffort, request.sendThinkingContent, request.enabledModelTools, request.preparedAttachments
    )

    private fun streamResponses(
        provider: ModelProvider,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int,
        reasoningEffort: String? = null,
        sendThinkingContent: Boolean = false,
        enabledModelTools: List<String> = emptyList(),
        preparedAttachments: Map<String, PreparedAttachment> = emptyMap()
    ): Flow<GenerationEvent> = flow {
        sdk.openAi(provider).useClient { client ->
            val inputItems = buildList {
                messages.forEach { message ->
                    if (message.role == ChatRole.MODEL && sendThinkingContent) {
                        message.reasoningContent?.takeIf { it.isNotBlank() }?.let { reasoning ->
                            val content = com.openai.models.responses.ResponseReasoningItem.Content.builder()
                                .text(reasoning)
                                .build()
                            val reasoningItem = com.openai.models.responses.ResponseReasoningItem.builder()
                                .id("rs_${message.id}")
                                .summary(emptyList())
                                .content(listOf(content))
                                .build()
                            add(ResponseInputItem.ofReasoning(reasoningItem))
                        }
                    }
                    val easy = EasyInputMessage.builder()
                        .role(if (message.role == ChatRole.MODEL) EasyInputMessage.Role.ASSISTANT else EasyInputMessage.Role.USER)
                    if (message.role == ChatRole.USER && message.files.isNotEmpty()) {
                        easy.contentOfResponseInputMessageContentList(sdk.openAiResponseContent(message, preparedAttachments))
                    } else {
                        easy.content(message.content)
                    }
                    add(ResponseInputItem.ofEasyInputMessage(easy.build()))
                }
            }
            // Codex uses a private endpoint with Responses-compatible events, but its request
            // contract differs from the public API: keep the SDK transport/parser while preserving
            // Codex's required cache key, reasoning include, and omission of unsupported fields.
            val codexWire = provider.config.headers["Originator"] == "codex-tui"
            val params = ResponseCreateParams.builder().model(modelName).store(false).inputOfResponse(inputItems)
            if (!codexWire) params.maxOutputTokens(maxTokens.toLong())
            val nativeTools = if (codexWire) emptyList() else requestToolsForOpenAIResponses(enabledModelTools)
            if (nativeTools.isNotEmpty()) params.putAdditionalBodyProperty("tools", com.openai.core.JsonValue.from(nativeTools))
            if (systemPrompt.isNotBlank() || codexWire) {
                params.instructions(systemPrompt.takeIf(String::isNotBlank)
                    ?: "You are a coding assistant. Follow the user's request accurately and concisely.")
            }
            if (codexWire) {
                provider.config.headers["Session_id"]?.takeIf(String::isNotBlank)?.let { params.promptCacheKey(it) }
                params.include(listOf(ResponseIncludable.REASONING_ENCRYPTED_CONTENT))
                val reasoning = Reasoning.builder().summary(Reasoning.Summary.AUTO)
                reasoningEffort?.trim()?.takeIf(String::isNotEmpty)?.let {
                    reasoning.effort(ReasoningEffort.of(it))
                }
                params.reasoning(reasoning.build())
            } else {
                temperature?.let { params.temperature(it) }
                topP?.let { params.topP(it) }
                reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { effort ->
                    params.putAdditionalBodyProperty(
                        "reasoning",
                        com.openai.core.JsonValue.from(mapOf("effort" to effort))
                    )
                }
            }
            if (!provider.config.supportStream && !codexWire) {
                val response = sdk.sdkCall { client.responses().create(params.build()) }
                check(!response.error().isPresent) { "OpenAI Responses request failed" }
                if (response.incompleteDetails().orElse(null)?.reason()?.orElse(null) ==
                    com.openai.models.responses.Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS)
                    error(OUTPUT_TOKEN_LIMIT_REACHED)
                response.output().forEach { item ->
                    item.reasoning().orElse(null)?.content()?.orElse(emptyList())?.forEach { part ->
                        part.text().takeIf { it.isNotBlank() }?.let { emit(GenerationEvent.Reasoning(it)) }
                    }
                    item.message().orElse(null)?.content()?.forEach { content ->
                        content.outputText().orElse(null)?.text()?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                    }
                }
                response._usage().asKnown().orElse(null)?.tokenUsage()?.let { emit(GenerationEvent.Usage(it)) }
                return@useClient
            }
            sdk.sdkCall { client.responses().createStreaming(params.build()) }.use { response ->
                val iterator = response.stream().iterator()
                var finished = false
                var usage: TokenUsage? = null
                // Codex requires upstream SSE even for a non-stream caller. Buffer those events
                // until completion so the caller's configured delivery semantics stay unchanged.
                val bufferEvents = codexWire && !provider.config.supportStream
                val bufferedText = StringBuilder()
                val bufferedReasoning = StringBuilder()
                while (sdk.sdkCall { iterator.hasNext() }) {
                    val event = iterator.next()
                    check(!event.error().isPresent && !event.failed().isPresent) { "OpenAI Responses stream failed" }
                    event.reasoningTextDelta().orElse(null)?.delta()?.takeIf { it.isNotEmpty() }?.let {
                        if (bufferEvents) bufferedReasoning.append(it) else emit(GenerationEvent.Reasoning(it))
                    }
                    event.reasoningSummaryTextDelta().orElse(null)?.delta()?.takeIf { it.isNotEmpty() }?.let {
                        if (bufferEvents) bufferedReasoning.append(it) else emit(GenerationEvent.Reasoning(it))
                    }
                    event.outputTextDelta().orElse(null)?.delta()?.takeIf { it.isNotEmpty() }?.let {
                        if (bufferEvents) bufferedText.append(it) else emit(GenerationEvent.Text(it))
                    }
                    event.incomplete().orElse(null)?.let { incomplete ->
                        if (incomplete.response().incompleteDetails().orElse(null)?.reason()?.orElse(null) ==
                            com.openai.models.responses.Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS)
                            error(OUTPUT_TOKEN_LIMIT_REACHED)
                        error(UNEXPECTED_STREAM_END)
                    }
                    event.completed().orElse(null)?.let { completed ->
                        finished = true
                        completed._response().asKnown().orElse(null)
                            ?._usage()?.asKnown()?.orElse(null)?.tokenUsage()?.let { usage = it }
                    }
                }
                requireStreamCompleted(finished)
                if (bufferEvents) {
                    if (bufferedReasoning.isNotEmpty()) emit(GenerationEvent.Reasoning(bufferedReasoning.toString()))
                    if (bufferedText.isNotEmpty()) emit(GenerationEvent.Text(bufferedText.toString()))
                }
                usage?.let { emit(GenerationEvent.Usage(it)) }
            }
        }
    }.flowOn(Dispatchers.IO)
}

private fun ResponseUsage.tokenUsage(): TokenUsage? {
    val input = _inputTokens().asKnown().orElse(null) ?: return null
    val outputField = _outputTokens()
    val output = outputField.asKnown().orElse(null)
    if (output == null && !outputField.isMissing() && !outputField.isNull()) return null
    return TokenUsage(input, output).takeIf { it.isValid }
}


private fun requestToolsForOpenAIResponses(enabled: List<String>): List<Map<String, Any>> =
    enabled.distinct().mapNotNull { tool ->
        when (tool) {
            "web_search" -> mapOf("type" to "web_search")
            "code_interpreter" -> mapOf("type" to "code_interpreter", "container" to mapOf("type" to "auto"))
            "image_generation" -> mapOf("type" to "image_generation")
            else -> null
        }
    }
