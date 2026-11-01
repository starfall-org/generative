package org.starfall.generative.shared.files

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.update
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import org.starfall.generative.shared.networking.text

/** Marks media created by a temporary chat. Preserved across coroutine dispatcher switches. */
class TemporaryChatFiles(val conversationId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TemporaryChatFiles>
}

/** App-private files only; database rows never contain binary data. */
class ToolFiles(
    val directory: File,
    val uploadDirectory: File? = null,
    private val fileNamePrefix: String = ""
) {
    constructor(context: Context): this(appStorageDirectory(context), uploadStorageDirectory(context)) {
        val root = File(context.cacheDir, "temporary_chat_media")
        synchronized(initialized) {
            if (initializedTemporaryRoots.add(directory.canonicalPath)) {
                // Discard files left by a process that died during a temporary session.
                root.deleteRecursively()
                root.mkdirs()
            }
            cacheRoots[directory.canonicalPath] = root
        }
    }
    init {
        directory.mkdirs()
        synchronized(initialized) {
            if(initialized.add(directory.canonicalPath)) directory.listFiles()?.filter {
                it.name.endsWith(".part") || it.name.endsWith(".spill")
            }?.forEach { it.delete() }
        }
    }
    private data class CachedHash(val length: Long, val modified: Long, val digest: String)
    private class DirectoryIndex(directory: File) {
        val hashes = mutableMapOf<String, CachedHash>()
        val provenance = ToolFileProvenance(File(directory.parentFile, ".${directory.name}.provenance"))
    }
    companion object {
        /**
         * Generated media storage, exposed as MediaStorage by the unified DocumentsProvider.
         * User-uploaded copies are stored separately under UploadCaches.
         */
        fun appStorageDirectory(context: Context): File = File(context.filesDir, "tool_media").apply { mkdirs() }
        /** Durable user-provided copies, never included in Media Management. */
        fun uploadStorageDirectory(context: Context): File = File(context.filesDir, "UploadCaches").apply { mkdirs() }
        val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
        private val initialized = mutableSetOf<String>()
        private val indexes = mutableMapOf<String, DirectoryIndex>()
        private val initializedTemporaryRoots = mutableSetOf<String>()
        private val cacheRoots = mutableMapOf<String, File>()
        private val temporarySessions = mutableMapOf<String, MutableMap<String, ToolFiles>>()
    }
    private val index = synchronized(initialized) {
        indexes.getOrPut(directory.canonicalPath) { DirectoryIndex(directory) }
    }
    private val uploadStore by lazy {
        uploadDirectory?.let { ToolFiles(it, fileNamePrefix = "upload_") }
    }

    /** User input is private and durable, but never part of generated MediaStorage. */
    suspend fun saveUpload(input: InputStream, mime: String? = null, maxBytes: Long = limit): String {
        val temporary = currentTemporaryFiles()
        return if (temporary != null) temporary.save(input, mime, maxBytes)
        else (uploadStore ?: this).save(input, mime, maxBytes)
    }

    /** The upload copy's real file path, or the current temporary-session file path. */
    suspend fun resolveUploadInCurrentSession(name: String): File? {
        if (nameOf(name) != name) return null
        val storage = currentTemporaryFiles()?.directory ?: uploadStore?.directory ?: directory
        val file = File(storage, name)
        return file.takeIf { it.isFile && it.canonicalFile.parentFile == storage.canonicalFile }
    }

    /** Backup/restore can enumerate and replace uploads separately from generated media. */
    fun listUploads(): List<File> = uploadStore?.list().orEmpty()
    fun isUserUpload(name: String): Boolean = uploadStore?.directory?.let { File(it, name).isFile } == true
    suspend fun restoreUpload(name: String, input: InputStream) {
        (uploadStore ?: this).restore(name, input)
    }
    fun clearUploads() {
        uploadStore?.clearAll()
    }
    /** Temporary media lives only in cache and never appears in Media Storage. */
    fun openTemporarySession(id: String, cacheDirectory: File? = null) {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid temporary session ID" }
        synchronized(initialized) {
            val root = cacheDirectory ?: cacheRoots[directory.canonicalPath]
                ?: error("Temporary media cache is not configured")
            cacheRoots[directory.canonicalPath] = root
            root.mkdirs()
            val sessions = temporarySessions.getOrPut(directory.canonicalPath) { mutableMapOf() }
            check(id !in sessions) { "Temporary session already exists" }
            sessions[id] = ToolFiles(File(root, id))
        }
    }

    suspend fun closeTemporarySession(id: String) {
        val session = synchronized(initialized) { temporarySessions[directory.canonicalPath]?.remove(id) }
        if (session != null) withContext(Dispatchers.IO) {
            session.directory.deleteRecursively()
            revision.update { it + 1 }
        }
    }

    private suspend fun currentTemporaryFiles(): ToolFiles? {
        val id = currentCoroutineContext()[TemporaryChatFiles]?.conversationId ?: return null
        // This is already the cache-backed ToolFiles instance, not the permanent store.
        val isCacheInstance = synchronized(initialized) {
            cacheRoots.values.any { directory.canonicalFile.parentFile == it.canonicalFile }
        }
        if (isCacheInstance) return null
        // Do not fall back to permanent storage if a cancelled chat writes late.
        return synchronized(initialized) { temporarySessions[directory.canonicalPath]?.get(id) }
            ?: throw CancellationException("Temporary chat was discarded")
    }

    private val limit = 256L * 1024 * 1024
    fun resolve(name: String): File? {
        if (!name.matches(Regex("[a-zA-Z0-9._-]+")) || name.endsWith(".part")) return null
        val temporary = synchronized(initialized) {
            temporarySessions[directory.canonicalPath]?.values?.toList().orEmpty()
        }
        val permitted = temporary.map { it.directory.canonicalFile } +
            listOfNotNull(uploadStore?.directory?.canonicalFile, directory.canonicalFile)
        return permitted.asSequence().map { File(it, name) }.firstOrNull { candidate ->
            candidate.isFile && candidate.canonicalFile.parentFile in permitted
        }
    }
    /** Resolve the storage selected by the current chat, never a different temporary session. */
    suspend fun resolveInCurrentSession(name: String): File? {
        if (nameOf(name) != name) return null
        val temporary = currentTemporaryFiles()
        if (temporary != null) return temporary.resolveInCurrentSession(name)
        // A different temporary chat can contain an identical hash. Only inspect permanent
        // directories here, otherwise it would shadow the file owned by this conversation.
        val roots = listOfNotNull(uploadStore?.directory, directory).map { it.canonicalFile }
        return roots.asSequence().map { File(it, name) }.firstOrNull { candidate ->
            candidate.isFile && candidate.canonicalFile.parentFile?.let { it in roots } == true
        }
    }

    /**
     * Name of the app-private file a stored reference points at, whether it is saved as `tool-file:<name>`,
     * a bare name, an absolute path or a `file:` URI. Returns null for anything outside this directory,
     * such as user-owned `content://` documents, so callers never treat those as deletable.
     */
    fun nameOf(reference: String): String? {
        val raw = reference.removePrefix("tool-file:")
        val path = if (raw.startsWith("file:")) {
            runCatching { java.net.URI(raw).path }.getOrNull() ?: return null
        } else raw
        val name = if ('/' in path) {
            val file = File(path)
            val parent = runCatching { file.canonicalFile.parentFile }.getOrNull()
            val temporaryDirectories = synchronized(initialized) {
                temporarySessions[directory.canonicalPath]?.values?.map { it.directory.canonicalFile }.orEmpty()
            }
            if (parent == null || (parent != directory.canonicalFile &&
                    parent != uploadStore?.directory?.canonicalFile && parent !in temporaryDirectories)) return null
            file.name
        } else path
        return name.takeIf { it.matches(Regex("[a-zA-Z0-9._-]+")) && !it.endsWith(".part") && !it.endsWith(".spill") }
    }
    /** Imported copies of external user attachments; the original URI itself is never deletable. */
    fun importedCopyNames(reference: String): Set<String> = index.provenance.importedCopies(reference)

    suspend fun recordImportedAttachment(reference: String, name: String) {
        if (currentCoroutineContext()[TemporaryChatFiles] != null) return
        withContext(Dispatchers.IO) {
            if (nameOf(name) == name && nameOf(reference) != name) {
                index.provenance.recordImport(reference, name)
            }
        }
    }

    /** Generated output remains owned by MediaStorage even after its conversation is deleted. */
    suspend fun markGeneratedMedia(references: Collection<String>) {
        if (currentCoroutineContext()[TemporaryChatFiles] != null) return
        withContext(Dispatchers.IO) {
            index.provenance.markGenerated(references.mapNotNull(::nameOf)
                .filter { File(directory, it).isFile && !isUserUpload(it) })
        }
    }

    fun isGeneratedMedia(name: String): Boolean = index.provenance.isGenerated(name)

    fun generatedMediaNames(): Set<String> = index.provenance.generatedNames()

    fun importedMediaNames(): Map<String, List<String>> = index.provenance.importedNames()

    fun replaceMediaProvenance(generated: Collection<String>, imports: Map<String, List<String>>) {
        index.provenance.replace(generated, imports)
    }

    fun list(): List<File> = directory.listFiles()?.filter {
        it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".spill")
    }?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun delete(names: Collection<String>) {
        synchronized(index) {
            val removed = mutableSetOf<String>()
            names.forEach { name ->
                if (resolve(name)?.delete() == true) {
                    index.hashes.remove(name)
                    // A temporary file can share a name with a permanent file.
                    if (!File(directory, name).isFile) removed += name
                }
            }
            index.provenance.forget(removed)
        }
        revision.update { it + 1 }
    }

    /** Delete only permanent user uploads; do not touch matching temporary-chat cache files. */
    fun deletePermanentUploads(names: Collection<String>) {
        synchronized(index) {
            val removed = mutableSetOf<String>()
            names.forEach { name ->
                if (nameOf(name) != name) return@forEach
                val userUpload = isUserUpload(name)
                if (!userUpload && index.provenance.isGenerated(name)) return@forEach
                val root = if (userUpload) uploadStore!!.directory else directory
                val file = File(root, name)
                if (file.isFile && file.canonicalFile.parentFile == root.canonicalFile && file.delete()) {
                    removed += name
                    index.hashes.remove(name)
                }
            }
            index.provenance.forget(removed)
            if (removed.isNotEmpty()) revision.update { it + 1 }
        }
    }

    fun clearAll() = delete(list().map { it.name })

    /** Restores a previously exported app-private file under its original reference name. */
    suspend fun restore(name: String, input: InputStream, maxBytes: Long = limit) = withContext(Dispatchers.IO) {
        require(name.matches(Regex("[a-zA-Z0-9._-]+")) && !name.endsWith(".part") && !name.endsWith(".spill")) {
            "Invalid stored file name."
        }
        checkSpace()
        val destination = File(directory, name)
        val temporary = File(directory, "$name.restore.part")
        try {
            input.use { source ->
                temporary.outputStream().use { output ->
                    var total = 0L
                    val buffer = ByteArray(32768)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "File exceeds the backup file size limit." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(temporary.renameTo(destination)) { "Could not restore stored file $name." }
            synchronized(index) { index.hashes.remove(name) }
            revision.update { it + 1 }
        } finally {
            temporary.delete()
        }
    }
    private fun checkSpace() { check(directory.usableSpace > 64L * 1024 * 1024) { "Not enough storage. Free space in Storage." } }
    suspend fun save(input: InputStream, mime: String? = null, maxBytes: Long = limit, audioOnly: Boolean = false): String {
        val target = currentTemporaryFiles()
        return if (target != null) target.save(input, mime, maxBytes, audioOnly) else saveInternal(input, mime, maxBytes, audioOnly)
    }

    private suspend fun saveInternal(
        input: InputStream,
        mime: String?,
        maxBytes: Long,
        audioOnly: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        checkSpace()
        val temp = File(directory, "${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { out ->
                val buffer = ByteArray(32768)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if(total % (1024*1024) < n) checkSpace()
                    check(total <= minOf(limit, maxBytes)) { "File exceeds the ${minOf(limit, maxBytes) / (1024 * 1024)} MB limit." }
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                }
            }
            check(temp.length() > 0) { "Empty media response" }
            val header = ByteArray(16)
            temp.inputStream().use { it.read(header) }
            if (audioOnly) require(org.starfall.generative.shared.media.audioMime(header) != null) { "Response is not supported audio." }
            val ext = when {
                header[0] == 0x89.toByte() && header[1] == 0x50.toByte() -> "png"
                header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> "jpg"
                String(header, 0, 3) == "GIF" -> "gif"
                String(header, 8, 4) == "WEBP" -> "webp"
                String(header, 0, 3) == "ID3" -> "mp3"
                String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE" -> "wav"
                String(header, 0, 4) == "OggS" -> "ogg"
                String(header, 0, 4) == "fLaC" -> "flac"
                String(header, 4, 4) == "ftyp" && (String(header, 8, 4).startsWith("M4A") ||
                    mime?.substringBefore(';') in setOf("audio/mp4", "audio/x-m4a")) -> "m4a"
                mime?.substringBefore(';') == "audio/webm" -> "weba"
                mime?.substringBefore(';') in setOf("audio/mpeg", "audio/mp3") -> "mp3"
                mime?.substringBefore(';') in setOf("audio/aac", "audio/aacp") -> "aac"
                header[0] == 0xff.toByte() && (header[1].toInt() and 0xf6) == 0xf0 -> "aac"
                header[0] == 0xff.toByte() && (header[1].toInt() and 0xe0) == 0xe0 -> "mp3"
                String(header, 4, 4) == "ftyp" -> "mp4"
                header[0] == 0x1a.toByte() && header[1] == 0x45.toByte() -> "webm"
                String(header, 0, 4) == "%PDF" -> "pdf"
                mime?.substringBefore(';') == "application/pdf" -> "pdf"
                mime?.substringBefore(';') in setOf("application/zip", "application/x-zip-compressed") -> "zip"
                mime?.substringBefore(';') == "text/csv" -> "csv"
                mime?.substringBefore(';') == "text/html" -> "html"
                mime == "text/plain" -> "txt"
                mime == "application/json" -> "json"
                // Preserve known MIME extensions for shared documents (docx, xlsx, epub, svg,
                // etc.) instead of turning valid attachments into anonymous .bin files.
                else -> mime?.substringBefore(';')?.trim()?.lowercase()?.let {
                    android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
                }
                    ?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "bin"
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val context = coroutineContext
            synchronized(index) {
                context.ensureActive()
                val existingFiles = list().asSequence()
                    .filter { fileNamePrefix.isEmpty() || it.name.startsWith(fileNamePrefix) }
                    .mapNotNull { resolve(it.name) }.toList()
                index.hashes.keys.retainAll(existingFiles.map { it.name }.toSet())
                val existing = existingFiles.firstOrNull { file ->
                    if (file.length() != temp.length()) return@firstOrNull false
                    val cached = index.hashes[file.name]?.takeIf {
                        it.length == file.length() && it.modified == file.lastModified()
                    }
                    val fingerprint = cached ?: try {
                        val fileDigest = MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { source ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                context.ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                fileDigest.update(buffer, 0, count)
                            }
                        }
                        CachedHash(file.length(), file.lastModified(), fileDigest.digest().joinToString("") { "%02x".format(it) })
                            .also { index.hashes[file.name] = it }
                    } catch (_: IOException) { return@firstOrNull false }
                    fingerprint.digest == hash
                }
                if (existing != null && context[ToolFileBatch] == null) existing.name else {
                    context.ensureActive()
                    // Never overwrite an existing referenced file, even if a
                    // hash-named file was externally changed or is a symlink.
                    val canonical = File(directory, "$fileNamePrefix$hash.$ext")
                    val dest = if (canonical.exists()) File(directory, "${UUID.randomUUID()}.$ext") else canonical
                    check(temp.renameTo(dest)) { "Could not save media" }
                    index.hashes[dest.name] = CachedHash(dest.length(), dest.lastModified(), hash)
                    context[ToolFileBatch]?.record(this@ToolFiles, dest.name)
                    revision.update { it + 1 }
                    dest.name
                }
            }
        } finally { input.close(); temp.delete() }
    }
    suspend fun decode(raw: File): String = raw.inputStream().use { source ->
        java.util.Base64.getMimeDecoder().wrap(source).use { save(it) }
    }
    /** Streaming JSON scanner; media payloads are spooled, while text content is preserved in full. */
    suspend fun sanitize(reader: Reader): String {
        val target = currentTemporaryFiles()
        if (target != null) return target.sanitize(reader)
        return sanitizeInternal(reader)
    }

    private suspend fun sanitizeInternal(reader: Reader): String = withContext(Dispatchers.IO) {
        val input = PushbackReader(BufferedReader(reader), 2)
        val output = StringBuilder()
        var lastKey = ""
        while (true) {
            coroutineContext.ensureActive()
            val c = input.read()
            if (c < 0) break
            if (c != '"'.code) {
                output.append(c.toChar())
            } else {
                val value = StringBuilder()
                var raw: File? = null
                var spool: Writer? = null
                var escaped = false
                var length = 0L
                var media = false
                var largeValueClassified = false
                try {
                    while (true) {
                        val n = input.read()
                        check(n >= 0) { "Truncated JSON response" }
                        if (!escaped && n == '"'.code) break
                        length++
                        if (length % 32768L == 0L) coroutineContext.ensureActive()
                        if (raw == null && !largeValueClassified && value.length >= 16384) {
                            largeValueClassified = true
                            media = lastKey in setOf("b64_json", "bytesBase64Encoded", "video_base64", "base64", "blob") ||
                                value.startsWith("data:image/") || value.startsWith("data:video/") || value.startsWith("data:audio/") ||
                                (lastKey == "data" && value.all { it.isLetterOrDigit() || it in "+/=\\" })
                            if (media) {
                                checkSpace()
                                raw = File(directory, "${UUID.randomUUID()}.part")
                                spool = raw.bufferedWriter()
                                spool.write(value.toString())
                                value.setLength(0)
                            }
                        }
                        if (spool != null) spool.write(n)
                        else value.append(n.toChar())
                        escaped = if (escaped) false else n == '\\'.code
                    }
                    spool?.close()
                    var next: Int
                    do { next = input.read() } while (next >= 0 && next.toChar().isWhitespace())
                    if (next >= 0) input.unread(next)
                    if (next == ':'.code) {
                        lastKey = value.toString()
                        output.append('"').append(value).append('"')
                    } else if (raw != null) {
                        val normalized = File(directory, "${UUID.randomUUID()}.part")
                        try {
                            // JSON base64 may escape forward slashes. No media string is materialized.
                            raw.reader().buffered().use { r -> normalized.bufferedWriter().use { w ->
                                var slash = false
                                r.mark(64)
                                val prefix = CharArray(11)
                                val count = r.read(prefix)
                                r.reset()
                                if (count > 0 && String(prefix, 0, count).startsWith("data:")) {
                                    var headerLength = 0
                                    while (r.read() != ','.code) { check(++headerLength < 256) { "Invalid media data URL" } }
                                }
                                var processed = 0
                                while (true) {
                                    if (++processed % 32768 == 0) coroutineContext.ensureActive()
                                    val v = r.read(); if (v < 0) break
                                    if (slash) { if (v != 'n'.code && v != 'r'.code) w.write(v); slash = false }
                                    else if (v == '\\'.code) slash = true else w.write(v)
                                }
                            } }
                            val name = decode(normalized)
                            output.append('"').append("tool-file:").append(name).append('"')
                        } finally { normalized.delete() }
                    } else {
                        // Small base64 media is also saved, including small previews.
                        val text = value.toString()
                        if (text.startsWith("data:image/") || text.startsWith("data:video/") || text.startsWith("data:audio/") || lastKey in setOf("b64_json", "bytesBase64Encoded", "video_base64", "base64", "blob") ||
                            (lastKey == "data" && text.length > 100 && text.matches(Regex("[A-Za-z0-9+/=\\\\]+")))) {
                            val rawSmall = File(directory, "${UUID.randomUUID()}.part")
                            try {
                                rawSmall.writeText((if (text.startsWith("data:")) text.substringAfter(",") else text).replace("\\/", "/"))
                                output.append('"').append("tool-file:").append(decode(rawSmall)).append('"')
                            } finally { rawSmall.delete() }
                        } else {
                            val decoded = kotlinx.serialization.json.Json.parseToJsonElement("\"$text\"")
                                .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                            output.append(kotlinx.serialization.json.JsonPrimitive(decoded).toString())
                        }
                    }
                } finally { spool?.close(); raw?.delete() }
            }
        }
        output.toString()
    }
}
