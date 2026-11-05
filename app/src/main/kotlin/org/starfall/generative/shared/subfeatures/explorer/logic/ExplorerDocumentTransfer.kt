package org.starfall.generative.shared.subfeatures.explorer.logic

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Document-picker metadata is resolved by the host, never by the Explorer extension. */
fun ContentResolver.explorerDocumentName(uri: Uri): String =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
    } ?: throw FileNotFoundException("The selected document has no file name")

// Keep resolver calls and stream copies off the UI thread for both local and remote hosts.
suspend fun ContentResolver.explorerImport(
    uri: Uri, upload: suspend (String, InputStream) -> Unit
) = withContext(Dispatchers.IO) {
    val name = explorerDocumentName(uri)
    (openInputStream(uri) ?: throw FileNotFoundException("Cannot read selected document")).use {
        upload(name, it)
    }
}

suspend fun ContentResolver.explorerExport(
    uri: Uri, download: suspend (OutputStream) -> Unit
) = withContext(Dispatchers.IO) {
    (openOutputStream(uri, "wt") ?: throw FileNotFoundException("Cannot write selected document")).use {
        download(it)
    }
}
