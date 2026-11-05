package org.starfall.generative.shared.helpers.media

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.starfall.generative.shared.model.media.ContentApiMedia
import org.starfall.generative.shared.model.media.PreparedContentApiResult
import org.starfall.generative.shared.helpers.networking.text

/** Handle direct JSON, MCP structuredContent and JSON encoded in MCP text blocks. */
internal fun prepareContentApiResult(result: JsonObject): PreparedContentApiResult {
    val media = linkedMapOf<String, ContentApiMedia>()
    val hiddenUrls = linkedSetOf<String>()
    fun embedded(value: JsonPrimitive): JsonElement? {
        if (!value.isString) return null
        val text = value.content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (!text.startsWith("{") && !text.startsWith("[")) return null
        return runCatching { Json.parseToJsonElement(text) }.getOrNull()
    }
    fun collect(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                (element["media"] as? JsonArray)?.forEach { value ->
                    val item = value as? JsonObject ?: return@forEach
                    val url = item.text("url").trim()
                    if (url.isNotBlank()) hiddenUrls += url
                    val type = item.text("type").lowercase()
                    if (type in setOf("image", "video", "audio") && url.toHttpUrlOrNull() != null) {
                        media.putIfAbsent(url, ContentApiMedia(url, type))
                    }
                }
                element.text("thumbnail_url").takeIf { it.isNotBlank() }?.let(hiddenUrls::add)
                element.values.forEach(::collect)
            }
            is JsonArray -> element.forEach(::collect)
            is JsonPrimitive -> embedded(element)?.let(::collect)
        }
    }
    collect(result)
    val replacements = hiddenUrls.sortedByDescending { it.length }
    fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(element.map(::redact))
        is JsonPrimitive -> if (!element.isString) element else {
            val parsed = embedded(element)
            if (parsed != null) JsonPrimitive(redact(parsed).toString())
            else JsonPrimitive(replacements.fold(element.content) { text, url -> text.replace(url, "[media URL hidden by app]") })
        }
    }
    return PreparedContentApiResult(redact(result).jsonObject, media.values.take(32))
}

