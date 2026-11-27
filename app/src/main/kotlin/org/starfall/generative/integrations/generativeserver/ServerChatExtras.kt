package org.starfall.generative.integrations.generativeserver

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

data class ServerAttachmentUploadLimits(val maxFiles: Int, val maxFileBytes: Long, val maxTotalBytes: Long) {
    internal companion object {
        fun negotiate(attachments: Boolean, operations: Set<String>, value: JsonElement?): ServerAttachmentUploadLimits? {
            if (!attachments || "attachment.upload_chunk" !in operations) return null
            val options = value as? JsonObject ?: return null
            fun limit(key: String, max: Int): Int? =
                (options[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..max }
            if (limit("version", 1) == null) return null
            val maxFiles = limit("max_files", 20) ?: return null
            val maxFile = limit("max_file_bytes", 256 shl 20) ?: return null
            val maxTotal = limit("max_total_bytes", 256 shl 20) ?: return null
            return ServerAttachmentUploadLimits(maxFiles, maxFile.toLong(), maxTotal.toLong())
        }
    }
}

data class ServerAttachmentSource(val file: File, val mimeType: String, val sizeBytes: Long)

@Serializable
internal data class ServerTool(val id: String, val name: String, val description: String = "")

@Serializable
private data class ServerToolCatalog(val version: Int, val tools: List<ServerTool>)

internal fun decodeServerTools(data: JsonElement): List<ServerTool> {
    val catalog = GenerativeServerCodec.json.decodeFromJsonElement<ServerToolCatalog>(data)
    val ids = mutableSetOf<String>()
    if (catalog.version != 1 || catalog.tools.size > 1000 || catalog.tools.any {
            it.id.isBlank() || it.id.length > 160 || !ids.add(it.id) ||
                it.name.isBlank() || it.name.length > 160 || it.description.length > 2000
        }
    ) throw GenerativeServerProtocolException("Invalid server tool catalog")
    return catalog.tools
}
