package org.starfall.generative.shared.helpers.networking

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
    unwrapResponse: (JsonObject) -> JsonObject = { it },
    store: ToolJsonStore? = null
): JsonObject {
    if (!stream) return unwrapResponse(if (store == null) post(url, body, provider) else
        storedModelResponse(url, body, provider, store))
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
        val requestBody = if (store == null) payload.toString().toRequestBody("application/json".toMediaType())
            else StreamingJsonBody(payload)
        withResponse(request(target, provider).post(requestBody).build()) { response ->
            requireSuccess(response)
            var received = false
            var completed = false
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val calls = store.indexedElements()
            val reasoningSignature = StringBuilder()
            val arguments = ToolArgumentChunks(store)
            val googleParts = store.elementList()
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
                            val responseValue = value.requireObject("response")
                            val output = responseValue["output"] as? JsonArray
                            finalResponse = if (store != null && output != null) {
                                val items = store.elementList()
                                output.forEach(items::add)
                                JsonObject(responseValue + ("output" to JsonArray(items)))
                            } else responseValue
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
                                val chunk = delta.text("partial_json")
                                check(arguments.length(index).toLong() + chunk.length <= 65536) { "Tool arguments too large" }
                                arguments.append(index, chunk)
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
                                val previous = calls[index] as? JsonObject
                                val old = previous ?: obj()
                                val oldF = old["function"] as? JsonObject ?: obj()
                                val f = call["function"] as? JsonObject ?: obj()
                                val arg = f["arguments"]
                                if (arg != null) {
                                    val chunk = if (arg is JsonPrimitive) arg.content else arg.toString()
                                    check(arguments.length(index).toLong() + chunk.length <= 65536) { "Tool arguments too large" }
                                    arguments.append(index, chunk)
                                }
                                val id = call.text("id").ifBlank { old.text("id") }
                                val name = oldF.text("name") + f.text("name")
                                if (previous == null || id != old.text("id") || name != oldF.text("name")) {
                                    calls[index] = obj("id" to str(id), "type" to str("function"),
                                        "function" to obj("name" to str(name)))
                                }
                            }
                        }
                    }
                }
            }

            val reader = response.body.charStream().buffered()
            if (store != null) {
                if (readStoredEvents(reader, store, ollama, ::consume)) completed = true
            } else {
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
            }
            // Some Gemini-compatible account endpoints return one complete candidate without
            // a finishReason or SSE sentinel. They are not covered by the strict EOF contract.
            if (!google) requireStreamCompleted(completed)
            check(received) { "Provider stream ended without a response" }

            val result = when {
                responses -> finalResponse ?: error("OpenAI Responses stream ended without a final response")
                google -> {
                    val parts = store.elementList()
                    if (reasoning.isNotEmpty()) parts.add(obj("text" to str(reasoning.toString()), "thought" to JsonPrimitive(true)))
                    parts.add(obj("text" to str(text.toString())))
                    googleParts.forEach(parts::add)
                    obj("candidates" to JsonArray(listOf(obj("content" to obj("parts" to JsonArray(parts))))))
                }
                anthropic -> {
                    val blocks = store.elementList()
                    if (reasoning.isNotEmpty()) blocks.add(obj(
                        "type" to str("thinking"),
                        "thinking" to str(reasoning.toString()),
                        "signature" to str(reasoningSignature.toString())
                    ))
                    blocks.add(obj("type" to str("text"), "text" to str(text.toString())))
                    calls.forEach { (i, value) ->
                        val c = value.jsonObject
                        val args = arguments.read(i)
                        blocks.add(JsonObject(c + ("input" to (args?.takeIf { it.isNotEmpty() }?.let(Json::parseToJsonElement)
                            ?: c["input"] ?: obj()))))
                    }
                    obj("content" to JsonArray(blocks))
                }
                else -> {
                    val toolCalls = store.elementList()
                    calls.forEach { (i, value) ->
                        val c = value.jsonObject
                        toolCalls.add(JsonObject(c + ("function" to JsonObject(c.requireObject("function") +
                            ("arguments" to str(arguments.read(i) ?: "{}"))))))
                    }
                    val baseMessage = obj(
                        "role" to str("assistant"),
                        "content" to str(text.toString()),
                        "tool_calls" to JsonArray(toolCalls)
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

/** Returns true only for the SSE completion sentinel. */
private suspend fun readStoredEvents(
    reader: java.io.Reader,
    store: ToolJsonStore,
    ollama: Boolean,
    consume: suspend (JsonObject) -> Unit
): Boolean = store.textBuffer().use { line ->
    store.textBuffer().use { event ->
        while (true) {
            currentCoroutineContext().ensureActive()
            line.clear()
            var eof = false
            while (true) {
                val c = reader.read()
                if (c < 0) { eof = true; break }
                if (c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
                if (line.length % 32768L == 0L) currentCoroutineContext().ensureActive()
            }
            if (ollama && line.length > 0) {
                line.reader().use { consume(readStoredJson(it, store)) }
            } else if (!ollama) {
                if (line.length >= 5) line.reader().use { input ->
                    val prefix = CharArray(5)
                    var read = 0
                    while (read < prefix.size) {
                        val count = input.read(prefix, read, prefix.size - read)
                        if (count < 0) break
                        read += count
                    }
                    if (read == 5 && String(prefix) == "data:") {
                        var leading = true
                        while (true) {
                            val c = input.read()
                            if (c < 0) break
                            if (leading && c.toChar().isWhitespace()) continue
                            leading = false
                            event.append(c.toChar())
                            if (event.length % 32768L == 0L) currentCoroutineContext().ensureActive()
                        }
                        event.append('\n')
                    }
                }
                if ((line.length == 0L || eof) && event.length > 0) {
                    val done = event.length <= 32 && event.reader().use { it.readText().trim() == "[DONE]" }
                    if (done) return@use true
                    event.reader().use { consume(readStoredJson(it, store)) }
                    event.clear()
                }
            }
            if (eof) return@use false
        }
        @Suppress("UNREACHABLE_CODE") false
    }
}

private suspend fun ToolHttp.storedModelResponse(
    url: String, body: JsonObject, provider: ModelProvider, store: ToolJsonStore
): JsonObject = withContext(Dispatchers.IO) {
    val request = request(url, provider).post(StreamingJsonBody(body)).build()
    withResponse(request) { response ->
        requireSuccess(response)
        val files = files
        val parsed = if (files == null) {
            response.body.charStream().use { readStoredJson(it, store) }
        } else {
            store.textBuffer().use { sanitized ->
                val output = object : Appendable {
                    override fun append(csq: CharSequence?): Appendable = append(csq, 0, csq?.length ?: 4)
                    override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
                        val text = csq ?: "null"
                        for (index in start until end) sanitized.append(text[index])
                        return this
                    }
                    override fun append(c: Char): Appendable { sanitized.append(c); return this }
                }
                response.body.charStream().use { files.sanitize(it, output) }
                sanitized.reader().use { readStoredJson(it, store) }
            }
        }
        check(parsed["error"] == null || parsed["error"] == JsonNull) {
            "Provider error: " + safeError(parsed["error"], requestSecrets(request))
        }
        parsed
    }
}
