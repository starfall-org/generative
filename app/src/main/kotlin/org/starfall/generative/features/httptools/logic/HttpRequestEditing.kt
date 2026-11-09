package org.starfall.generative.features.httptools.logic

import kotlinx.serialization.json.*
import okio.Buffer
import org.starfall.generative.integrations.httptools.buildHttpToolRequest
import org.starfall.generative.shared.model.CustomHttpTool

internal val httpEditorJson = Json { prettyPrint = true }

internal fun unknownHttpRequestParameters(text: String, json: Boolean, parameters: List<String>): Set<String> {
    val references = mutableSetOf<String>()
    val token = Regex("\\{\\{\\s*([^{}]+?)\\s*}}")
    fun scan(text: String) { token.findAll(text).forEach { references += it.groupValues[1] } }
    fun visit(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach(::visit)
            is JsonArray -> value.forEach(::visit)
            is JsonPrimitive -> if (value.isString) scan(value.content)
        }
    }
    if (json) runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let(::visit) else scan(text)
    return references - parameters.toSet()
}

/** Insert at the selection, quoting a token only when outside a JSON string. */
internal fun insertHttpParameter(
    text: String, start: Int, end: Int, parameter: String, json: Boolean
): Pair<String, Int> {
    val from = minOf(start, end).coerceIn(0, text.length)
    val to = maxOf(start, end).coerceIn(from, text.length)
    var inString = false
    var escaped = false
    text.take(from).forEach { char ->
        if (escaped) escaped = false
        else if (char == '\\' && inString) escaped = true
        else if (char == '"') inString = !inString
    }
    val token = "{{$parameter}}"
    val insertion = if (!json) token else {
        val quoted = JsonPrimitive(token).toString()
        if (inString) quoted.drop(1).dropLast(1) else quoted
    }
    return text.replaceRange(from, to, insertion) to (from + insertion.length)
}

/** Prepare and render locally; this never opens a network connection. */
internal fun previewHttpRequest(tool: CustomHttpTool, sample: String): String {
    val request = buildHttpToolRequest(tool, Json.parseToJsonElement(sample).jsonObject)
    return buildString {
        append(request.method).append(' ').append(request.url).append('\n')
        request.headers.forEach { (name, value) -> append(name).append(": ").append(value).append('\n') }
        request.body?.let { body ->
            if (request.header("Content-Type") == null) {
                append("Content-Type: ").append(body.contentType()).append('\n')
            }
            val buffer = Buffer()
            body.writeTo(buffer)
            val text = buffer.readUtf8()
            val json = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            append('\n').append(if (json == null) text else httpEditorJson.encodeToString(JsonElement.serializer(), json))
        }
    }
}
