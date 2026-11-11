package org.starfall.generative.database.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.starfall.generative.database.room.entities.RemoteAttachmentEntity

@Dao
interface RemoteAttachmentDao {
    @Query("SELECT * FROM remote_attachments")
    suspend fun all(): List<RemoteAttachmentEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: RemoteAttachmentEntity)
    @Query("DELETE FROM remote_attachments WHERE `key` = :key")
    suspend fun remove(key: String)
}
