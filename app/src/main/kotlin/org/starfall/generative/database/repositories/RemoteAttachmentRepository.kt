package org.starfall.generative.database.repositories

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.starfall.generative.database.room.dao.RemoteAttachmentDao
import org.starfall.generative.database.room.entities.RemoteAttachmentEntity
import org.starfall.generative.integrations.modelapis.attachments.RemoteAttachmentRecord
import org.starfall.generative.integrations.modelapis.attachments.RemoteAttachmentStore

/** One initial Room read; explicit operations own the RAM catalog. No invalidation observers. */
class RemoteAttachmentRepository(private val dao: RemoteAttachmentDao) : RemoteAttachmentStore {
    private val lock = Mutex()
    private var records: MutableMap<String, RemoteAttachmentRecord>? = null

    private suspend fun records(): MutableMap<String, RemoteAttachmentRecord> = records ?: dao.all().associate { row ->
        row.key to RemoteAttachmentRecord(row.key, row.localReference, row.remoteId, row.remoteName, row.expiresAt)
    }.toMutableMap().also { records = it }

    override suspend fun get(key: String) = lock.withLock { records()[key] }
    override suspend fun put(record: RemoteAttachmentRecord) = lock.withLock {
        val catalog = records()
        dao.put(RemoteAttachmentEntity(record.key, record.localReference, record.remoteId, record.remoteName, record.expiresAt))
        catalog[record.key] = record
    }
    override suspend fun remove(key: String) = lock.withLock {
        val catalog = records()
        dao.remove(key)
        catalog.remove(key)
        Unit
    }
    override suspend fun prune(missing: (String) -> Boolean, now: Long) = lock.withLock {
        val catalog = records()
        val obsolete = catalog.values.filter { missing(it.localReference) || (it.expiresAt?.let { expiry -> expiry <= now } == true) }
        obsolete.forEach { dao.remove(it.key); catalog.remove(it.key) }
    }
}
