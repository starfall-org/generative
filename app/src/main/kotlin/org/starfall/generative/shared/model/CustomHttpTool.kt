package org.starfall.generative.shared.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable
enum class HttpToolBodyFormat { JSON, TEXT }

@Serializable
enum class HttpToolResponseMode { AUTO, RAW, JSON, MEDIA }

/** Shared contract for the editor, encrypted catalog and chat runtime. */
@Serializable
data class CustomHttpTool(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = false,
    val method: String = "GET",
    val url: String = "",
    val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    },
    val query: JsonObject = JsonObject(emptyMap()),
    val headers: JsonObject = JsonObject(emptyMap()),
    val body: String = "",
    val bodyFormat: HttpToolBodyFormat = HttpToolBodyFormat.JSON,
    val responseMode: HttpToolResponseMode = HttpToolResponseMode.AUTO,
    /** Output object shape; string leaves are RFC 6901 pointers into the response. */
    val responseMapping: JsonObject = JsonObject(emptyMap())
) {
    fun definition() = ToolDefinition(
        name = "http_$name", description = description, schema = parameters, customHttpToolId = id
    )

    fun validate() {
        require(Regex("[A-Za-z_][A-Za-z0-9_]{0,58}").matches(name)) { "Invalid tool name" }
        require(method in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) { "Invalid HTTP method" }
        val endpoint = java.net.URI(url)
        require(endpoint.scheme in listOf("http", "https") && !endpoint.host.isNullOrBlank() && endpoint.userInfo == null) { "Invalid HTTP URL" }
        require(parameters["type"] == JsonPrimitive("object") && parameters["properties"] is JsonObject) { "Parameters must be an object JSON Schema with properties" }
        val properties = parameters.getValue("properties").jsonObject
        require(properties.values.all { it is JsonObject }) { "Parameter definitions must be JSON Schema objects" }
        parameters["required"]?.let { required ->
            require(required is JsonArray && required.all { it is JsonPrimitive && it.isString && it.content in properties }) {
                "Required parameters must exist in properties"
            }
        }
        require(headers.all { (key, value) ->
            key.isNotBlank() && key.all { it.code in 33..126 && (it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~") } &&
                value is JsonPrimitive && value != JsonNull && value.content.none { it == '\r' || it == '\n' }
        }) { "Invalid HTTP headers" }
        require(headers.keys.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == headers.size) {
            "Header names must be unique (case-insensitive)"
        }
        val token = Regex("\\{\\{\\s*([^{}]+?)\\s*}}")
        fun validateText(text: String) {
            token.findAll(text).forEach { match ->
                require(match.groupValues[1] in properties) { "Unknown parameter: ${match.groupValues[1]}" }
            }
        }
        fun validateTemplate(value: JsonElement) {
            when (value) {
                is JsonObject -> value.values.forEach(::validateTemplate)
                is JsonArray -> value.forEach(::validateTemplate)
                is JsonPrimitive -> if (value.isString) validateText(value.content)
            }
        }
        validateTemplate(query)
        validateTemplate(headers)
        if (body.isNotBlank()) {
            if (bodyFormat == HttpToolBodyFormat.JSON) validateTemplate(Json.parseToJsonElement(body))
            else validateText(body)
        }
        require(method !in listOf("GET", "HEAD") || body.isBlank()) { "GET and HEAD cannot have a body" }
        fun validatePointers(value: JsonElement) {
            when (value) {
                is JsonObject -> value.values.forEach(::validatePointers)
                is JsonArray -> value.forEach(::validatePointers)
                is JsonPrimitive -> require(value.isString && (value.content.isEmpty() || value.content.startsWith('/'))) { "Response mapping leaves must be JSON Pointers" }
            }
        }
        validatePointers(responseMapping)
    }
}
