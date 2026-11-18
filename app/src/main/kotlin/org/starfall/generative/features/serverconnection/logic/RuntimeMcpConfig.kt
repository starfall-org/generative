package org.starfall.generative.features.serverconnection.logic

import kotlinx.serialization.json.*

/** Keep protocol-specific options intact and reject collisions with fields owned by the form. */
internal fun runtimeMcpConfig(type: String, url: String, headers: List<Pair<String, String>>, options: String): JsonObject? {
    val extra = runCatching { Json.parseToJsonElement(options) as? JsonObject }.getOrNull() ?: return null
    if ("type" in extra) return null
    when (type) {
        "remote" -> {
            if (url.isBlank() || "url" in extra || "headers" in extra) return null
            if (headers.any { (key, value) -> key.isBlank() || key.any { it <= ' ' || it.code >= 127 || it == ':' } ||
                    value.any { it == '\r' || it == '\n' } } ||
                headers.map { it.first.lowercase(java.util.Locale.ROOT) }.distinct().size != headers.size) return null
        }
        "local" -> {
            val command = extra["command"] as? JsonArray ?: return null
            if (command.isEmpty() || command.any { it !is JsonPrimitive || !it.isString } ||
                (command.first() as JsonPrimitive).content.isBlank()) return null
        }
        else -> return null
    }
    return buildJsonObject {
        put("type", type)
        if (type == "remote") {
            put("url", url.trim())
            if (headers.isNotEmpty()) put("headers", buildJsonObject { headers.forEach { (key, value) -> put(key, value) } })
        }
        extra.forEach { (key, value) -> put(key, value) }
    }
}
