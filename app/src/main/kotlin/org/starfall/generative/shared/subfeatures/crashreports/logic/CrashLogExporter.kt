package org.starfall.generative.shared.subfeatures.crashreports.logic

import androidx.annotation.RequiresApi
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Writes crash logs to shared storage (Download/Generative) instead of the app's private directory. */
internal object CrashLogExporter {
    private const val FOLDER = "Generative"

    /** Android 9 and below need WRITE_EXTERNAL_STORAGE to write to shared storage; Android 10+ does not. */
    val needsLegacyStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Returns the user-visible location of the saved file, e.g. "Download/Generative/xxx.log". */
    fun save(context: Context, report: CrashReport): Result<String> = runCatching {
        val name = report.logFileName()
        val bytes = report.logText().toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(context, name, bytes)
        else saveLegacy(context, name, bytes)
        "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, name: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create file in shared storage")
        try {
            (resolver.openOutputStream(uri) ?: error("Could not open $uri")).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (failure: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, name: String, bytes: ByteArray) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) error("Could not create ${dir.path}")
        val file = File(dir, name)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
    }
}
