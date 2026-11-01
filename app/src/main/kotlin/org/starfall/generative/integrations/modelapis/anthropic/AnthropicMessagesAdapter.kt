package org.starfall.generative.integrations.modelapis.anthropic

import kotlinx.coroutines.flow.Flow
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.core.JsonField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.*
import org.starfall.generative.shared.model.auth.*
import org.starfall.generative.shared.model.generation.*
import org.starfall.generative.shared.model.language.*
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.shared.model.claudeThinkingParameters
import org.starfall.generative.shared.model.claudeAllowsSampling
import org.starfall.generative.integrations.modelapis.sdk.useClient
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.generation.requireStreamCompleted
import org.starfall.generative.shared.model.generation.OUTPUT_TOKEN_LIMIT_REACHED
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.integrations.modelapis.ModelApiAdapter
import org.starfall.generative.integrations.modelapis.ModelApiRequest
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk

internal class AnthropicMessagesAdapter(private val sdk: OfficialProviderSdk) : ModelApiAdapter {
    override val modelApiType = ModelApiType.ANTHROPIC


    override suspend fun testConnection(provider: ModelProvider): Result<String> = sdk.connectionResult(provider) {
        sdk.sdkCall { sdk.anthropic(provider).useClient { it.models().list() } }
    }

    override fun stream(request: ModelApiRequest): Flow<GenerationEvent> = streamAnthropic(
        request.provider, request.modelName, request.messages, request.systemPrompt,
        request.temperature, request.topP, request.maxTokens, request.topK, request.sendThinkingContent,
        request.enabledModelTools, request.callerSystemPrompt
    )

    private fun streamAnthropic(
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
        callerSystemPrompt: String? = null
    ): Flow<GenerationEvent> = flow {
        sdk.anthropic(provider).useClient { client ->
            val params = MessageCreateParams.builder().model(modelName).maxTokens(maxTokens.toLong())
            // Claude Code accounts keep their own wire shape; only plain Anthropic Messages providers cache.
            val cache = provider.config.anthropicPromptCache.takeIf { provider.type == ModelApiType.ANTHROPIC && it.enabled }
            val breakpoint = cache?.let { cacheBreakpoint(it.ttl) }
            val conversationBreakpoint = breakpoint.takeIf { cache?.cacheConversation == true }
            val nativeTools = requestToolsForAnthropic(enabledModelTools, cache?.takeIf { it.cacheTools })
            if (nativeTools.isNotEmpty()) params.putAdditionalBodyProperty("tools", com.anthropic.core.JsonValue.from(nativeTools))
            val modelConfig = provider.config.modelConfigs[modelName]
            modelConfig?.claudeThinkingParameters(modelName, maxTokens)?.forEach { (key, value) ->
                params.putAdditionalBodyProperty(key, com.anthropic.core.JsonValue.from(
                    value.jsonObject.mapValues { (_, field) ->
                        val primitive = field.jsonPrimitive
                        if (primitive.isString) primitive.content else primitive.intOrNull ?: primitive.content
                    }))
            }
            if (systemPrompt.isNotBlank()) {
                val systemBreakpoint = breakpoint.takeIf { cache?.cacheSystem == true }
                if (callerSystemPrompt == null && systemBreakpoint == null) params.system(systemPrompt)
                else params.systemOfTextBlockParams(listOf(textBlock(systemPrompt, systemBreakpoint)))
            }
            val firstUser = messages.indexOfFirst { it.role == ChatRole.USER }
            val useMidSystem = callerSystemPrompt != null && claudeCodeSupportsMidConversationSystem(modelName)
            val reminderUser = if (callerSystemPrompt != null && !useMidSystem) firstUser else -1
            var systemInsertAt = if (useMidSystem && firstUser >= 0) firstUser + 1 else -1
            if (systemInsertAt >= 0) {
                while (systemInsertAt < messages.size && messages[systemInsertAt].role == ChatRole.USER) systemInsertAt++
            }
            messages.forEachIndexed { index, message ->
                // The breakpoint sits on the last message so the next turn reads everything before it.
                val messageBreakpoint = conversationBreakpoint.takeIf { index == messages.lastIndex }
                if (index == systemInsertAt) {
                    params.addSystemMessageOfBlockParams(listOf(
                        com.anthropic.models.messages.ContentBlockParam.ofText(callerSystemPrompt.orEmpty())))
                }
                if (message.role == ChatRole.MODEL && sendThinkingContent && !message.reasoningContent.isNullOrBlank()) {
                    val blocks = mutableListOf<com.anthropic.models.messages.ContentBlockParam>()
                    val thinking = com.anthropic.models.messages.ThinkingBlockParam.builder()
                        .thinking(message.reasoningContent!!)
                        .signature(message.reasoningSignature.orEmpty())
                        .build()
                    blocks += com.anthropic.models.messages.ContentBlockParam.ofThinking(thinking)
                    if (message.content.isNotEmpty()) {
                        blocks += com.anthropic.models.messages.ContentBlockParam.ofText(message.content)
                    }
                    params.addMessage(
                        com.anthropic.models.messages.MessageParam.builder()
                            .role(com.anthropic.models.messages.MessageParam.Role.ASSISTANT)
                            .contentOfBlockParams(blocks.withBreakpoint(messageBreakpoint))
                            .build()
                    )
                } else if (message.role == ChatRole.MODEL) {
                    if (messageBreakpoint != null && message.content.isNotEmpty()) {
                        params.addMessage(
                            com.anthropic.models.messages.MessageParam.builder()
                                .role(com.anthropic.models.messages.MessageParam.Role.ASSISTANT)
                                .contentOfBlockParams(listOf(
                                    com.anthropic.models.messages.ContentBlockParam.ofText(textBlock(message.content, messageBreakpoint))))
                                .build()
                        )
                    } else params.addAssistantMessage(message.content)
                } else if (message.files.isNotEmpty() || index == reminderUser) {
                    val content = sdk.anthropicContent(message).toMutableList()
                    if (index == reminderUser) content.add(0, com.anthropic.models.messages.ContentBlockParam.ofText(
                        claudeCodeSystemReminder(callerSystemPrompt.orEmpty())))
                    params.addMessage(
                        com.anthropic.models.messages.MessageParam.builder()
                            .role(com.anthropic.models.messages.MessageParam.Role.USER)
                            .contentOfBlockParams(content.withBreakpoint(messageBreakpoint))
                            .build()
                    )
                } else if (messageBreakpoint != null && message.content.isNotEmpty()) {
                    params.addMessage(
                        com.anthropic.models.messages.MessageParam.builder()
                            .role(com.anthropic.models.messages.MessageParam.Role.USER)
                            .contentOfBlockParams(listOf(
                                com.anthropic.models.messages.ContentBlockParam.ofText(textBlock(message.content, messageBreakpoint))))
                            .build()
                    )
                } else {
                    params.addUserMessage(message.content)
                }
            }
            if (systemInsertAt == messages.size) {
                params.addSystemMessageOfBlockParams(listOf(
                    com.anthropic.models.messages.ContentBlockParam.ofText(callerSystemPrompt.orEmpty())))
            }
            @Suppress("DEPRECATION")
            run {
                if ((modelConfig ?: ModelConfiguration()).claudeAllowsSampling(modelName, maxTokens)) {
                    temperature?.let { params.temperature(it) }
                    topP?.let { params.topP(it) }
                    topK?.let { params.topK(it.toLong()) }
                }
            }

            val tokenUsage = AnthropicTokenUsage()
            if (!provider.config.supportStream) {
                val response = sdk.sdkCall { client.messages().create(params.build()) }
                response.content().forEach { block ->
                    block.thinking().orElse(null)?.let { thinking ->
                        emit(GenerationEvent.Reasoning(thinking.thinking(), thinking.signature()))
                    }
                    block.text().orElse(null)?.text()?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                }
                response._usage().asKnown().orElse(null)?.let { usage ->
                    tokenUsage.update(usage._inputTokens(), usage._cacheCreationInputTokens(),
                        usage._cacheReadInputTokens(), usage._outputTokens())
                }
                tokenUsage.usage?.let { emit(GenerationEvent.Usage(it)) }
                return@useClient
            }

            sdk.sdkCall { client.messages().createStreaming(params.build()) }.use { response ->
                val iterator = response.stream().iterator()
                var completed = false
                while (sdk.sdkCall { iterator.hasNext() }) {
                    val event = iterator.next()
                    if (event.messageStop().isPresent) completed = true
                    if (event.messageDelta().orElse(null)?.delta()?.stopReason()?.orElse(null) ==
                        com.anthropic.models.messages.StopReason.MAX_TOKENS) error(OUTPUT_TOKEN_LIMIT_REACHED)
                    event.messageStart().orElse(null)?.message()?._usage()?.asKnown()?.orElse(null)?.let { usage ->
                        tokenUsage.update(usage._inputTokens(), usage._cacheCreationInputTokens(),
                            usage._cacheReadInputTokens(), usage._outputTokens())
                    }
                    event.messageDelta().orElse(null)?._usage()?.asKnown()?.orElse(null)?.let { usage ->
                        tokenUsage.update(usage._inputTokens(), usage._cacheCreationInputTokens(),
                            usage._cacheReadInputTokens(), usage._outputTokens())
                    }
                    val delta = event.contentBlockDelta().orElse(null)?.delta() ?: continue
                    delta.thinking().orElse(null)?.thinking()?.takeIf { it.isNotEmpty() }?.let {
                        emit(GenerationEvent.Reasoning(it))
                    }
                    delta.signature().orElse(null)?.signature()?.takeIf { it.isNotEmpty() }?.let {
                        emit(GenerationEvent.Reasoning("", it))
                    }
                    delta.text().orElse(null)?.text()?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                }
                requireStreamCompleted(completed)
                tokenUsage.usage?.let { emit(GenerationEvent.Usage(it)) }
            }
        }
    }.flowOn(Dispatchers.IO)
}

/** Anthropic deltas are cumulative; only cache categories are additive to the base input count. */
private class AnthropicTokenUsage {
    private var input: Long? = null
    private var cacheCreation = 0L
    private var cacheRead = 0L
    private var output: Long? = null
    var usage: TokenUsage? = null
        private set

    fun update(inputField: JsonField<Long>, creationField: JsonField<Long>,
               readField: JsonField<Long>, outputField: JsonField<Long>) {
        val counts = listOf(inputField, creationField, readField, outputField).map { field ->
            if (field.isMissing() || field.isNull()) null
            else field.asKnown().orElse(null)?.takeIf { it >= 0 } ?: return
        }
        val nextInput = counts[0] ?: input
        val nextCreation = counts[1] ?: cacheCreation
        val nextRead = counts[2] ?: cacheRead
        val nextOutput = counts[3] ?: output
        val totalInput = nextInput?.let {
            runCatching { Math.addExact(Math.addExact(it, nextCreation), nextRead) }.getOrNull() ?: return
        }
        val nextUsage = totalInput?.let { TokenUsage(it, nextOutput).takeIf { value -> value.isValid } ?: return }
        input = nextInput
        cacheCreation = nextCreation
        cacheRead = nextRead
        output = nextOutput
        usage = nextUsage
    }
}


private fun requestToolsForAnthropic(enabled: List<String>, cache: AnthropicPromptCache? = null): List<Map<String, Any>> {
    val tools = enabled.distinct().mapNotNull { tool ->
        when (tool) {
            "web_search" -> mapOf("type" to "web_search_20250305", "name" to "web_search")
            "web_fetch" -> mapOf("type" to "web_fetch_20250910", "name" to "web_fetch")
            "code_execution" -> mapOf("type" to "code_execution_20250825", "name" to "code_execution")
            else -> null
        }
    }
    if (cache == null || tools.isEmpty()) return tools
    // A breakpoint on the last tool covers the whole tools block.
    val marker = buildMap<String, String> {
        put("type", "ephemeral")
        if (cache.ttl == AnthropicCacheTtl.ONE_HOUR) put("ttl", cache.ttl.wireValue)
    }
    return tools.dropLast(1) + (tools.last() + ("cache_control" to marker))
}

/** The 5-minute lifetime is the API default, so it is left implicit for compatible gateways. */
private fun cacheBreakpoint(lifetime: AnthropicCacheTtl): CacheControlEphemeral {
    val builder = CacheControlEphemeral.builder()
    if (lifetime == AnthropicCacheTtl.ONE_HOUR) builder.ttl(CacheControlEphemeral.Ttl.TTL_1H)
    return builder.build()
}

private fun textBlock(text: String, breakpoint: CacheControlEphemeral?): TextBlockParam =
    TextBlockParam.builder().text(text).apply { breakpoint?.let { cacheControl(it) } }.build()

/** Marks the last text or image block; other block kinds are left untouched. */
private fun List<ContentBlockParam>.withBreakpoint(breakpoint: CacheControlEphemeral?): List<ContentBlockParam> {
    if (breakpoint == null || isEmpty()) return this
    val block = last()
    val text = block.text().orElse(null)
    val image = block.image().orElse(null)
    val marked = when {
        text != null -> ContentBlockParam.ofText(text.toBuilder().cacheControl(breakpoint).build())
        image != null -> ContentBlockParam.ofImage(image.toBuilder().cacheControl(breakpoint).build())
        else -> block
    }
    return dropLast(1) + marked
}
