package org.starfall.generative.integrations.serverside

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.integrations.serverside.ServerSideCodec
import org.starfall.generative.integrations.serverside.ServerSnapshot
import org.starfall.generative.integrations.serverside.SessionListItem

/** Never use an Android chat ID alone: each pairing is an independent server owner. */
data class RemoteCacheScope(val profileId: String, val clientId: String, val serverId: String, val pin: String)

/**
 * Evictable, encrypted, private mirror of server-authoritative chat snapshots.
 * No Room entries, local chat exports, credentials, or unconfirmed stream deltas.
 */
class RemoteConversationCache(
    private val root: File,
    private val encrypt: (String) -> String = SecretCipher::encrypt,
    private val decrypt: (String) -> String = SecretCipher::decryptStrict,
) {
    private val mutex = Mutex()
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun profile(scope: RemoteCacheScope) = File(root, hash(scope.profileId))
    private fun owner(scope: RemoteCacheScope) = File(profile(scope), hash("${scope.clientId}\u0000${scope.serverId}\u0000${scope.pin}"))
    private fun record(scope: RemoteCacheScope, id: String) = AtomicFile(File(owner(scope), "${hash(id)}.cache"))

    @Serializable private data class CachedSnapshot(val version: Int, val sessionId: String, val revision: Long, val conversation: JsonObject)
    @Serializable private data class CachedList(val version: Int, val items: List<SessionListItem>)

    private fun read(file: AtomicFile): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return try {
            // AtomicFile may restore .bak after a crash. Bound both paths before reading.
            val backup = File(file.baseFile.path + ".bak")
            if (file.baseFile.length() > MAX_ENTRY_BYTES || backup.length() > MAX_ENTRY_BYTES) {
                file.delete(); return null
            }
            val data = file.openRead().use { it.readBytes() }
            if (data.size > MAX_ENTRY_BYTES) { file.delete(); null }
            else decrypt(String(data, Charsets.UTF_8))
        } catch (_: Exception) { file.delete(); null }
    }
    private fun write(file: AtomicFile, text: String) {
        val payload = encrypt(text).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_ENTRY_BYTES) return
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try { stream.write(payload); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        trim()
    }
    private fun trim() {
        val entries = root.walkTopDown().filter { it.isFile && (it.name.endsWith(".cache") || it.name.endsWith(".cache.bak") || it.name.endsWith(".cache.new")) }
            .sortedBy { it.lastModified() }.toList()
        var total = entries.sumOf { it.length() }
        for (entry in entries) {
            if (total <= MAX_TOTAL_BYTES) break
            total -= entry.length()
            entry.delete()
        }
    }
    suspend fun snapshot(scope: RemoteCacheScope, id: String): ServerSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = record(scope, id)
            try {
                val value = ServerSideCodec.json.decodeFromString<CachedSnapshot>(read(file) ?: return@withLock null)
                if (value.version != 1 || value.sessionId != id || value.revision <= 0 ||
                    value.conversation["id"]?.jsonPrimitive?.content != id) {
                    file.delete(); null
                } else {
                    // A cache produced by an older app must not make the chat unavailable.
                    ServerSideCodec.json.decodeFromString<Conversation>(value.conversation.toString())
                    ServerSnapshot(id, value.revision, value.conversation)
                }
            } catch (_: Exception) { file.delete(); null }
        }
    }
    suspend fun save(scope: RemoteCacheScope, value: ServerSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = record(scope, value.sessionId)
            // An earlier async stream checkpoint may arrive after a later one.
            val previous = try { ServerSideCodec.json.decodeFromString<CachedSnapshot>(read(file).orEmpty()) } catch (_: Exception) { null }
            if (value.revision > 0 && value.conversation["id"]?.jsonPrimitive?.content == value.sessionId &&
                (previous == null || previous.revision <= value.revision)) {
                write(file, ServerSideCodec.json.encodeToString(CachedSnapshot(1, value.sessionId, value.revision, value.conversation)))
            }
        }
    }
    suspend fun list(scope: RemoteCacheScope): List<SessionListItem>? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val cached = ServerSideCodec.json.decodeFromString<CachedList>(read(record(scope, LIST_KEY)) ?: return@withLock null)
                cached.items.takeIf { cached.version == 1 }
            } catch (_: Exception) { record(scope, LIST_KEY).delete(); null }
        }
    }
    suspend fun saveList(scope: RemoteCacheScope, items: List<SessionListItem>) = withContext(Dispatchers.IO) {
        mutex.withLock { write(record(scope, LIST_KEY), ServerSideCodec.json.encodeToString(CachedList(1, items))) }
    }
    suspend fun remove(scope: RemoteCacheScope, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { record(scope, id).delete() }
    }
    suspend fun prune(scope: RemoteCacheScope, ids: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val keep = ids.mapTo(mutableSetOf()) { "${hash(it)}.cache" } + "${hash(LIST_KEY)}.cache"
            owner(scope).listFiles().orEmpty().filter { file ->
                file.isFile && (file.name.endsWith(".cache") || file.name.endsWith(".cache.bak") || file.name.endsWith(".cache.new")) &&
                    file.name.removeSuffix(".bak").removeSuffix(".new") !in keep
            }.forEach { it.delete() }
        }
    }
    suspend fun removeProfile(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { File(root, hash(id)).deleteRecursively() }
    }

    private companion object {
        const val LIST_KEY = "__remote_chat_list__"
        const val MAX_ENTRY_BYTES = 12L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    }
}
