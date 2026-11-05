package org.starfall.generative.features.storage.logic

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerLocation
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerRepository
import org.starfall.generative.shared.subfeatures.media.logic.AppDocumentsLocation
import org.starfall.generative.shared.subfeatures.media.logic.MediaStorageDocuments
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles

/** Local MediaStorage and UploadCaches adapter to the reusable explorer. */
class MediaStorageExplorerRepository(context: Context) : ExplorerRepository {
    private val app = context.applicationContext
    private val root = ToolFiles.appStorageDirectory(app).canonicalFile
    private val uploadRoot = ToolFiles.uploadStorageDirectory(app).canonicalFile
    private val media = ExplorerLocation(sourceId = "media", label = "MediaStorage")
    private val uploads = ExplorerLocation(sourceId = "uploads", label = "UploadCaches")
    private fun rootFor(location: ExplorerLocation): File = when (location.sourceId) {
        media.sourceId -> root
        uploads.sourceId -> uploadRoot
        else -> throw FileNotFoundException("Unknown storage location")
    }

    override suspend fun list(location: ExplorerLocation): List<ExplorerItem> = when {
        location.isHome -> listOf(ExplorerItem(media.label, media, true), ExplorerItem(uploads.label, uploads, true))
        location.sourceId == media.sourceId || location.sourceId == uploads.sourceId -> withContext(Dispatchers.IO) {
            val directory = mediaFile(location, location.path)
            if (!directory.isDirectory) throw FileNotFoundException("Not a directory")
            directory.listFiles().orEmpty().asSequence()
                .filter { !it.name.endsWith(".part") && !it.name.endsWith(".spill") &&
                    runCatching { mediaFile(location, join(location.path, it.name)) }.isSuccess }
                .map { file -> ExplorerItem(file.name, location.copy(path = join(location.path, file.name)),
                    file.isDirectory, file.length(), file.lastModified()) }
                .sortedWith(compareByDescending<ExplorerItem> { it.directory }.thenBy { it.name.lowercase() })
                .toList()
        }
        else -> throw FileNotFoundException("Unknown storage location")
    }

    override suspend fun createFolder(parent: ExplorerLocation, name: String) {
        val safe = checkName(name)
        requireLocal(parent)
        withContext(Dispatchers.IO) {
            if (!mediaFile(parent, join(parent.path, safe)).mkdir()) throw FileNotFoundException("Could not create directory")
        }
    }

    override suspend fun rename(item: ExplorerItem, newName: String) {
        val safe = checkName(newName)
        requireLocal(item.location)
        require(item.location.path.isNotEmpty()) { "Cannot rename the storage root" }
        withContext(Dispatchers.IO) {
            val source = mediaFile(item.location, item.location.path)
            val target = mediaFile(item.location, join(item.location.parent().path, safe))
            if (target.exists() || !source.renameTo(target)) throw FileNotFoundException("Could not rename file")
            ToolFiles.revision.value++
        }
    }

    override suspend fun delete(item: ExplorerItem) {
        requireLocal(item.location)
        require(item.location.path.isNotEmpty()) { "Cannot delete the storage root" }
        withContext(Dispatchers.IO) {
            if (!mediaFile(item.location, item.location.path).deleteRecursively()) throw FileNotFoundException("Could not delete file")
            ToolFiles.revision.value++
        }
    }

    /** Upload into a validated directory, without overwriting an existing file. */
    suspend fun upload(parent: ExplorerLocation, name: String, input: InputStream) {
        requireLocal(parent)
        val safeName = checkName(name)
        withContext(Dispatchers.IO) {
            val directory = mediaFile(parent, parent.path)
            if (!directory.isDirectory) throw FileNotFoundException("Not a directory")
            val target = mediaFile(parent, join(parent.path, safeName))
            if (!target.createNewFile()) throw FileNotFoundException("File already exists: $safeName")
            try {
                target.outputStream().use { input.copyTo(it) }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            ToolFiles.revision.value++
        }
    }

    suspend fun download(item: ExplorerItem, output: OutputStream) = withContext(Dispatchers.IO) {
        fileFor(item).inputStream().use { it.copyTo(output) }
        Unit
    }

    fun fileFor(item: ExplorerItem): File {
        requireLocal(item.location)
        return mediaFile(item.location, item.location.path).also {
            if (item.directory || !it.isFile) throw FileNotFoundException("Not a file")
        }
    }

    fun documentUri(item: ExplorerItem): Uri {
        fileFor(item)
        val rootId = if (item.location.sourceId == uploads.sourceId)
            MediaStorageDocuments.UPLOAD_ROOT_ID else MediaStorageDocuments.ROOT_ID
        return DocumentsContract.buildDocumentUri(AppDocumentsLocation.authority(app),
            "$rootId/${item.location.path}")
    }

    private fun requireLocal(location: ExplorerLocation) {
        require(location.sourceId == media.sourceId || location.sourceId == uploads.sourceId) {
            "Unknown storage location"
        }
    }

    private fun mediaFile(location: ExplorerLocation, relative: String): File {
        val root = rootFor(location)
        if (relative.startsWith('/') || '\u0000' in relative || relative.split('/').any { it == "." || it == ".." ||
                (relative.isNotBlank() && it.isEmpty()) }) throw FileNotFoundException("Invalid media path")
        val file = File(root, relative).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Outside storage")
        }
        return file
    }

    private fun checkName(name: String): String {
        if (name.isBlank() || name != name.trim() || name == "." || name == ".." ||
            '/' in name || '\\' in name || '\u0000' in name ||
            name.endsWith(".part") || name.endsWith(".spill")) throw IllegalArgumentException("Invalid file name")
        return name
    }

    private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
}
