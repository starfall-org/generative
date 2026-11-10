package org.starfall.generative.features.customization.ui

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Photo Picker URIs and Media Storage files are copied to long-lived private profile storage. */
internal object UserProfileImages {
    private const val maxImageBytes = 32L * 1024 * 1024
    private fun folder(context: Context) = File(context.filesDir, "user-profile").apply { mkdirs() }

    suspend fun importGallery(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("image/")) { "Please choose an image" }
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read image")
        import(context, input)
    }

    suspend fun importStorage(context: Context, file: File): String = withContext(Dispatchers.IO) {
        val folder = org.starfall.generative.shared.subfeatures.media.logic.ToolFiles.appStorageDirectory(context).canonicalFile
        require(file.canonicalFile.parentFile == folder && file.isFile &&
            org.starfall.generative.shared.subfeatures.media.ui.mediaMimeType(file.name).startsWith("image/"))
        import(context, file.inputStream())
    }

    private fun import(context: Context, source: java.io.InputStream): String {
        val target = File(folder(context), "${UUID.randomUUID()}.image")
        try {
            source.use { input ->
                target.outputStream().use { output ->
                    var size = 0L
                    val buffer = ByteArray(32768)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= maxImageBytes) { "Image is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(target.length() > 0L) { "Empty image" }
            check(android.graphics.BitmapFactory.Options().let { options ->
                options.inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeFile(target.path, options)
                options.outWidth > 0 && options.outHeight > 0
            }) { "Unsupported image" }
            return target.absolutePath
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** Only delete a profile-owned image if no other slot still references it. */
    fun cleanup(context: Context, removed: String?, retained: org.starfall.generative.shared.model.preferences.UserProfile) {
        val file = removed?.let(::File) ?: return
        if (file.parentFile?.canonicalFile != folder(context).canonicalFile) return
        if (removed in listOfNotNull(retained.photo, retained.chatBackground,
                retained.drawerBackground, retained.menuBackground, retained.sheetBackground,
                retained.dialogBackground)) return
        file.delete()
    }
}
