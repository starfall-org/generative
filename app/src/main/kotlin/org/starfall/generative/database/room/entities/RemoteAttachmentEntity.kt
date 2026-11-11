package org.starfall.generative.database.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Disposable cache, excluded from the application export format. */
@Entity(tableName = "remote_attachments")
data class RemoteAttachmentEntity(
    @PrimaryKey val key: String,
    val localReference: String,
    val remoteId: String,
    val remoteName: String,
    val expiresAt: Long?
)
