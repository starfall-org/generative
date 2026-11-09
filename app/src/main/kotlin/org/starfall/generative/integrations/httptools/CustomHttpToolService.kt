package org.starfall.generative.integrations.httptools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.model.CustomHttpTool
import org.starfall.generative.shared.model.HttpToolResponseMode

/** Executes one configured request through the same cancellable transport as other chat tools. */
class CustomHttpToolService(private val http: ToolHttp) {
    internal fun request(tool: CustomHttpTool, arguments: JsonObject): okhttp3.Request =
        buildHttpToolRequest(tool, arguments)

    suspend fun execute(tool: CustomHttpTool, arguments: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        check(tool.enabled) { "Custom HTTP tool disabled" }
        http.withResponse(request(tool, arguments)) { response ->
            http.requireSuccess(response)
            val mime = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
            val mode = if (tool.responseMode != HttpToolResponseMode.AUTO) tool.responseMode else when {
                mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") || mime == "application/octet-stream" -> HttpToolResponseMode.MEDIA
                mime.contains("json") -> HttpToolResponseMode.JSON
                else -> HttpToolResponseMode.RAW
            }
            val data: JsonElement = when (mode) {
                HttpToolResponseMode.MEDIA -> {
                    val file = http.requireFiles().save(response.body.byteStream(), mime)
                    buildJsonObject { put("uri", "tool-file:$file"); put("mimeType", mime) }
                }
                HttpToolResponseMode.JSON -> Json.parseToJsonElement(http.readJson(response, sanitizeMedia = false))
                else -> JsonPrimitive(http.readJson(response, sanitizeMedia = false))
            }
            val mapped = if (tool.responseMapping.isEmpty()) data else structureHttpResponse(data, tool.responseMapping)
            buildJsonObject {
                put("status", response.code)
                put("result", mapped)
                // Retain media even if the configured output shape omits the URI.
                if (mode == HttpToolResponseMode.MEDIA) put("files", JsonArray(listOf(data.jsonObject.getValue("uri"))))
            }
        }
    }
}
