package org.starfall.generative.integrations.modelapis.gemini

import org.starfall.generative.integrations.modelapis.attachments.PreparedAttachment
import kotlinx.coroutines.flow.Flow
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponseUsageMetadata
import com.google.genai.types.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.*
import org.starfall.generative.shared.model.auth.*
import org.starfall.generative.shared.model.generation.*
import org.starfall.generative.shared.model.language.*
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.generation.requireStreamCompleted
import org.starfall.generative.shared.model.generation.OUTPUT_TOKEN_LIMIT_REACHED
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.integrations.modelapis.ModelApiAdapter
import org.starfall.generative.integrations.modelapis.ModelApiRequest
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk

internal class GeminiAdapter(private val sdk: OfficialProviderSdk) : ModelApiAdapter {
    override val modelApiType = ModelApiType.GOOGLE


    override suspend fun testConnection(provider: ModelProvider): Result<String> = sdk.connectionResult(provider) {
        sdk.sdkCall { sdk.google(provider).use { it.models.list(null).iterator().hasNext() } }
    }

    override fun stream(request: ModelApiRequest): Flow<GenerationEvent> = streamGoogle(
        request.provider, request.modelName, request.messages, request.systemPrompt,
        request.temperature, request.topP, request.maxTokens, request.topK, request.sendThinkingContent, request.enabledModelTools, request.preparedAttachments
    )

    private fun streamGoogle(
        provider: ModelProvider,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int,
        topK: Int? = null,
        sendThinkingContent: Boolean = false,
        enabledModelTools: List<String> = emptyList(),
        preparedAttachments: Map<String, PreparedAttachment> = emptyMap()
    ): Flow<GenerationEvent> = flow {
        sdk.google(provider).use { client ->
            val contents = mutableListOf<Content>()
            for (message in messages) {
                contents += Content.builder()
                    .role(if (message.role == ChatRole.MODEL) "model" else "user")
                    .parts(sdk.googleParts(client, message, preparedAttachments))
                    .build()
            }
            val config = GenerateContentConfig.builder().maxOutputTokens(maxTokens)
            val nativeTools = requestToolsForGemini(enabledModelTools)
            if (nativeTools.isNotEmpty()) config.tools(nativeTools)
            provider.config.modelConfigs[modelName]?.let { modelConfig ->
                if (modelConfig.reasoningEffortDisabled) {
                    config.thinkingConfig(com.google.genai.types.ThinkingConfig.builder().thinkingBudget(0).includeThoughts(false).build())
                } else modelConfig.requestReasoningEffort?.let {
                    val thinking = com.google.genai.types.ThinkingConfig.builder().includeThoughts(true)
                    modelConfig.wireReasoningEffort?.let { level -> thinking.thinkingLevel(level) }
                    config.thinkingConfig(thinking.build())
                }
            }
            if (systemPrompt.isNotBlank()) config.systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
            temperature?.let { config.temperature(it.toFloat()) }
            topP?.let { config.topP(it.toFloat()) }
            topK?.let { config.topK(it.toFloat()) }
            if (!provider.config.supportStream) {
                val response = sdk.sdkCall { client.models.generateContent(modelName.removePrefix("models/"), contents, config.build()) }
                response.text()?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                response.usageMetadata().orElse(null)?.tokenUsage()?.let { emit(GenerationEvent.Usage(it)) }
                return@use
            }
            sdk.sdkCall { client.models.generateContentStream(modelName.removePrefix("models/"), contents, config.build()) }.use { response ->
                val iterator = response.iterator()
                var usage: TokenUsage? = null
                var completed = false
                while (sdk.sdkCall { iterator.hasNext() }) {
                    val chunk = iterator.next()
                    chunk.candidates().orElse(emptyList()).forEach { candidate ->
                        candidate.finishReason().orElse(null)?.let { reason ->
                            completed = true
                            if (reason.knownEnum() == com.google.genai.types.FinishReason.Known.MAX_TOKENS)
                                error(OUTPUT_TOKEN_LIMIT_REACHED)
                        }
                    }
                    chunk.usageMetadata().orElse(null)?.tokenUsage()?.let { usage = it }
                    chunk.text()?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                }
                requireStreamCompleted(completed)
                usage?.let { emit(GenerationEvent.Usage(it)) }
            }
        }
    }.flowOn(Dispatchers.IO)
}

private fun GenerateContentResponseUsageMetadata.tokenUsage(): TokenUsage? {
    // promptTokenCount already includes cachedContentTokenCount; thoughts are separate from candidates.
    val prompt = promptTokenCount().orElse(null)?.toLong() ?: return null
    val toolInput = toolUsePromptTokenCount().orElse(0).toLong()
    if (prompt < 0 || toolInput < 0) return null
    val input = prompt + toolInput
    val candidates = candidatesTokenCount().orElse(null)?.toLong()
    val thoughts = thoughtsTokenCount().orElse(null)?.toLong()
    if ((candidates != null && candidates < 0) || (thoughts != null && thoughts < 0)) return null
    val output = if (candidates == null && thoughts == null) null else (candidates ?: 0L) + (thoughts ?: 0L)
    return TokenUsage(input, output).takeIf { it.isValid }
}


private fun requestToolsForGemini(enabled: List<String>): List<com.google.genai.types.Tool> =
    enabled.distinct().mapNotNull { tool ->
        when (tool) {
            "google_search" -> com.google.genai.types.Tool.builder()
                .googleSearch(com.google.genai.types.GoogleSearch.builder().build()).build()
            "google_maps" -> com.google.genai.types.Tool.builder()
                .googleMaps(com.google.genai.types.GoogleMaps.builder().build()).build()
            "url_context" -> com.google.genai.types.Tool.builder()
                .urlContext(com.google.genai.types.UrlContext.builder().build()).build()
            "code_execution" -> com.google.genai.types.Tool.builder()
                .codeExecution(com.google.genai.types.ToolCodeExecution.builder().build()).build()
            else -> null
        }
    }
