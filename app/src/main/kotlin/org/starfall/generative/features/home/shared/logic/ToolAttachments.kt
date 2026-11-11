package org.starfall.generative.features.home.shared.logic

import kotlinx.serialization.json.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text

/** Internal attachment metadata is converted once to the selected wire protocol. */
internal fun toolAttachmentParts(message: JsonObject, type: ModelApiType): List<JsonElement> =
    (message["_attachments"] as? JsonArray).orEmpty().map { attachment ->
        val file = attachment.jsonObject
        val mime = file.text("mimeType")
        val data = file.text("data")
        val remote = file.text("remoteId").takeIf { it.isNotBlank() }
        val name = file.text("name").ifBlank { "attachment" }
        when (type) {
            ModelApiType.GOOGLE -> if (remote != null) obj("fileData" to obj("mimeType" to str(mime), "fileUri" to str(remote)))
                else obj("inlineData" to obj("mimeType" to str(mime), "data" to str(data)))
            ModelApiType.ANTHROPIC -> {
                if (mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/javascript", "application/yaml", "application/x-yaml")) {
                    return@map obj("type" to str("document"), "title" to str(name),
                        "source" to obj("type" to str("text"), "media_type" to str("text/plain"),
                            "data" to str(String(java.util.Base64.getDecoder().decode(data), Charsets.UTF_8))))
                }
                require(mime.startsWith("image/") || mime == "application/pdf") { "Unsupported Claude attachment" }
                if (remote != null) return@map obj("type" to str(if (mime == "application/pdf") "document" else "image"),
                    "source" to obj("type" to str("file"), "file_id" to str(remote)))
                obj("type" to str(if (mime == "application/pdf") "document" else "image"),
                    "source" to obj("type" to str("base64"), "media_type" to str(mime), "data" to str(data)))
            }
            ModelApiType.OPENAI_RESPONSES -> {
                if (remote != null) obj("type" to str(if (mime.startsWith("image/")) "input_image" else "input_file"), "file_id" to str(remote))
                else if (mime.startsWith("image/")) obj("type" to str("input_image"), "image_url" to str("data:$mime;base64,$data"))
                else obj("type" to str("input_file"), "filename" to str(name), "file_data" to str("data:$mime;base64,$data"))
            }
            ModelApiType.OPENAI -> {
                if (remote != null) obj("type" to str("file"), "file" to obj("file_id" to str(remote)))
                else if (!mime.startsWith("image/")) obj("type" to str("file"), "file" to obj("filename" to str(name), "file_data" to str("data:$mime;base64,$data")))
                else obj("type" to str("image_url"), "image_url" to obj("url" to str("data:$mime;base64,$data")))
            }
            else -> {
                require(mime.startsWith("image/")) { "Unsupported chat attachment" }
                obj("type" to str("image_url"), "image_url" to obj("url" to str("data:$mime;base64,$data")))
            }
        }
    }

internal fun toolMessageContent(message: JsonObject, type: ModelApiType): JsonElement {
    if (type == ModelApiType.OLLAMA) return message["content"] ?: str("")
    val files = toolAttachmentParts(message, type)
    if (files.isEmpty()) return message["content"] ?: str("")
    return JsonArray(listOf(obj("type" to str(if (type == ModelApiType.OPENAI_RESPONSES) "input_text" else "text"),
        "text" to str(message.text("content")))) + files)
}
