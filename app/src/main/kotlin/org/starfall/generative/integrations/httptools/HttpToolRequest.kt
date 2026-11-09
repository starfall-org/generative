package org.starfall.generative.integrations.httptools

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.starfall.generative.shared.model.CustomHttpTool
import org.starfall.generative.shared.model.HttpToolBodyFormat

/** Pure request preparation shared by execution and the offline editor preview. */
fun buildHttpToolRequest(tool: CustomHttpTool, arguments: JsonObject): Request {
    tool.validate()
    val args = httpToolArguments(tool.parameters, arguments)
    val url = tool.url.toHttpUrl().newBuilder()
    mapHttpValue(tool.query, args).jsonObject.forEach { (name, value) ->
        // The Query editor owns each configured key, including keys already in the URL.
        url.removeAllQueryParameters(name)
        val values = if (value is JsonArray) value else listOf(value)
        values.forEach { item ->
            url.addQueryParameter(name, if (item is JsonPrimitive) item.content else item.toString())
        }
    }
    val builder = Request.Builder().url(url.build()).header("Accept", "*/*")
    mapHttpValue(tool.headers, args).jsonObject.forEach { (name, value) ->
        require(value is JsonPrimitive && value != JsonNull) { "Header values must be literals" }
        require(value.content.none { it == '\r' || it == '\n' }) { "Invalid request header value" }
        builder.header(name, value.content)
    }
    val bodyText = when {
        tool.body.isBlank() -> ""
        tool.bodyFormat == HttpToolBodyFormat.JSON -> mapHttpValue(Json.parseToJsonElement(tool.body), args).toString()
        else -> mapHttpText(tool.body, args)
    }
    val body = if (tool.method in listOf("GET", "HEAD") ||
        tool.method in listOf("DELETE", "OPTIONS") && bodyText.isBlank()) null else {
        val defaultType = if (tool.bodyFormat == HttpToolBodyFormat.JSON) "application/json" else "text/plain"
        val contentType = builder.build().header("Content-Type") ?: defaultType
        bodyText.toRequestBody(contentType.toMediaType())
    }
    return builder.method(tool.method, body).build()
}
