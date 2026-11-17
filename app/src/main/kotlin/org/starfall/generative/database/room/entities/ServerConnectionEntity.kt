package org.starfall.generative.database.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Credentials remain encrypted with the device's non-exportable Keystore key. */
@Entity(tableName = "server_connections")
data class ServerConnectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val endpoint: String,
    val pin: String,
    val clientId: String,
    val serverId: String,
    val encryptedSecret: String,
    val selected: Boolean,
)
