package org.starfall.generative.database.room.entities

import androidx.room.Entity

/** The payload is encrypted by its OAuth integration before reaching Room. */
@Entity(tableName = "oauth_token_records", primaryKeys = ["namespace", "recordId"])
data class OAuthTokenRecordEntity(
    val namespace: String,
    val recordId: String,
    val encryptedPayload: String
)
