package org.starfall.generative.integrations.modelapis.attachments

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver

/** Network work never runs in a database transaction. Striped locks bound single-flight bookkeeping. */
internal class AttachmentPreflight(
    private val source: AttachmentResolver,
    private val uploader: AttachmentUploader,
    private val store: RemoteAttachmentStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val messages: AttachmentMessages = AttachmentMessages(),
    immutableRoot: File? = null
) {
    private val immutableRoot = immutableRoot?.canonicalFile
    private val locks = Array(64) { Mutex() }
    private val unsupportedUntil = ConcurrentHashMap<String, Long>()
    private val hashes = object : LinkedHashMap<String, Pair<String, String>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>) = size > 128
    }
    private val cleanup = Mutex()

    suspend fun prepare(provider: ModelProvider, model: ModelConfiguration, references: List<String>): Map<String, PreparedAttachment> =
        withContext(Dispatchers.IO) {
            cleanup.withLock {
                store.prune({ false }, clock())
            }
            unsupportedUntil.entries.removeIf { it.value <= clock() }
            val scope = uploader.scope(provider)
            val prepared = linkedMapOf<String, PreparedAttachment>()
            for (reference in references.distinct()) {
                currentCoroutineContext().ensureActive()
                val file = source.metadata(reference) ?: error(messages.missing(reference.substringAfterLast('/')))
                require(!file.isImage || model.supportsVision) { messages.unsupported(file.name) }
                require(!file.isVideo || model.supportsVideoInput) { messages.unsupported(file.name) }
                require(!file.mimeType.startsWith("audio/") || model.supportsAudioInput) { messages.unsupported(file.name) }
                val capability = uploader.capability(provider, file)
                require(capability.remote || capability.inline) { messages.unsupported("${file.name} (${file.mimeType})") }
                require(file.sizeBytes <= capability.maxBytes) { messages.tooLarge(file.name) }
                val digest = fingerprint(reference, capability.maxBytes)
                if (!capability.remote) {
                    prepared[reference] = PreparedAttachment(file)
                    continue
                }
                val key = attachmentHash(listOf(scope, digest, file.mimeType, capability.purpose))
                prepared[reference] = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                    val cached = store.get(key)
                    if (cached != null) {
                        if ((cached.expiresAt == null || cached.expiresAt > clock() + 60_000) && uploader.exists(provider, cached)) {
                            val reusable = cached.copy(localReference = reference)
                            if (reusable != cached) store.put(reusable)
                            return@withLock PreparedAttachment(file, reusable)
                        }
                        store.remove(key)
                    }
                    if ((unsupportedUntil[scope] ?: 0) > clock() && capability.inline) {
                        fingerprint(reference, INLINE_LIMIT)
                        return@withLock PreparedAttachment(file)
                    }
                    try {
                        val remote = uploader.upload(provider, file, key)
                        currentCoroutineContext().ensureActive()
                        require(remote.remoteId.isNotBlank() && (remote.expiresAt == null || remote.expiresAt > clock())) {
                            messages.processing(file.name)
                        }
                        store.put(remote)
                        PreparedAttachment(file, remote)
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (!capability.inline || !uploader.isUnsupportedEndpoint(error)) throw error
                        unsupportedUntil[scope] = clock() + 15 * 60_000L
                        fingerprint(reference, INLINE_LIMIT)
                        PreparedAttachment(file)
                    }
                }
            }
            cleanup.withLock { store.prune({ source.metadata(it) == null }, clock()) }
            prepared
        }

    suspend fun invalidate(prepared: Map<String, PreparedAttachment>) {
        for (remote in prepared.values.mapNotNull { it.remote }.distinctBy { it.key }) {
            locks[(remote.key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                // A different request may already have repaired this mapping while this one failed.
                if (store.get(remote.key)?.remoteId == remote.remoteId) store.remove(remote.key)
            }
        }
    }

    private suspend fun fingerprint(reference: String, limit: Long): String {
        // Only app-owned snapshots are immutable. Other paths/content URIs must be rehashed.
        val local = File(reference)
        val owned = immutableRoot?.let { root ->
            local.isFile && local.canonicalPath.startsWith(root.path + File.separator)
        } == true
        val stamp = if (owned) "${local.length()}:${local.lastModified()}" else null
        if (stamp != null && local.length() <= limit) synchronized(hashes) { hashes[reference]?.takeIf { it.first == stamp }?.second }?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        source.open(reference)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { messages.tooLarge(local.name) }
                digest.update(buffer, 0, count)
            }
        } ?: error(messages.missing(local.name))
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (stamp != null) synchronized(hashes) { hashes[reference] = stamp to hash }
        return hash
    }

    companion object { const val INLINE_LIMIT = 30L * 1024 * 1024 }
}

internal fun attachmentHash(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
