package org.starfall.generative.shared.helpers.networking

import kotlinx.serialization.json.*
import java.util.UUID
import org.starfall.generative.shared.model.api.ModelApiType

internal fun JsonObject.requireObject(key: String): JsonObject =
    get(key) as? JsonObject ?: error("Provider response: missing or invalid $key object")
internal fun JsonObject.requireArray(key: String): JsonArray =
    get(key) as? JsonArray ?: error("Provider response: missing or invalid $key array")
internal fun JsonElement.requireObject(): JsonObject = this as? JsonObject ?: error("Provider response: expected an object")
internal fun toolArguments(value: JsonElement?): JsonObject = when (value) {
    is JsonObject -> value
    is JsonPrimitive -> {
        require(value.isString && value.content.length <= 65536) { "Tool arguments must be a JSON object or JSON object string (max 64 KB)" }
        runCatching { Json.parseToJsonElement(value.content) as? JsonObject }.getOrNull()
            ?: error("Tool arguments contain invalid JSON; expected an object")
    }
    else -> error("Tool arguments are missing or invalid")
}
internal fun validateToolTurn(turn: JsonObject): JsonObject {
    val seen = mutableSetOf<String>()
    val calls = turn.requireArray("tool_calls").map { value ->
        val call = value.requireObject()
        val function = call.requireObject("function")
        require(function.text("name").isNotBlank()) { "Tool call is missing a function name" }
        val id = call.text("id")
        require(id.isNotBlank() && seen.add(id)) { "Tool call ID is missing or duplicated" }
        JsonObject(call + ("function" to JsonObject(function + ("arguments" to toolArguments(function["arguments"])))))
    }
    return JsonObject(turn + ("tool_calls" to JsonArray(calls)))
}
internal fun providerTurn(type: ModelApiType, response: JsonObject): JsonObject {
    check(response["error"] == null || response["error"] == JsonNull) {
        "${type.displayName} returned an error: " + safeError(response["error"])
    }

    fun turn(
        text: String,
        calls: List<JsonElement>,
        reasoning: String? = null,
        reasoningSignature: String? = null
    ): JsonObject {
        var value = obj(
            "role" to str("assistant"),
            "content" to str(text),
            "tool_calls" to JsonArray(calls)
        )
        if (!reasoning.isNullOrBlank()) {
            value = JsonObject(value + ("reasoning_content" to str(reasoning)))
        }
        if (!reasoningSignature.isNullOrBlank()) {
            value = JsonObject(value + ("reasoning_signature" to str(reasoningSignature)))
        }
        return validateToolTurn(value)
    }

    fun call(id: String, name: String, args: JsonElement?, part: JsonElement? = null) = buildJsonObject {
        put("id", id)
        put("type", "function")
        put("function", obj("name" to str(name), "arguments" to toolArguments(args)))
        part?.let { put("googlePart", it) }
    }

    return when (type) {
        ModelApiType.OPENAI_RESPONSES -> {
            check(response.text("status") != "failed") { "OpenAI Responses request failed" }
            val output = response.requireArray("output")
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val calls = mutableListOf<JsonElement>()
            output.forEach { item ->
                val value = item.requireObject()
                when (value.text("type")) {
                    "message" -> value.requireArray("content").forEach { part ->
                        val content = part.requireObject()
                        if (content.text("type") == "output_text") text.append(content.text("text"))
                    }
                    "reasoning" -> (
                        (value["content"] as? JsonArray).orEmpty() +
                            (value["summary"] as? JsonArray).orEmpty()
                    ).forEach { part ->
                        val content = part.requireObject()
                        if (content.text("type") in setOf("reasoning_text", "summary_text")) {
                            reasoning.append(content.text("text"))
                        }
                    }
                    "function_call" -> calls += call(value.text("call_id"), value.text("name"), value["arguments"])
                }
            }
            JsonObject(turn(text.toString(), calls, reasoning.toString()) + ("responsesOutput" to output))
        }

        ModelApiType.OPENAI, ModelApiType.OLLAMA -> {
            val message = if (type == ModelApiType.OLLAMA) {
                response.requireObject("message")
            } else {
                val choice = response.requireArray("choices").firstOrNull()
                    ?: error("OpenAI-compatible provider returned no choices")
                choice.requireObject().requireObject("message")
            }
            val values = message["tool_calls"]
            require(values == null || values == JsonNull || values is JsonArray) {
                "Provider returned invalid tool_calls"
            }
            turn(
                message.text("content"),
                (values as? JsonArray).orEmpty().map { item ->
                    val c = item.requireObject()
                    val f = c.requireObject("function")
                    call(
                        c.text("id").ifBlank {
                            if (type == ModelApiType.OLLAMA) UUID.randomUUID().toString()
                            else error("Tool call is missing an ID")
                        },
                        f.text("name"),
                        f["arguments"]
                    )
                },
                message.text("reasoning_content").takeIf { it.isNotBlank() }
            )
        }

        ModelApiType.ANTHROPIC -> {
            val blocks = response.requireArray("content").map { it.requireObject() }
            val thinkingBlocks = blocks.filter { it.text("type") == "thinking" }
            turn(
                blocks.filter { it.text("type") == "text" }.joinToString("") { it.text("text") },
                blocks.filter { it.text("type") == "tool_use" }
                    .map { call(it.text("id"), it.text("name"), it["input"]) },
                thinkingBlocks.joinToString("") { it.text("thinking") }.takeIf { it.isNotBlank() },
                thinkingBlocks.joinToString("") { it.text("signature") }.takeIf { it.isNotBlank() }
            )
        }

        ModelApiType.GOOGLE -> {
            val candidate = response.requireArray("candidates").firstOrNull()
                ?: error("Google response has no candidates")
            val parts = candidate.requireObject().requireObject("content")
                .requireArray("parts").map { it.requireObject() }
            turn(
                parts.filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
                    .joinToString("") { it.text("text") },
                parts.filter { it.containsKey("functionCall") }.map {
                    val f = it.requireObject("functionCall")
                    call(UUID.randomUUID().toString(), f.text("name"), f["args"] ?: obj(), it)
                },
                parts.filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull == true }
                    .joinToString("") { it.text("text") }
                    .takeIf { it.isNotBlank() }
            )
        }
        else -> error("Unsupported provider response protocol: ${type.displayName}")
    }
}
private val ERROR_TEXT_KEYS = listOf("message", "detail", "error_description", "msg", "reason", "error", "errors", "status", "code", "type")

private fun errorText(value: JsonElement?, depth: Int = 0): String {
    if (depth > 4) return ""
    return when (value) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.asSequence().map { errorText(it, depth + 1) }.firstOrNull { it.isNotBlank() }.orEmpty()
        is JsonObject -> ERROR_TEXT_KEYS.asSequence().map { errorText(value[it], depth + 1) }.firstOrNull { it.isNotBlank() }
            ?: value.takeIf { it.isNotEmpty() }?.toString().orEmpty()
        else -> ""
    }
}

/**
 * Only display bounded error details; redact common secret formats and echoed request credentials.
 * [raw] is the unparsed response body, used when the body is not JSON (HTML/plain-text from gateways and proxies).
 */
internal fun safeError(value: JsonElement?, secrets: Collection<String> = emptyList(), raw: String? = null): String {
    var text = errorText(value).ifBlank {
        raw.orEmpty().replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }.take(2000)
    secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { text = text.replace(it, "[redacted]") }
    text = text.replace(Regex("(?i)(bearer\\s+|sk-)[A-Za-z0-9._~+/-]+"), "[redacted]")
        .replace(Regex("(?i)(api[_-]?key|token|authorization|password)([\\s\"':=]+)[^\\s,}]+"), "$1=[redacted]")
    return text.filter { it >= ' ' }.take(500).ifBlank { "No error details in response" }
}

private val GEMINI_SCHEMA_KEYS = setOf(
    "type", "format", "title", "description", "nullable", "enum", "maxItems", "minItems", "minProperties",
    "maxProperties", "minLength", "maxLength", "pattern", "example", "propertyOrdering", "default",
    "minimum", "maximum"
)

/**
 * Gemini accepts only an OpenAPI-style subset of JSON Schema for function parameters and answers
 * unknown fields (additionalProperties, $schema, $ref, const, ...) with HTTP 400. Returns the
 * schema reduced to that subset, or null when there is nothing to declare (an object without
 * properties is rejected too).
 */
internal fun geminiFunctionParameters(schema: JsonElement): JsonObject? =
    (geminiSchema(schema) as? JsonObject)?.takeIf { (it["properties"] as? JsonObject)?.isNotEmpty() == true }

private fun geminiSchema(element: JsonElement): JsonElement? {
    val schema = element as? JsonObject ?: return null
    val out = linkedMapOf<String, JsonElement>()
    var nullable = false
    schema.forEach { (key, value) ->
        when {
            key == "type" && value is JsonArray -> {
                val types = value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                nullable = "null" in types
                types.firstOrNull { it != "null" }?.let { out["type"] = JsonPrimitive(it) }
            }
            key == "type" && (value as? JsonPrimitive)?.contentOrNull == "null" -> nullable = true
            key == "const" -> out["enum"] = JsonArray(listOf(value))
            key == "properties" && value is JsonObject ->
                out["properties"] = JsonObject(value.mapNotNull { (name, child) -> geminiSchema(child)?.let { name to it } }.toMap())
            key == "items" -> geminiSchema(value)?.let { out["items"] = it }
            key == "anyOf" || key == "oneOf" -> (value as? JsonArray)
                ?.mapNotNull { geminiSchema(it) }?.filter { (it as JsonObject).isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }?.let { out["anyOf"] = JsonArray(it) }
            key in GEMINI_SCHEMA_KEYS -> out[key] = value
        }
    }
    if (nullable) out["nullable"] = JsonPrimitive(true)
    val properties = out["properties"] as? JsonObject
    (schema["required"] as? JsonArray)
        ?.filter { required -> (required as? JsonPrimitive)?.contentOrNull in (properties?.keys ?: emptySet()) }
        ?.takeIf { it.isNotEmpty() }?.let { out["required"] = JsonArray(it) }
    return JsonObject(out)
}
