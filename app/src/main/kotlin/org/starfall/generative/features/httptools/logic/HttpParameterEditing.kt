package org.starfall.generative.features.httptools.logic

import kotlinx.serialization.json.*

internal fun updateHttpParameter(
    schema: JsonObject, originalName: String?, name: String, definition: JsonObject, required: Boolean
): JsonObject {
    require(name.isNotBlank()) { "Parameter name is required" }
    val properties = (schema["properties"] as? JsonObject).orEmpty()
    require(originalName == name || name !in properties) { "Parameter name already exists" }
    val updated = linkedMapOf<String, JsonElement>()
    properties.forEach { (key, value) -> updated[if (key == originalName) name else key] = if (key == originalName) definition else value }
    if (originalName == null || originalName !in properties) updated[name] = definition
    val requiredNames = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        .filterNot { it == originalName || it == name }.toMutableList()
    if (required) requiredNames += name
    return JsonObject(schema + mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(updated),
        "required" to JsonArray(requiredNames.map(::JsonPrimitive))))
}

internal fun removeHttpParameter(schema: JsonObject, name: String): JsonObject = JsonObject(schema + mapOf(
    "properties" to JsonObject((schema["properties"] as? JsonObject).orEmpty() - name),
    "required" to JsonArray((schema["required"] as? JsonArray).orEmpty().filterNot { (it as? JsonPrimitive)?.content == name })
))

internal fun httpParameterDefinition(
    type: String, description: String, useDefault: Boolean, defaultValue: String, enumValues: String, constraints: String
): JsonObject {
    val extra = Json.parseToJsonElement(constraints).jsonObject
    val result = (extra - setOf("description", "default", "enum")).toMutableMap()
    if (type != "advanced") result["type"] = JsonPrimitive(type)
    if (description.isNotBlank()) result["description"] = JsonPrimitive(description)
    if (useDefault) {
        val value = if (type == "string") JsonPrimitive(defaultValue) else Json.parseToJsonElement(defaultValue)
        require(parameterValueMatchesType(value, type)) { "Default value has the wrong type" }
        result["default"] = value
    }
    if (enumValues.isNotBlank()) {
        val values = Json.parseToJsonElement(enumValues).jsonArray
        require(values.isNotEmpty() && values.all { parameterValueMatchesType(it, type) }) { "Invalid allowed values" }
        if (useDefault) require(result["default"] in values) { "Default is not an allowed value" }
        result["enum"] = values
    }
    return JsonObject(result)
}

private fun parameterValueMatchesType(value: JsonElement, type: String): Boolean = when (type) {
    "string" -> value is JsonPrimitive && value.isString
    "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
    "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
    "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
    "array" -> value is JsonArray
    "object" -> value is JsonObject
    "null" -> value == JsonNull
    else -> true // Advanced schemas retain unions and constraints for the provider/API to validate.
}

/** Rename references through parsed JSON so identifiers containing quotes remain escaped. */
internal fun renameHttpParameterTokens(template: String, originalName: String, name: String): String {
    val token = Regex("\\{\\{\\s*" + Regex.escape(originalName) + "\\s*\\}\\}")
    fun replace(text: String) = token.replace(text) { "{{" + name + "}}" }
    fun rewrite(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { rewrite(it.value) })
        is JsonArray -> JsonArray(value.map { rewrite(it) })
        is JsonPrimitive -> if (value.isString) JsonPrimitive(replace(value.content)) else value
    }
    val json = runCatching { Json.parseToJsonElement(template) }.getOrNull()
    return if (json == null) replace(template) else rewrite(json).toString()
}
