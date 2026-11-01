package org.starfall.generative.shared.files

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Snapshot a user-selected file once while its URI grant is still valid. A saved chat always
 * references an app-private file, not the original document or a revocable content:// grant.
 * Only temporary conversations use the disposable cache directory.
 */
internal suspend fun importUserAttachment(
    context: Context,
    files: ToolFiles,
    uri: Uri,
    fallbackMime: String? = null,
    temporaryChatId: String? = null
): String = withContext(Dispatchers.IO + (temporaryChatId?.let(::TemporaryChatFiles) ?: EmptyCoroutineContext)) {
    val reference = uri.toString()
    // Reuse an existing upload or a known tool-generated file; never reclassify generated
    // media as a disposable user upload. Other files must be snapshotted into UploadCaches.
    files.nameOf(reference)?.let { name ->
        val stored = files.resolveInCurrentSession(name)
        if (stored != null && (files.isUserUpload(name) || files.isGeneratedMedia(name))) {
            return@withContext stored.absolutePath
        }
    }
    val resolver = AttachmentResolver(context)
    val mime = runCatching { resolver.metadata(reference)?.mimeType }.getOrNull()
        ?.takeUnless { it == "application/octet-stream" || it == "*/*" }
        ?: fallbackMime
    val input = resolver.open(reference) ?: error("Selected attachment is no longer readable")
    val name = files.saveUpload(input, mime)
    files.resolveUploadInCurrentSession(name)?.absolutePath ?: error("Selected attachment was not saved")
}
