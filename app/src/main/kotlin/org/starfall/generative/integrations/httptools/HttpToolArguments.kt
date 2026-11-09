package org.starfall.generative.integrations.httptools

import kotlinx.serialization.json.*

internal fun httpToolArguments(schema: JsonObject, arguments: JsonObject): JsonObject {
    val properties = schema["properties"]!!.jsonObject
    val resolved = JsonObject(properties.mapNotNull { (key, definition) ->
        (arguments[key] ?: (definition as? JsonObject)?.get("default"))?.let { key to it }
    }.toMap())
    validateHttpArgument(resolved, schema, "arguments")
    return resolved
}

/** Enforce declared types, required fields and enums before any request leaves the device. */
private fun validateHttpArgument(value: JsonElement, schema: JsonObject, path: String) {
    val types = when (val type = schema["type"]) {
        is JsonPrimitive -> listOf(type.content)
        is JsonArray -> type.map { it.jsonPrimitive.content }
        else -> emptyList()
    }
    fun matches(type: String): Boolean = when (type) {
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.content.toBigDecimalOrNull() != null
        "integer" -> value is JsonPrimitive && !value.isString &&
            value.content.toBigDecimalOrNull()?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value == JsonNull
        else -> true
    }
    require(types.isEmpty() || types.any(::matches)) { "Parameter $path has the wrong type" }
    (schema["enum"] as? JsonArray)?.let { require(value in it) { "Parameter $path is not an allowed value" } }
    if (value is JsonObject) {
        (schema["required"] as? JsonArray).orEmpty().forEach {
            require(it.jsonPrimitive.content in value) { "Missing required parameter: $path.${it.jsonPrimitive.content}" }
        }
        (schema["properties"] as? JsonObject).orEmpty().forEach { (key, child) ->
            value[key]?.let { if (child is JsonObject) validateHttpArgument(it, child, "$path.$key") }
        }
    }
    if (value is JsonArray) (schema["items"] as? JsonObject)?.let { items ->
        value.forEachIndexed { index, item -> validateHttpArgument(item, items, "$path[$index]") }
    }
}
