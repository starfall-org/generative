package org.starfall.generative.features.storage.logic

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles

/** Flatten files under MediaStorage without exposing directory entries or traversing outside its root. */
fun listMediaStorageFiles(store: ToolFiles): List<File> {
    val root = store.directory.canonicalFile
    val pending = ArrayDeque<File>()
    val visited = mutableSetOf<String>()
    val files = mutableListOf<File>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val directory = pending.removeLast().canonicalFile
        if (!withinMediaStorage(root, directory) || !visited.add(directory.path)) continue
        directory.listFiles().orEmpty().forEach { candidate ->
            if (candidate.name.endsWith(".part") || candidate.name.endsWith(".spill")) return@forEach
            val file = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            // Do not follow aliases: a symlink must not escape the root or duplicate an item.
            if (candidate.absoluteFile != file || !withinMediaStorage(root, file)) return@forEach
            if (file.isDirectory) pending.add(file)
            else if (file.isFile) files.add(file)
        }
    }
    return files.distinctBy { it.path }.sortedByDescending { it.lastModified() }
}

/** Delete exact files, including same-named nested files. The storage hash cache self-prunes on the next save. */
suspend fun deleteMediaStorageFiles(store: ToolFiles, files: Collection<File>) = withContext(Dispatchers.IO) {
    val root = store.directory.canonicalFile
    val targets = files.map { file ->
        file.canonicalFile.also { resolved ->
            require(resolved != root && withinMediaStorage(root, resolved) && resolved.isFile) {
                "File is not within MediaStorage"
            }
        }
    }
    var deleted = false
    try {
        targets.distinctBy { it.path }.forEach { file ->
            if (!file.delete()) throw IOException("Could not delete media file")
            deleted = true
        }
    } finally {
        if (deleted) ToolFiles.revision.update { it + 1 }
    }
}

private fun withinMediaStorage(root: File, file: File): Boolean =
    file == root || file.path.startsWith(root.path + File.separator)
