package org.starfall.generative.integrations.remote

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import org.starfall.generative.shared.subfeatures.media.logic.AppDocumentsLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SFTP file operations for the in-app explorer; shares the DocumentsProvider's idle session pool. */
class RemoteSftpExplorer(private val service: RemoteMachineService) {
    fun documentUri(context: Context, machineId: String, relative: String): Uri {
        if (relative.isBlank() || relative.startsWith('/') || '\u0000' in relative ||
            relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException("Invalid SFTP file path")
        }
        if (service.state.value.machines.none { it.id == machineId }) {
            throw FileNotFoundException("Remote machine no longer exists")
        }
        val rootId = sftpRootId(machineId)
        return DocumentsContract.buildDocumentUri(AppDocumentsLocation.authority(context),
            sftpChildDocumentId(rootId, relative))
    }

    data class Entry(val name: String, val relativePath: String, val directory: Boolean, val size: Long, val modified: Long)

    suspend fun list(machineId: String, path: String): List<Entry> = withLease(machineId, path) { lease ->
        lease.channel.ls(lease.absolute(path)).asSequence()
            .filter { it.filename != "." && it.filename != ".." &&
                !(it.filename.startsWith(".explorer-upload-") && it.filename.endsWith(".part")) }
            .map { file ->
                val child = sftpJoinRelative(path, file.filename)
                val attrs = if (file.attrs.isLink) {
                    runCatching { lease.channel.stat(lease.absolute(child)) }.getOrDefault(file.attrs)
                } else file.attrs
                Entry(file.filename, child, attrs.isDir, attrs.size, attrs.mTime.toLong() * 1000L)
            }
            .sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() }).toList()
    }

    /** Stage the upload remotely and rename only after the full transfer completes. */
    suspend fun upload(
        machineId: String, parent: String, name: String, input: InputStream,
        onProgress: (Long) -> Unit = {}
    ) =
        withLease(machineId, parent) { lease ->
            val safeName = sanitizeSftpName(name)
            require(safeName == name && '\\' !in name) { "Invalid file name" }
            val destination = lease.absolute(sftpJoinRelative(parent, safeName))
            val parentAbsolute = lease.absolute(parent)
            if (!lease.channel.stat(parentAbsolute).isDir) throw FileNotFoundException("Not a directory")

            fun exists(path: String): Boolean = try {
                lease.channel.lstat(path)
                true
            } catch (error: SftpException) {
                if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw error
            }

            if (exists(destination)) throw FileNotFoundException("File already exists: $safeName")
            val temp = lease.absolute(sftpJoinRelative(parent, ".explorer-upload-${UUID.randomUUID()}.part"))
            try {
                var transferred = 0L
                val progress = object : SftpProgressMonitor {
                    override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
                    override fun count(count: Long): Boolean {
                        transferred += count
                        onProgress(transferred)
                        return true
                    }
                    override fun end() = Unit
                }
                lease.channel.put(input, temp, progress, ChannelSftp.OVERWRITE)
                if (exists(destination)) throw FileNotFoundException("File already exists: $safeName")
                lease.channel.rename(temp, destination)
            } catch (error: Throwable) {
                runCatching { lease.channel.rm(temp) }
                throw error
            }
        }

    suspend fun download(
        machineId: String, path: String, output: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) =
        withLease(machineId, path) { lease ->
            if (path.isBlank()) throw FileNotFoundException("Not a file")
            val source = lease.absolute(path)
            val attrs = lease.channel.stat(source)
            if (attrs.isDir) throw FileNotFoundException("Not a file")
            var transferred = 0L
            onProgress(0L, attrs.size)
            lease.channel.get(source).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    transferred += count
                    onProgress(transferred, attrs.size)
                }
            }
            Unit
        }

    suspend fun createFolder(machineId: String, parent: String, name: String) = withLease(machineId, parent) { lease ->
        val target = sftpJoinRelative(parent, sanitizeSftpName(name))
        lease.channel.mkdir(lease.absolute(target))
    }

    suspend fun rename(machineId: String, path: String, newName: String) = withLease(machineId, path) { lease ->
        require(path.isNotEmpty()) { "Cannot rename the machine root" }
        val target = sftpJoinRelative(sftpParentRelative(path), sanitizeSftpName(newName))
        lease.channel.rename(lease.absolute(path), lease.absolute(target))
    }

    suspend fun delete(machineId: String, path: String) = withLease(machineId, path) { lease ->
        require(path.isNotEmpty()) { "Cannot delete the machine root" }
        fun remove(path: String) {
            val absolute = lease.absolute(path)
            val attrs = lease.channel.lstat(absolute)
            if (attrs.isDir && !attrs.isLink) {
                lease.channel.ls(absolute).forEach { child ->
                    if (child.filename != "." && child.filename != "..") remove(sftpJoinRelative(path, child.filename))
                }
                lease.channel.rmdir(absolute)
            } else lease.channel.rm(absolute)
        }
        remove(path)
    }

    private suspend fun <T> withLease(machineId: String, path: String, block: (RemoteSftpLease) -> T): T =
        withContext(Dispatchers.IO) {
            // Reject traversal before joining a document-relative path to the configured SFTP root.
            if (path.startsWith('/') || '\u0000' in path || path.split('/').any { it == "." || it == ".." ||
                    (path.isNotEmpty() && it.isEmpty()) }) throw FileNotFoundException("Invalid SFTP path")
            val machine = service.state.value.machines.firstOrNull { it.id == machineId }
                ?: throw FileNotFoundException("Remote machine no longer exists")
            service.acquireSftpLease(machine).use(block)
        }
}
