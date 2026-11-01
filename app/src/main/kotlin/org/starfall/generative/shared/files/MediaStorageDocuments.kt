package org.starfall.generative.shared.files

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** Local file operations for the MediaStorage directory inside the unified DocumentsProvider. */
class MediaStorageDocuments(
    private val appContext: Context,
    private val rootId: String = ROOT_ID,
    private val displayName: String = "MediaStorage",
    private val storageDirectory: File = ToolFiles.appStorageDirectory(appContext)
) {
    private val baseDirectory: File
        get() = storageDirectory

    fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("No such file: $documentId")
        includeFile(cursor, file)
        return cursor
    }

    fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        parent.listFiles()
            ?.filter { !it.name.endsWith(".part") && !it.name.endsWith(".spill") &&
                runCatching { fileFor(documentIdFor(it)) }.isSuccess }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { includeFile(cursor, it) }
        cursor.setNotificationUri(appContext.contentResolver, DocumentsContract.buildChildDocumentsUri(AppDocumentsLocation.authority(appContext), parentDocumentId))
        return cursor
    }

    fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileFor(documentId)
        if (!file.isFile) throw FileNotFoundException("No such file: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode ?: "r"))
    }

    fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val safeName = File(displayName?.takeIf { it.isNotBlank() } ?: "file").name
        if (safeName == "." || safeName == ".." || safeName.endsWith(".part") ||
            safeName.endsWith(".spill") || '\u0000' in safeName) {
            throw FileNotFoundException("Invalid name: $safeName")
        }
        var target = File(parent, safeName)
        var suffix = 1
        while (target.exists()) {
            val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
            target = File(parent, "${safeName.removeSuffix(extension)} ($suffix)$extension")
            suffix++
        }
        // Check the canonical destination before writing (including symlinks and nested folders).
        fileFor(documentIdFor(target))
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (!target.mkdirs()) throw FileNotFoundException("Could not create folder: $safeName")
        } else if (!target.createNewFile()) {
            throw FileNotFoundException("Could not create file: $safeName")
        }
        notifyChildrenChanged(parentDocumentId ?: rootId)
        return documentIdFor(target)
    }

    fun deleteDocument(documentId: String?) {
        val file = fileFor(documentId)
        if (file.canonicalFile == baseDirectory.canonicalFile || !file.exists()) throw FileNotFoundException("No such document: $documentId")
        if (!file.deleteRecursively()) throw FileNotFoundException("Could not delete: $documentId")
        notifyChildrenChanged(documentId?.substringBeforeLast('/', rootId) ?: rootId)
    }

    fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean = runCatching {
        val parent = fileFor(parentDocumentId).canonicalFile
        val child = fileFor(documentId).canonicalFile
        child.exists() && parent.isDirectory && child != parent &&
            child.path.startsWith(parent.path + File.separator)
    }.getOrDefault(false)

    /** MediaStorage/<relative path>, also accepting legacy plain-file IDs from older releases. */
    private fun fileFor(documentId: String?): File {
        val base = baseDirectory.canonicalFile
        if (documentId == rootId) return base
        val path = when {
            documentId == null -> throw FileNotFoundException("Missing document ID")
            documentId.startsWith("$rootId/") -> documentId.removePrefix("$rootId/")
            rootId == ROOT_ID && '/' !in documentId -> documentId // Previously issued media file URI.
            else -> throw FileNotFoundException("Invalid media document: $documentId")
        }
        if (path.isBlank() || path.split('/').any {
            it.isBlank() || it == "." || it == ".." || it.endsWith(".part") || it.endsWith(".spill") || '\u0000' in it
        }) throw FileNotFoundException("Invalid media document: $documentId")
        val file = File(base, path).canonicalFile
        if (!file.path.startsWith(base.path + File.separator)) {
            throw FileNotFoundException("Outside storage: $documentId")
        }
        return file
    }

    private fun documentIdFor(file: File): String =
        "$rootId/${file.relativeTo(baseDirectory).invariantSeparatorsPath}"

    private fun includeFile(cursor: MatrixCursor, file: File) {
        val isRoot = file.canonicalFile == baseDirectory.canonicalFile
        val fields = cursor.columnNames
        cursor.newRow().apply {
            fun put(column: String, value: Any?) {
                if (column in fields) add(column, value)
            }
            put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, if (isRoot) rootId else documentIdFor(file))
            put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (isRoot) displayName else file.name)
            put(DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                        ?: "application/octet-stream")
            put(DocumentsContract.Document.COLUMN_FLAGS, if (file.isDirectory) {
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                    (if (isRoot) 0 else DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
            } else {
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            })
            if (file.isFile) put(DocumentsContract.Document.COLUMN_SIZE, file.length())
            put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun notifyChildrenChanged(parentDocumentId: String) {
        appContext.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AppDocumentsLocation.authority(appContext), parentDocumentId), null)
    }

    companion object {
        const val ROOT_ID = "media_storage"
        const val UPLOAD_ROOT_ID = "upload_caches"

        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}

/** Shared authority and top-level root ID for the single system DocumentsProvider. */
object AppDocumentsLocation {
    const val ROOT_ID = "app_files"
    fun authority(context: Context): String = "${context.packageName}.documents"
}
