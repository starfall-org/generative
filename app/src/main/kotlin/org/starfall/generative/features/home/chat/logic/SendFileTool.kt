package org.starfall.generative.features.home.chat.logic

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text

internal const val SEND_FILE_TOOL_NAME = "send_file"
internal val sendFileDefinition = ToolDefinition(
    name = SEND_FILE_TOOL_NAME,
    description = "Send one or more files from a previous tool response to the user. For one file provide type and uri; for multiple files provide files with type and uri for each item. Choose image, video, audio, or other. The app displays them; do not print file URIs or media links in your answer.",
    schema = obj(
        "type" to str("object"),
        "properties" to obj(
            "type" to obj("type" to str("string"), "enum" to JsonArray(listOf("video", "image", "audio", "other").map(::str))),
            "uri" to obj("type" to str("string"), "description" to str("File URI or download URL returned by a tool.")),
            "files" to obj(
                "type" to str("array"),
                "minItems" to JsonPrimitive(1),
                "maxItems" to JsonPrimitive(32),
                "items" to obj(
                    "type" to str("object"),
                    "properties" to obj(
                        "type" to obj("type" to str("string"), "enum" to JsonArray(listOf("video", "image", "audio", "other").map(::str))),
                        "uri" to obj("type" to str("string"), "description" to str("File URI or download URL returned by a tool."))
                    ),
                    "required" to JsonArray(listOf(str("type"), str("uri"))),
                    "additionalProperties" to JsonPrimitive(false)
                )
            )
        ),
        "anyOf" to JsonArray(listOf(
            obj("required" to JsonArray(listOf(str("type"), str("uri")))),
            obj("required" to JsonArray(listOf(str("files"))))
        )),
        "additionalProperties" to JsonPrimitive(false)
    )
)

internal class SendFileTool(private val http: ToolHttp) {
    private val downloads = mutableMapOf<String, String>()

    suspend fun execute(arguments: JsonObject): JsonObject {
        data class RequestedFile(val type: String, val uri: String)
        val requests = (arguments["files"] as? JsonArray)?.map { value ->
            val item = value as? JsonObject ?: error("Each file must be an object.")
            RequestedFile(item.text("type"), item.text("uri"))
        } ?: listOf(RequestedFile(arguments.text("type"), arguments.text("uri")))
        require(requests.isNotEmpty() && requests.size <= 32) { "Provide between 1 and 32 files." }
        val files = http.requireFiles()
        val sent = requests.map { request ->
            val type = request.type
            require(type in setOf("video", "image", "audio", "other")) { "File type must be video, image, audio, or other." }
            val uri = request.uri.trim()
            require(uri.isNotBlank()) { "File URI is required." }
            val name = when {
                uri.startsWith("tool-file:") -> uri.removePrefix("tool-file:").also {
                    check(files.resolve(it) != null) { "The requested file is unavailable." }
                }
                files.resolve(uri) != null -> uri
                uri.toHttpUrlOrNull() != null -> downloads[uri]?.takeIf { files.resolve(it) != null }
                    ?: http.download(uri).also { downloads[uri] = it }
                else -> error("Use a tool-file: URI or an HTTP(S) download URL from a tool response.")
            }
            obj("type" to str(type), "uri" to str("tool-file:$name"))
        }
        return obj(
            "sent" to JsonPrimitive(true),
            "count" to JsonPrimitive(sent.size),
            "files" to JsonArray(sent),
            // Keep the single-file shape for older callers and models.
            "type" to str(sent.first().text("type")),
            "uri" to str(sent.first().text("uri"))
        )
    }
}

internal fun fileDeliveryResult(result: JsonObject, names: List<String>, canSend: Boolean = true): JsonObject {
    // With Send File off the model must not learn the tool exists, so the result stays untouched.
    if (!canSend) return result
    return JsonObject(result + mapOf(
        "app_files" to JsonArray(names.distinct().map { name ->
            val type = when (name.substringAfterLast('.').lowercase()) {
                "png", "jpg", "jpeg", "gif", "webp" -> "image"
                "mp4", "webm", "mov", "mkv" -> "video"
                "mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "weba" -> "audio"
                else -> "other"
            }
            obj("type" to str(type), "uri" to str("tool-file:$name"))
        }),
        "app_file_delivery" to str("Files are available but have not been sent to the user. Use send_file(type, uri) to display a relevant file in chat. Do not print media URLs or local file URIs in your answer.")
    ))
}
