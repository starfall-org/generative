package org.starfall.generative.shared.networking

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.generation.requireStreamCompleted
import org.starfall.generative.shared.model.generation.UNEXPECTED_STREAM_END
import org.starfall.generative.shared.model.generation.OUTPUT_TOKEN_LIMIT_REACHED

/** Accumulate bounded tool arguments while delivering answer/reasoning streams incrementally. */
internal suspend fun ToolHttp.modelResponse(
    url: String,
    body: JsonObject,
    provider: ModelProvider,
    stream: Boolean,
    onText: suspend (String) -> Unit,
    onReasoning: suspend (String) -> Unit = {},
    unwrapResponse: (JsonObject) -> JsonObject = { it }
): JsonObject {
    if (!stream) return unwrapResponse(post(url, body, provider))
    val google = provider.type == ModelApiType.GOOGLE
    val ollama = provider.type == ModelApiType.OLLAMA
    val anthropic = provider.type == ModelApiType.ANTHROPIC
    val responses = provider.type == ModelApiType.OPENAI_RESPONSES
    val target = if (google) url.replace(":generateContent", ":streamGenerateContent") + "?alt=sse" else url
    val payload = if (google) body else buildJsonObject {
        body.forEach { (key, value) -> put(key, value) }
        put("stream", true)
        if (provider.type == ModelApiType.OPENAI) {
            put("stream_options", JsonObject((body["stream_options"] as? JsonObject).orEmpty() +
                ("include_usage" to JsonPrimitive(true))))
        }
    }

    return withContext(Dispatchers.IO) {
        withResponse(request(target, provider).post(payload.toString().toRequestBody("application/json".toMediaType())).build()) { response ->
            requireSuccess(response)
            var received = false
            var completed = false
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val calls = linkedMapOf<Int, JsonObject>()
            val reasoningSignature = StringBuilder()
            val arguments = mutableMapOf<Int, StringBuilder>()
            val googleParts = mutableListOf<JsonElement>()
            val usageFields = mutableMapOf<String, JsonElement>()
            var validUsageFields: Map<String, JsonElement>? = null
            var finalResponse: JsonObject? = null

            suspend fun consume(raw: JsonObject) {
                val value = unwrapResponse(raw)
                received = true
                if (value["error"] != null || value.text("type") == "error") {
                    error("Provider stream error: " + safeError(value["error"] ?: value, requestSecrets(response.request)))
                }
                // Usage chunks are cumulative snapshots. Only Anthropic splits one snapshot
                // between message_start (input/cache counts) and message_delta (output count).
                when {
                    anthropic -> {
                        val usage = (if (value.text("type") == "message_start")
                            (value["message"] as? JsonObject)?.get("usage") else value["usage"]) as? JsonObject
                        if (usage != null) usageFields["usage"] = JsonObject(
                            (usageFields["usage"] as? JsonObject).orEmpty() + usage.filterValues { it != JsonNull }
                        )
                    }
                    google -> (value["usageMetadata"] as? JsonObject)?.let { usageFields["usageMetadata"] = it }
                    ollama -> listOf("prompt_eval_count", "eval_count").forEach { key ->
                        value[key]?.takeIf { it != JsonNull }?.let { usageFields[key] = it }
                    }
                    else -> {
                        val nestedUsage = if (responses) (value["response"] as? JsonObject)?.get("usage") else null
                        val usage = nestedUsage as? JsonObject ?: value["usage"] as? JsonObject
                        if (usage != null) usageFields["usage"] = usage
                    }
                }
                if (providerTokenUsage(provider.type, JsonObject(usageFields)) != null) {
                    validUsageFields = usageFields.toMap()
                }
                suspend fun emitText(chunk: String) {
                    if (chunk.isNotEmpty()) {
                        text.append(chunk)
                        check(text.length <= 512000) { "Answer exceeds size limit" }
                        onText(chunk)
                    }
                }
                suspend fun emitReasoning(chunk: String) {
                    if (chunk.isNotEmpty()) {
                        reasoning.append(chunk)
                        check(reasoning.length <= 512000) { "Reasoning exceeds size limit" }
                        onReasoning(chunk)
                    }
                }

                when {
                    responses -> when (value.text("type")) {
                        "response.output_text.delta" -> emitText(value.text("delta"))
                        "response.reasoning_text.delta", "response.reasoning_summary_text.delta" ->
                            emitReasoning(value.text("delta"))
                        "response.completed" -> {
                            completed = true
                            finalResponse = value.requireObject("response")
                        }
                        "response.incomplete" -> {
                            if ((value.requireObject("response")["incomplete_details"] as? JsonObject)
                                    ?.text("reason") == "max_output_tokens")
                                error(OUTPUT_TOKEN_LIMIT_REACHED)
                            error(UNEXPECTED_STREAM_END)
                        }
                        "response.failed" -> error("OpenAI Responses stream failed: " + safeError(value.requireObject("response")["error"], requestSecrets(response.request)))
                    }
                    google -> {
                        if ((value["candidates"] as? JsonArray).orEmpty().any {
                            (it as? JsonObject)?.text("finishReason") == "MAX_TOKENS"
                        }) error(OUTPUT_TOKEN_LIMIT_REACHED)
                        if ((value["candidates"] != null && value["candidates"] != JsonNull) ||
                            value["usageMetadata"] !is JsonObject) value.requireArray("candidates")
                        (value["candidates"] as? JsonArray).orEmpty().firstOrNull()?.jsonObject
                            ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty().forEach { part ->
                                val p = part.jsonObject
                                if ((p["thought"] as? JsonPrimitive)?.booleanOrNull == true) emitReasoning(p.text("text"))
                                else emitText(p.text("text"))
                                if (p["functionCall"] != null) googleParts += part
                            }
                    }
                    anthropic -> {
                        if (value.text("type") == "message_delta" &&
                            (value["delta"] as? JsonObject)?.text("stop_reason") == "max_tokens")
                            error(OUTPUT_TOKEN_LIMIT_REACHED)
                        if (value.text("type") == "message_stop") completed = true
                        val index = (value["index"] as? JsonPrimitive)?.intOrNull ?: 0
                        if (value.text("type") == "content_block_start") {
                            val block = value.requireObject("content_block")
                            if (block.text("type") == "tool_use") calls[index] = block
                        }
                        if (value.text("type") == "content_block_delta") {
                            val delta = value.requireObject("delta")
                            when (delta.text("type")) {
                                "thinking_delta" -> emitReasoning(delta.text("thinking"))
                                "signature_delta" -> reasoningSignature.append(delta.text("signature"))
                                else -> emitText(delta.text("text"))
                            }
                            if (delta.text("type") == "input_json_delta") {
                                val args = arguments.getOrPut(index) { StringBuilder() }
                                args.append(delta.text("partial_json"))
                                check(args.length <= 65536) { "Tool arguments too large" }
                            }
                        }
                    }
                    else -> {
                        if (ollama) {
                            if (value.text("done_reason") == "length") error(OUTPUT_TOKEN_LIMIT_REACHED)
                            if ((value["done"] as? JsonPrimitive)?.booleanOrNull == true) completed = true
                        } else {
                            val reasons = (value["choices"] as? JsonArray).orEmpty().mapNotNull { choice ->
                                (choice as? JsonObject)?.text("finish_reason")?.takeUnless(String::isBlank)
                            }
                            if ("length" in reasons) error(OUTPUT_TOKEN_LIMIT_REACHED)
                            if (reasons.isNotEmpty()) completed = true
                        }
                        if (!ollama) {
                            if ((value["choices"] != null && value["choices"] != JsonNull) ||
                                value["usage"] !is JsonObject) value.requireArray("choices")
                        } else if (value["message"] !is JsonObject && (value["done"] as? JsonPrimitive)?.booleanOrNull != true) {
                            error("Ollama response has no message")
                        }
                        val delta = if (ollama) value["message"] as? JsonObject
                        else (value["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject
                        if (delta != null) {
                            emitReasoning(delta.text("reasoning_content"))
                            emitText(delta.text("content"))
                            (delta["tool_calls"] as? JsonArray).orEmpty().forEachIndexed { pos, item ->
                                val call = item.jsonObject
                                val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: pos
                                val old = calls[index] ?: obj()
                                val oldF = old["function"] as? JsonObject ?: obj()
                                val f = call["function"] as? JsonObject ?: obj()
                                val arg = f["arguments"]
                                if (arg != null) {
                                    val buf = arguments.getOrPut(index) { StringBuilder() }
                                    buf.append(if (arg is JsonPrimitive) arg.content else arg.toString())
                                    check(buf.length <= 65536) { "Tool arguments too large" }
                                }
                                calls[index] = obj(
                                    "id" to str(call.text("id").ifBlank { old.text("id") }),
                                    "type" to str("function"),
                                    "function" to obj("name" to str(oldF.text("name") + f.text("name")))
                                )
                            }
                        }
                    }
                }
                check(calls.size <= 16 && googleParts.size <= 16) { "Too many tool calls" }
            }

            val reader = response.body.charStream().buffered()
            val event = StringBuilder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = StringBuilder()
                var eof = false
                while (true) {
                    val c = reader.read()
                    if (c < 0) { eof = true; break }
                    if (c == 10) break
                    if (c != 13) line.append(c.toChar())
                    check(line.length <= 1024 * 1024) { "Provider event too large" }
                }
                if (ollama && line.isNotEmpty()) consume(Json.parseToJsonElement(line.toString()).jsonObject)
                else if (!ollama) {
                    if (line.startsWith("data:")) event.append(line.substring(5).trimStart()).append('\n')
                    check(event.length <= 1024 * 1024) { "Provider event too large" }
                    if ((line.isEmpty() || eof) && event.isNotEmpty()) {
                        val data = event.toString().trim()
                        event.setLength(0)
                        if (data == "[DONE]") {
                            completed = true
                            break
                        }
                        consume(Json.parseToJsonElement(data).jsonObject)
                    }
                }
                if (eof) break
            }
            // Some Gemini-compatible account endpoints return one complete candidate without
            // a finishReason or SSE sentinel. They are not covered by the strict EOF contract.
            if (!google) requireStreamCompleted(completed)
            check(received) { "Provider stream ended without a response" }

            val result = when {
                responses -> finalResponse ?: error("OpenAI Responses stream ended without a final response")
                google -> obj("candidates" to JsonArray(listOf(obj("content" to obj("parts" to JsonArray(
                    (if (reasoning.isNotEmpty()) listOf(obj("text" to str(reasoning.toString()), "thought" to JsonPrimitive(true))) else emptyList()) +
                        listOf(obj("text" to str(text.toString()))) + googleParts
                ))))))
                anthropic -> obj("content" to JsonArray(
                    (if (reasoning.isNotEmpty()) listOf(obj(
                        "type" to str("thinking"),
                        "thinking" to str(reasoning.toString()),
                        "signature" to str(reasoningSignature.toString())
                    )) else emptyList()) +
                        listOf(obj("type" to str("text"), "text" to str(text.toString()))) +
                        calls.map { (i, c) ->
                            JsonObject(c + ("input" to (arguments[i]?.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it.toString()) }
                                ?: c["input"] ?: obj())))
                        }
                ))
                else -> {
                    val baseMessage = obj(
                        "role" to str("assistant"),
                        "content" to str(text.toString()),
                        "tool_calls" to JsonArray(calls.map { (i, c) ->
                            JsonObject(c + ("function" to JsonObject(c.requireObject("function") +
                                ("arguments" to str(arguments[i]?.toString() ?: "{}")))))
                        })
                    )
                    val message = if (reasoning.isNotEmpty()) {
                        JsonObject(baseMessage + ("reasoning_content" to str(reasoning.toString())))
                    } else baseMessage
                    if (ollama) obj("message" to message)
                    else obj("choices" to JsonArray(listOf(obj("message" to message))))
                }
            }
            JsonObject(result + (validUsageFields ?: usageFields))
        }
    }
}
