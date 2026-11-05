package org.starfall.generative.shared.subfeatures.media.logic

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream

internal data class ResolvedAttachment(
    val reference: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isPdf: Boolean get() = mimeType == "application/pdf"
    val isText: Boolean get() = mimeType.startsWith("text/") || mimeType in setOf(
        "application/json", "application/xml", "application/javascript", "application/x-yaml",
        "application/yaml", "text/yaml", "text/markdown"
    )
}

internal class AttachmentResolver(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun metadata(reference: String): ResolvedAttachment? {
        val uri = runCatching { Uri.parse(reference) }.getOrNull() ?: return null

        if (uri.scheme == null || uri.scheme == "file") {
            val file = if (uri.scheme == "file") File(uri.path.orEmpty()) else File(reference)
            if (!file.exists() || !file.isFile) return null
            return ResolvedAttachment(reference, file.name, guessMime(file.name), file.length())
        }

        if (uri.scheme != "content") return null

        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }

        val finalName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "attachment"
        val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: guessMime(finalName)
        val finalSize = size ?: runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0 } ?: 0L

        return ResolvedAttachment(reference, finalName, mime, finalSize)
    }

    fun open(reference: String): InputStream? {
        val uri = runCatching { Uri.parse(reference) }.getOrNull() ?: return null
        return when {
            uri.scheme == null -> File(reference).takeIf { it.isFile }?.inputStream()
            uri.scheme == "file" -> File(uri.path.orEmpty()).takeIf { it.isFile }?.inputStream()
            uri.scheme == "content" -> resolver.openInputStream(uri)
            else -> null
        }
    }

    fun readBytes(reference: String, maxBytes: Long = 30L * 1024L * 1024L): ByteArray? {
        val meta = metadata(reference) ?: return null
        require(meta.sizeBytes <= 0 || meta.sizeBytes <= maxBytes) {
            "Attachment is too large for inline transfer."
        }
        return open(reference)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                require(total <= maxBytes) { "Attachment is too large for inline transfer." }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
