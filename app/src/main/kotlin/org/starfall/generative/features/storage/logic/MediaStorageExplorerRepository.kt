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
import org.starfall.generative.shared.files.AppDocumentsLocation
import org.starfall.generative.shared.files.MediaStorageDocuments
import org.starfall.generative.shared.files.ToolFiles

/** MediaStorage's sole adapter to the reusable explorer. No Remote Machines dependency. */
class MediaStorageExplorerRepository(context: Context) : ExplorerRepository {
    private val app = context.applicationContext
    private val root = ToolFiles.appStorageDirectory(app).canonicalFile
    private val media = ExplorerLocation(sourceId = "media", label = "MediaStorage")

    override suspend fun list(location: ExplorerLocation): List<ExplorerItem> = when {
        location.isHome -> listOf(ExplorerItem(media.label, media, true))
        location.sourceId == media.sourceId -> withContext(Dispatchers.IO) {
            val directory = mediaFile(location.path)
            if (!directory.isDirectory) throw FileNotFoundException("Not a directory")
            directory.listFiles().orEmpty().asSequence()
                .filter { !it.name.endsWith(".part") && !it.name.endsWith(".spill") &&
                    runCatching { mediaFile(join(location.path, it.name)) }.isSuccess }
                .map { file -> ExplorerItem(file.name, location.copy(path = join(location.path, file.name)),
                    file.isDirectory, file.length(), file.lastModified()) }
                .sortedWith(compareByDescending<ExplorerItem> { it.directory }.thenBy { it.name.lowercase() })
                .toList()
        }
        else -> throw FileNotFoundException("Unknown storage location")
    }

    override suspend fun createFolder(parent: ExplorerLocation, name: String) {
        val safe = checkName(name)
        requireMedia(parent)
        withContext(Dispatchers.IO) {
            if (!mediaFile(join(parent.path, safe)).mkdir()) throw FileNotFoundException("Could not create directory")
        }
    }

    override suspend fun rename(item: ExplorerItem, newName: String) {
        val safe = checkName(newName)
        requireMedia(item.location)
        require(item.location.path.isNotEmpty()) { "Cannot rename the storage root" }
        withContext(Dispatchers.IO) {
            val source = mediaFile(item.location.path)
            val target = mediaFile(join(item.location.parent().path, safe))
            if (target.exists() || !source.renameTo(target)) throw FileNotFoundException("Could not rename file")
            ToolFiles.revision.value++
        }
    }

    override suspend fun delete(item: ExplorerItem) {
        requireMedia(item.location)
        require(item.location.path.isNotEmpty()) { "Cannot delete the storage root" }
        withContext(Dispatchers.IO) {
            if (!mediaFile(item.location.path).deleteRecursively()) throw FileNotFoundException("Could not delete file")
            ToolFiles.revision.value++
        }
    }

    /** Upload into a validated directory, without overwriting an existing file. */
    suspend fun upload(parent: ExplorerLocation, name: String, input: InputStream) {
        requireMedia(parent)
        val safeName = checkName(name)
        withContext(Dispatchers.IO) {
            val directory = mediaFile(parent.path)
            if (!directory.isDirectory) throw FileNotFoundException("Not a directory")
            val target = mediaFile(join(parent.path, safeName))
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
        requireMedia(item.location)
        return mediaFile(item.location.path).also {
            if (item.directory || !it.isFile) throw FileNotFoundException("Not a file")
        }
    }

    fun documentUri(item: ExplorerItem): Uri {
        fileFor(item)
        return DocumentsContract.buildDocumentUri(AppDocumentsLocation.authority(app),
            "${MediaStorageDocuments.ROOT_ID}/${item.location.path}")
    }

    private fun requireMedia(location: ExplorerLocation) {
        require(location.sourceId == media.sourceId) { "Unknown storage location" }
    }

    private fun mediaFile(relative: String): File {
        if (relative.startsWith('/') || '\u0000' in relative || relative.split('/').any { it == "." || it == ".." ||
                (relative.isNotBlank() && it.isEmpty()) }) throw FileNotFoundException("Invalid media path")
        val file = File(root, relative).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Outside MediaStorage")
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
