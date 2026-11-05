package org.starfall.generative.database.room.migrations

import org.starfall.generative.integrations.security.SecretCipher

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

internal object ConfigurationMigration10Speech {
    @Serializable
    private data class LegacyOptions(
        val icon: String? = null,
        val instructions: String? = null,
        val responseFormat: String? = null,
        val languageCode: String? = null,
        val extraBody: JsonObject = JsonObject(emptyMap())
    )

    fun copy(db: SupportSQLiteDatabase) {
        db.query("SELECT id, name, provider, modelId, voice, speed, pitch, apiKey, sortOrder, optionsJson FROM speech_services_v10 ORDER BY rowid").use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val source = rows.migrationString(9)?.let { migrationValue(db, "speech", id, "options", it) }
                val options = source?.decode<LegacyOptions>() ?: LegacyOptions()
                val normalized = options.copy(instructions = options.instructions ?: "",
                    responseFormat = options.responseFormat ?: "mp3", languageCode = options.languageCode ?: "")
                source?.retainUnrepresented(source.parse(), configurationMigrationJson.encodeToJsonElement(normalized))
                val apiKey = migrationValue(db, "speech", id, "apiKey", rows.getString(7)).clear()
                val authId = apiKey.takeIf { it.isNotEmpty() }?.let { "speech-auth-$id" }
                if (authId != null) {
                    db.execSQL("INSERT INTO speech_auth(id, encryptedApiKey, cipherVersion, updatedAt) VALUES(?, ?, ?, ?)",
                        migrationArgs(authId, SecretCipher.encrypt(apiKey), 1, System.currentTimeMillis()))
                }
                db.execSQL("""
                    INSERT INTO speech_services(id, name, provider, modelId, voice, speed, pitch, sortOrder, icon,
                        instructions, responseFormat, languageCode, authId)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(), migrationArgs(id, rows.getString(1), rows.getString(2), rows.migrationString(3), rows.getString(4),
                    rows.getDouble(5), rows.getDouble(6), rows.getLong(8), normalized.icon,
                    normalized.instructions, normalized.responseFormat, normalized.languageCode, authId))
                if (options.extraBody.isNotEmpty()) migrationStructured(db, "speech_service", id, "extra_body", options.extraBody)
            }
        }
    }
}
