package org.starfall.generative.features.home.shared.logic

import kotlinx.coroutines.*
import org.starfall.generative.integrations.modelapis.attachments.rejectedRemoteAttachment
import org.starfall.generative.integrations.modelapis.attachments.rejectedRemoteAttachments
import org.starfall.generative.integrations.modelapis.attachments.withFileReferenceHeaders
import kotlinx.coroutines.flow.*
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.generation.TokenUsage
import kotlinx.serialization.json.*
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.integrations.modelapis.openaicodex.CODEX_SESSION_SCOPE
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.claudeAllowsSampling
import org.starfall.generative.shared.model.claudeThinkingParameters
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.googleThinkingConfig
import org.starfall.generative.shared.model.language.wireReasoningEffort
import org.starfall.generative.shared.model.provider.streamEnabledFor
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.geminiFunctionParameters
import org.starfall.generative.shared.helpers.networking.modelResponse
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.providerBase
import org.starfall.generative.shared.helpers.networking.providerTurn
import org.starfall.generative.shared.helpers.networking.providerTokenUsage
import org.starfall.generative.shared.helpers.networking.requireArray
import org.starfall.generative.shared.helpers.networking.requireObject
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text
import org.starfall.generative.shared.helpers.networking.toolArguments
import org.starfall.generative.shared.helpers.networking.ToolJsonStore
import org.starfall.generative.shared.helpers.networking.elementList

/** Builds protocol-specific model requests and normalizes their responses for tool calling. */
internal class ToolRequestService(
    private val http: ToolHttp,
    private val aiService: AiService
) {
    fun streamEvents(
        provider: ModelProvider,
        model: String,
        messages: List<StoredMessage>,
        prompt: String
    ): Flow<GenerationEvent> = aiService.streamEvents(provider, model, messages, prompt)

    suspend fun completeText(
        provider: ModelProvider,
        model: String,
        messages: List<StoredMessage>,
        prompt: String,
        maxOutputTokens: Int? = null
    ): String {
        val output = StringBuilder()
        aiService.streamContent(provider, model, messages, prompt, maxOutputTokens).collect { output.append(it) }
        return output.toString()
    }

    suspend fun request(
        p: ModelProvider,
        model: String,
        history: List<JsonObject>,
        tools: List<ToolDefinition>,
        prompt: String,
        sendThinking: Boolean,
        onText: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        onUsage: suspend (TokenUsage) -> Unit = {},
        sessionKey: String? = null,
        onPreparation: suspend (Boolean) -> Unit = {},
        store: ToolJsonStore? = null
    ): JsonObject {
        val snapshot = aiService.resolveProvider(p)
        var retried = false
        while (true) {
            if (history.any { it["_attachments"] != null }) onPreparation(true)
            val (readyHistory, prepared) = aiService.prepareToolHistory(snapshot, model, history)
            onPreparation(false)
            var outputStarted = false
            try {
                return requestPrepared(snapshot, model, readyHistory, tools, prompt, sendThinking,
                    { outputStarted = true; onText(it) }, { outputStarted = true; onReasoning(it) },
                    { outputStarted = true; onUsage(it) }, sessionKey, store)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (outputStarted || retried || !rejectedRemoteAttachment(error, prepared)) throw error
                retried = true
                aiService.invalidateAttachments(rejectedRemoteAttachments(error, prepared))
            }
        }
    }

    private suspend fun requestPrepared(
        p: ModelProvider,
        model: String,
        history: List<JsonObject>,
        tools: List<ToolDefinition>,
        prompt: String,
        sendThinking: Boolean,
        onText: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        onUsage: suspend (TokenUsage) -> Unit = {},
        sessionKey: String? = null,
        store: ToolJsonStore? = null
    ): JsonObject {
        if (p.type == ModelApiType.DEVIN) {
            aiService.accountToolTurn(p, model, history, tools, prompt, sendThinking,
                onText, onReasoning, onUsage, sessionKey, store)?.let { return it }
        }
        val defs = JsonArray(tools.map { obj("type" to str("function"),"function" to obj("name" to str(it.name),"description" to str(it.description),"parameters" to it.schema)) })
        val wireProvider = aiService.prepareAccountProvider(p, model).let { wire ->
            if (history.any { message -> (message["_attachments"] as? JsonArray).orEmpty().any { it.jsonObject["remoteId"] != null } })
                wire.withFileReferenceHeaders() else wire
        }
        val base = providerBase(wireProvider)
        val config = p.config.modelConfigs[model] ?: ModelConfiguration()
        suspend fun modelResponse(url: String, body: JsonObject, provider: ModelProvider,
            stream: Boolean, text: suspend (String) -> Unit, reasoning: suspend (String) -> Unit = {}): JsonObject {
            val scopedBody = if (p.type == ModelApiType.OPENAI_CODEX && !sessionKey.isNullOrBlank())
                JsonObject(body + (CODEX_SESSION_SCOPE to str(sessionKey))) else body
            // Cloud Code's account envelopes place model outside the native Gemini request;
            // the ordinary Gemini tool path omits it, so attach the selected model here.
            val requestBody = if (p.type in setOf(ModelApiType.ANTIGRAVITY, ModelApiType.GEMINI_CLI))
                JsonObject(scopedBody + ("model" to str(model))) else scopedBody
            val requestProvider = aiService.prepareAccountRequest(p, provider, requestBody)
            val upstreamStream = aiService.accountShouldStreamUpstream(p, requestProvider, model, stream)
            // Account adapters may synchronously access encrypted Room credentials when
            // normalizing requests; never evaluate these callbacks on a UI-owned dispatcher.
            val (requestUrl, normalizedBody) = withContext(Dispatchers.IO) {
                val routeKey = when (provider.type) {
                    ModelApiType.OPENAI -> "chat"
                    ModelApiType.OPENAI_RESPONSES -> "responses"
                    ModelApiType.ANTHROPIC -> "messages"
                    ModelApiType.GOOGLE -> "generate"
                    else -> ""
                }
                val effectiveUrl = provider.config.customEndpoints[routeKey]?.takeIf(String::isNotBlank)
                    ?.replace("{model}", model)?.replace("{method}", "generateContent") ?: url
                aiService.accountRequestUrl(p, provider, effectiveUrl) to
                    aiService.normalizeAccountToolRequest(p, provider, requestBody, prompt)
            }
            val response = http.modelResponse(requestUrl, normalizedBody, requestProvider, upstreamStream,
                { chunk -> if (stream) text(chunk) }, { chunk -> if (stream) reasoning(chunk) },
                { aiService.unwrapAccountResponse(p, it) }, store)
            val restored = aiService.restoreAccountToolResponse(p, provider, requestBody, response)
            providerTokenUsage(provider.type, restored)?.takeIf { it.isValid }?.let { onUsage(it) }
            return restored
        }

        return when(wireProvider.type) {
            ModelApiType.OPENAI_RESPONSES -> {
                val sendReasoning = sendThinking
                val isDeepSeek = wireProvider.baseUrl.contains("deepseek.com", ignoreCase = true) || model.startsWith("deepseek-", ignoreCase = true)
                val input = store.elementList()
                history.forEach { message ->
                    when {
                        message["responsesOutput"] is JsonArray -> {
                            message.requireArray("responsesOutput").forEach { item ->
                                if (sendReasoning || item.requireObject().text("type") != "reasoning") input.add(item)
                            }
                        }
                        message.text("role") == "tool" -> input.add(obj(
                            "type" to str("function_call_output"), "call_id" to str(message.text("tool_call_id")),
                            "output" to str(message.text("content"))
                        ))
                        message.text("role") == "assistant" -> {
                            message.text("reasoning_content").takeIf { sendReasoning && it.isNotBlank() }?.let { reasoning ->
                                input.add(obj(
                                    "type" to str("reasoning"),
                                    "content" to JsonArray(listOf(obj("type" to str("reasoning_text"), "text" to str(reasoning))))
                                ))
                            }
                            input.add(obj("role" to str("assistant"), "content" to str(message.text("content"))))
                        }
                        else -> input.add(JsonObject(message.filterKeys {
                            it != "reasoning_content" && it != "reasoning_signature" && it != "_attachments"
                        } + ("content" to toolMessageContent(message, ModelApiType.OPENAI_RESPONSES))))
                    }
                }
                val requestBody = buildJsonObject {
                    put("model", model); put("input", JsonArray(input)); put("store", false)
                    if (!isDeepSeek) put("include", JsonArray(listOf(str("reasoning.encrypted_content"))))
                    put("max_output_tokens", p.config.maxTokens)
                    config.temperature?.let { put("temperature", it) }
                    config.topP?.let { put("top_p", it) }
                    config.wireReasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { effort ->
                        put("reasoning", buildJsonObject { put("effort", effort) })
                    }
                    if (tools.isNotEmpty()) put("tools", JsonArray(tools.map {
                        obj("type" to str("function"), "name" to str(it.name), "description" to str(it.description),
                            "parameters" to it.schema, "strict" to JsonPrimitive(false))
                    }))
                }
                val response = modelResponse(
                    "$base/responses",
                    requestBody,
                    wireProvider,
                    p.streamEnabledFor(model),
                    onText,
                    onReasoning
                )
                providerTurn(wireProvider.type, response, store)
            }
            ModelApiType.OPENAI, ModelApiType.OLLAMA -> {
                val ollama = wireProvider.type == ModelApiType.OLLAMA
                val sendReasoning = sendThinking && !ollama
                val wireHistory: MutableList<JsonObject> = store?.let { ToolMessageHistory(it) } ?: mutableListOf()
                history.forEach { message ->
                    var clean = JsonObject(message.filterKeys { key ->
                        key != "_attachments" && key != "responsesOutput" && key != "reasoning_signature" && (sendReasoning || key != "reasoning_content")
                    } + ("content" to toolMessageContent(message, wireProvider.type)))
                    if (ollama && message["_attachments"] is JsonArray) {
                        val images = message.getValue("_attachments").jsonArray.map {
                            val file = it.jsonObject
                            require(file.text("mimeType").startsWith("image/")) { "Unsupported Ollama attachment" }
                            file.getValue("data")
                        }
                        clean = JsonObject(clean + ("images" to JsonArray(images)))
                    }
                    val wireMessage = when (message.text("role")) {
                        "tool" -> if (ollama) JsonObject(clean + ("tool_name" to str(message.text("name")))) else clean
                        "assistant" -> {
                            val calls = store.elementList()
                            (message["tool_calls"] as? JsonArray).orEmpty().forEach { c ->
                                val call = c.requireObject()
                                val f = call.requireObject("function")
                                val rawArgs = f["arguments"]
                                val args = if (ollama) {
                                    rawArgs as? JsonObject ?: Json.parseToJsonElement(f.text("arguments"))
                                } else {
                                    str(if (rawArgs is JsonPrimitive) rawArgs.content else rawArgs?.toString() ?: "{}")
                                }
                                calls.add(if (ollama) obj("function" to JsonObject(f + ("arguments" to args)))
                                else obj(
                                    "id" to str(call.text("id")),
                                    "type" to str("function"),
                                    "function" to JsonObject(f + ("arguments" to args))
                                ))
                            }
                            // An assistant message without tool calls must not carry an empty `tool_calls` array.
                            if (calls.isEmpty()) JsonObject(clean - "tool_calls")
                            else JsonObject(clean + ("tool_calls" to JsonArray(calls)))
                        }
                        else -> clean
                    }
                    // Providers reject an assistant message with neither text nor tool calls.
                    if (!(wireMessage.text("role") == "assistant" && wireMessage["tool_calls"] == null &&
                        (wireMessage["content"] as? JsonPrimitive)?.content?.isBlank() == true)) {
                        wireHistory.add(wireMessage)
                    }
                }
                val request = buildJsonObject {
                    put("model", model); put("messages", JsonArray(wireHistory)); put("stream", false)
                    if (tools.isNotEmpty()) put("tools", defs)
                    if (ollama) put("options", buildJsonObject {
                        config.temperature?.let { put("temperature", it) }
                        config.topP?.let { put("top_p", it) }
                        config.topK?.let { put("top_k", it) }
                        put("num_predict", p.config.maxTokens)
                    }) else {
                        put("max_tokens", p.config.maxTokens)
                        config.temperature?.let { put("temperature", it) }
                        config.topP?.let { put("top_p", it) }
                        config.wireReasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            put("reasoning_effort", it)
                        }
                    }
                }
                val response = modelResponse(
                    if (ollama) base.removeSuffix("/api") + "/api/chat" else "$base/chat/completions",
                    request, wireProvider, p.streamEnabledFor(model), onText, onReasoning
                )
                providerTurn(wireProvider.type, response, store)
            }
            ModelApiType.ANTHROPIC -> {
                val native = mutableListOf<JsonObject>()
                var currentRole: String? = null
                var blocks = store.elementList()
                fun flush() {
                    currentRole?.let { role -> native.add(obj("role" to str(role), "content" to JsonArray(blocks))) }
                }
                history.forEach { message ->
                    val role = message.text("role")
                    if (role == "system") return@forEach
                    val targetRole = if (role == "assistant") "assistant" else "user"
                    if (targetRole != currentRole) {
                        flush()
                        currentRole = targetRole
                        blocks = store.elementList()
                    }
                    if (role == "tool") {
                        blocks += obj(
                            "type" to str("tool_result"),
                            "tool_use_id" to str(message.text("tool_call_id")),
                            "content" to str(message.text("content"))
                        )
                    } else {
                        if (role == "assistant" && sendThinking) {
                            message.text("reasoning_content").takeIf { it.isNotBlank() }?.let { reasoning ->
                                val signature = message.text("reasoning_signature")
                                blocks += buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", reasoning)
                                    if (signature.isNotBlank()) put("signature", signature)
                                }
                            }
                        }
                        blocks += toolAttachmentParts(message, ModelApiType.ANTHROPIC)
                        if (message.text("content").isNotEmpty()) {
                            blocks += obj("type" to str("text"), "text" to str(message.text("content")))
                        }
                        (message["tool_calls"] as? JsonArray).orEmpty().forEach { c ->
                            val call = c.jsonObject
                            val f = call.requireObject("function")
                            blocks += obj(
                                "type" to str("tool_use"),
                                "id" to str(call.text("id")),
                                "name" to str(f.text("name")),
                                "input" to (f["arguments"] as? JsonObject ?: Json.parseToJsonElement(f.text("arguments")))
                            )
                        }
                    }
                }
                flush()
                val response = modelResponse(base.removeSuffix("/v1") + "/v1/messages", buildJsonObject {
                    put("model", model); put("max_tokens", p.config.maxTokens); put("system", prompt); put("messages", JsonArray(native))
                    config.claudeThinkingParameters(model, p.config.maxTokens).forEach { (key, value) -> put(key, value) }
                    if (tools.isNotEmpty()) put("tools", JsonArray(tools.map {
                        obj("name" to str(it.name), "description" to str(it.description), "input_schema" to it.schema)
                    }))
                    if (config.claudeAllowsSampling(model, p.config.maxTokens)) {
                        config.temperature?.let { put("temperature", it) }
                        config.topP?.let { put("top_p", it) }
                        config.topK?.let { put("top_k", it) }
                    }
                }, wireProvider, p.streamEnabledFor(model), onText, onReasoning)
                providerTurn(wireProvider.type, response, store)
            }
            ModelApiType.GOOGLE -> {
                val native: MutableList<JsonObject> = store?.let { cache ->
                    ToolMessageHistory(cache) { it.text("role") == "user" }
                } ?: mutableListOf()
                history.forEach { message ->
                    val role=message.text("role"); val parts=store.elementList()
                    if(role=="tool") parts += obj("functionResponse" to obj("name" to str(message.text("name")),"response" to obj("result" to str(message.text("content")))))
                    else {
                        parts += toolAttachmentParts(message, ModelApiType.GOOGLE)
                        if(message.text("content").isNotBlank()) parts += obj("text" to str(message.text("content")))
                        (message["tool_calls"] as? JsonArray).orEmpty().forEach { c -> val f=c.requireObject().requireObject("function")
                            parts += (c.jsonObject["googlePart"] ?: obj("functionCall" to obj("name" to str(f.text("name")),"args" to (f["arguments"] as? JsonObject ?: Json.parseToJsonElement(f.text("arguments")))))) }
                    }
                    native.add(obj("role" to str(if(role=="assistant") "model" else "user"),"parts" to JsonArray(parts)))
                }
                val root=if(Regex("/v1(?:beta|alpha)?$").containsMatchIn(base)) base else "$base/v1beta"
                val response=modelResponse("$root/models/$model:generateContent",buildJsonObject {
                    put("contents",JsonArray(native)); put("systemInstruction",obj("parts" to JsonArray(listOf(obj("text" to str(prompt))))))
                    if(tools.isNotEmpty()) put("tools",JsonArray(listOf(obj("functionDeclarations" to JsonArray(tools.map { tool -> buildJsonObject { put("name", str(tool.name)); put("description", str(tool.description)); geminiFunctionParameters(tool.schema)?.let { put("parameters", it) } } })))))
                    put("generationConfig",buildJsonObject { put("maxOutputTokens",p.config.maxTokens); config.temperature?.let { put("temperature",it) }; config.topP?.let { put("topP",it) }; config.topK?.let { put("topK",it) }; config.googleThinkingConfig()?.let { put("thinkingConfig", it) } })
                },wireProvider,p.streamEnabledFor(model),onText,onReasoning)
                providerTurn(wireProvider.type, response, store)
            }
            else -> error("Unsupported provider protocol: ${wireProvider.type.displayName}")
        }
    }
}
