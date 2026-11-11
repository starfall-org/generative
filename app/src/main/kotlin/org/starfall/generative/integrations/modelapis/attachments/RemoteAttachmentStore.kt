package org.starfall.generative.integrations.modelapis.attachments

/** Only confirmed, usable remote references belong in durable storage. No credentials are stored. */
data class RemoteAttachmentRecord(
    val key: String,
    val localReference: String,
    val remoteId: String,
    val remoteName: String = remoteId,
    val expiresAt: Long? = null
)

interface RemoteAttachmentStore {
    suspend fun get(key: String): RemoteAttachmentRecord?
    suspend fun put(record: RemoteAttachmentRecord)
    suspend fun remove(key: String)
    suspend fun prune(missing: (String) -> Boolean, now: Long)
}

internal class MemoryRemoteAttachmentStore : RemoteAttachmentStore {
    private val records = java.util.concurrent.ConcurrentHashMap<String, RemoteAttachmentRecord>()
    override suspend fun get(key: String) = records[key]
    override suspend fun put(record: RemoteAttachmentRecord) { records[record.key] = record }
    override suspend fun remove(key: String) { records.remove(key) }
    override suspend fun prune(missing: (String) -> Boolean, now: Long) {
        records.entries.removeIf { (_, value) -> missing(value.localReference) || (value.expiresAt?.let { it <= now } == true) }
    }
}
