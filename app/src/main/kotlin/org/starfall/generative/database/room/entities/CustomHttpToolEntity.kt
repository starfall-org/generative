package org.starfall.generative.database.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Encrypt the entire configuration: endpoints, literals and templates can contain credentials. */
@Entity(tableName = "custom_http_tools")
data class CustomHttpToolEntity(@PrimaryKey val id: String, val encryptedPayload: String)
