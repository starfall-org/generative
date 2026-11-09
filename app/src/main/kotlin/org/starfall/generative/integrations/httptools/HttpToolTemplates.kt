package org.starfall.generative.integrations.httptools

import kotlinx.serialization.json.*

private val parameterToken = Regex("\\{\\{\\s*([^{}]+?)\\s*\\}\\}")

/** Exact tokens keep JSON types; interpolation inside a string produces text. */
internal fun mapHttpValue(value: JsonElement, arguments: JsonObject): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapNotNull { (key, child) ->
        if (missingOptionalToken(child, arguments)) null else key to mapHttpValue(child, arguments)
    }.toMap())
    is JsonArray -> JsonArray(value.filterNot { missingOptionalToken(it, arguments) }.map { mapHttpValue(it, arguments) })
    is JsonPrimitive -> if (value.isString) {
        val token = parameterToken.matchEntire(value.content)
        if (token != null) arguments[token.groupValues[1]] ?: error("Missing parameter: ${token.groupValues[1]}")
        else JsonPrimitive(mapHttpText(value.content, arguments))
    } else value
}

private fun missingOptionalToken(value: JsonElement, arguments: JsonObject): Boolean {
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
    val token = parameterToken.matchEntire(text) ?: return false
    return token.groupValues[1] !in arguments
}

internal fun mapHttpText(template: String, arguments: JsonObject): String = parameterToken.replace(template) { match ->
    val value = arguments[match.groupValues[1]] ?: error("Missing parameter: ${match.groupValues[1]}")
    if (value is JsonPrimitive) value.content else value.toString()
}

internal fun responsePointer(root: JsonElement, pointer: String): JsonElement {
    if (pointer.isEmpty()) return root
    require(pointer.startsWith('/')) { "Invalid JSON Pointer" }
    return pointer.drop(1).split('/').fold(root) { value, escaped ->
        val key = escaped.replace("~1", "/").replace("~0", "~")
        when (value) {
            is JsonObject -> value[key]
            is JsonArray -> key.toIntOrNull()?.let { value.getOrNull(it) }
            else -> null
        } ?: error("Response path not found: $pointer")
    }
}

internal fun structureHttpResponse(root: JsonElement, shape: JsonElement): JsonElement = when (shape) {
    is JsonObject -> JsonObject(shape.mapValues { structureHttpResponse(root, it.value) })
    is JsonArray -> JsonArray(shape.map { structureHttpResponse(root, it) })
    is JsonPrimitive -> responsePointer(root, shape.content)
}
