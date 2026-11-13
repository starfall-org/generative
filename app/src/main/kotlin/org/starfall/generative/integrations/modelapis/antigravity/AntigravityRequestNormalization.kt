package org.starfall.generative.integrations.modelapis.antigravity

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text
import org.starfall.generative.shared.helpers.networking.mapOnAccess

/** Model-specific normalization applied to the inner Antigravity request envelope. */
internal fun normalizeAntigravityRequest(request: JsonObject, model: String, credentialId: String): JsonObject {
    var result = request
    if (model.contains("claude")) {
        result = withValidatedToolSchemas(result)
        val toolConfig = result["toolConfig"] as? JsonObject ?: obj()
        val functionConfig = toolConfig["functionCallingConfig"] as? JsonObject ?: obj()
        result = JsonObject(result + ("toolConfig" to JsonObject(toolConfig +
            ("functionCallingConfig" to JsonObject(functionConfig + ("mode" to str("VALIDATED")))))))
    } else {
        val generation = result["generationConfig"] as? JsonObject
        if (generation != null) {
            result = JsonObject(result + ("generationConfig" to JsonObject(generation - "maxOutputTokens")))
        }
    }

    val explicit = (result["sessionId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (explicit.isNotEmpty()) return JsonObject(result + ("sessionId" to str(explicit)))
    // Native image requests have per-generation identity rather than a conversation session.
    if (model.contains("image")) return result
    val firstUserText = (result["contents"] as? JsonArray).orEmpty().firstNotNullOfOrNull { element ->
        val content = element as? JsonObject ?: return@firstNotNullOfOrNull null
        if (content.text("role") != "user") return@firstNotNullOfOrNull null
        ((content["parts"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("text")
            ?.takeIf { it.isNotEmpty() }
    }
    val session = firstUserText?.let { stableSessionId(credentialId, it) } ?: randomSessionId()
    return JsonObject(result + ("sessionId" to str(session)))
}

/** Remove only synthetic VALIDATED placeholders, never a caller-declared dynamic property. */
internal fun restoreAntigravityToolArguments(originalRequest: JsonObject, response: JsonObject): JsonObject {
    if (!originalRequest.text("model").contains("claude")) return response
    val schemas = mutableMapOf<String, JsonObject>()
    (originalRequest["tools"] as? JsonArray).orEmpty().forEach { toolElement ->
        val tool = toolElement as? JsonObject ?: return@forEach
        for (key in listOf("functionDeclarations", "function_declarations")) {
            (tool[key] as? JsonArray).orEmpty().forEach { declarationElement ->
                val declaration = declarationElement as? JsonObject ?: return@forEach
                val name = declaration.text("name")
                if (name.isNotEmpty()) schemas[name] = declaration["parameters"] as? JsonObject ?: obj()
            }
        }
    }
    if (schemas.isEmpty()) return response
    val candidates = response["candidates"] as? JsonArray ?: return response
    return JsonObject(response + ("candidates" to candidates.mapOnAccess candidate@{ candidateElement ->
        val candidate = candidateElement as? JsonObject ?: return@candidate candidateElement
        val content = candidate["content"] as? JsonObject ?: return@candidate candidate
        val parts = content["parts"] as? JsonArray ?: return@candidate candidate
        val updatedParts = parts.mapOnAccess part@{ partElement ->
            val part = partElement as? JsonObject ?: return@part partElement
            val call = part["functionCall"] as? JsonObject ?: return@part part
            val schema = schemas[call.text("name")] ?: return@part part
            val args = call["args"] as? JsonObject ?: return@part part
            JsonObject(part + ("functionCall" to JsonObject(call + ("args" to cleanArguments(args, schema)))))
        }
        JsonObject(candidate + ("content" to JsonObject(content + ("parts" to updatedParts))))
    }))
}

private fun withValidatedToolSchemas(request: JsonObject): JsonObject {
    val tools = request["tools"] as? JsonArray ?: return request
    return JsonObject(request + ("tools" to JsonArray(tools.map { toolElement ->
        val tool = toolElement as? JsonObject ?: return@map toolElement
        var result = tool
        for (key in listOf("functionDeclarations", "function_declarations")) {
            val declarations = result[key] as? JsonArray ?: continue
            result = JsonObject(result + (key to JsonArray(declarations.map { declarationElement ->
                val declaration = declarationElement as? JsonObject ?: return@map declarationElement
                val parameters = declaration["parameters"] as? JsonObject ?: obj("type" to str("object"))
                JsonObject(declaration + ("parameters" to validatedSchema(parameters)))
            })))
        }
        result
    })))
}

private fun validatedSchema(schema: JsonObject): JsonObject {
    var result = schema.ifEmpty { obj("type" to str("object")) }
    (result["properties"] as? JsonObject)?.let { properties ->
        result = JsonObject(result + ("properties" to JsonObject(properties.mapValues { (_, child) ->
            (child as? JsonObject)?.let(::validatedSchema) ?: child
        })))
    }
    (result["items"] as? JsonObject)?.let { items -> result = JsonObject(result + ("items" to validatedSchema(items))) }
    for (key in listOf("anyOf", "oneOf")) {
        (result[key] as? JsonArray)?.let { branches ->
            result = JsonObject(result + (key to JsonArray(branches.map { branch ->
                (branch as? JsonObject)?.let(::validatedSchema) ?: branch
            })))
        }
    }
    val properties = result["properties"] as? JsonObject
    val isObject = result.text("type") == "object" || properties != null
    val required = result["required"] as? JsonArray
    if (isObject && required.isNullOrEmpty()) {
        val withPlaceholder = (properties ?: obj()).let { current ->
            if (current.containsKey("_")) current else JsonObject(current + ("_" to obj("type" to str("boolean"))))
        }
        result = JsonObject(result + ("properties" to withPlaceholder) +
            ("required" to JsonArray(listOf(str("_")))))
    }
    return result
}

private fun cleanArguments(args: JsonObject, schema: JsonObject): JsonObject = JsonObject(args.mapNotNull { (key, value) ->
    if (key == "_" && placeholderInjected(schema)) return@mapNotNull null
    val childSchema = propertySchema(schema, key)
    key to cleanArgumentValue(value, childSchema)
}.toMap())

private fun cleanArgumentValue(value: JsonElement, schema: JsonObject?): JsonElement = when {
    value is JsonObject && schema != null -> cleanArguments(value, schema)
    value is JsonArray -> {
        val items = schema?.get("items") as? JsonObject
        JsonArray(value.map { cleanArgumentValue(it, items) })
    }
    else -> value
}

private fun propertySchema(schema: JsonObject, name: String): JsonObject? {
    ((schema["properties"] as? JsonObject)?.get(name) as? JsonObject)?.let { return it }
    for (key in listOf("anyOf", "oneOf")) {
        (schema[key] as? JsonArray).orEmpty().forEach { branch ->
            ((branch as? JsonObject)?.let { propertySchema(it, name) })?.let { return it }
        }
    }
    return null
}

private fun placeholderInjected(schema: JsonObject): Boolean {
    val properties = schema["properties"] as? JsonObject
    val required = schema["required"] as? JsonArray
    val directObject = schema.isEmpty() || schema.text("type") == "object" || properties != null
    if (directObject) return required.isNullOrEmpty() && properties?.containsKey("_") != true
    val branches = listOf("anyOf", "oneOf").flatMap { key ->
        (schema[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }
    return branches.isNotEmpty() && branches.all(::placeholderInjected)
}

private fun stableSessionId(credentialId: String, firstUserText: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$credentialId\n$firstUserText".toByteArray(Charsets.UTF_8))
    var value = 0L
    repeat(8) { index -> value = (value shl 8) or (digest[index].toInt() and 0xff).toLong() }
    return "-${value and Long.MAX_VALUE}"
}

private fun randomSessionId(): String = "-${UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE}"
