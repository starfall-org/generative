package org.starfall.generative.database.room.migrations

import org.starfall.generative.integrations.security.SecretCipher

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@OptIn(ExperimentalSerializationApi::class)
internal val configurationMigrationJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // Older exports used enum names as well as the lowercase @SerialName spellings.
    decodeEnumsCaseInsensitive = true
}

internal fun migrationArgs(vararg values: Any?): Array<out Any?> = values
internal fun migrationBool(value: Boolean): Int = if (value) 1 else 0
internal fun Cursor.migrationString(index: Int): String? = if (isNull(index)) null else getString(index)

/** A source cell, with recoverable originals retained only in Keystore envelopes. */
internal class ConfigurationMigration10Value(
    val db: SupportSQLiteDatabase,
    val ownerType: String,
    val ownerId: String,
    val field: String,
    val raw: String
) {
    private var retained = false

    fun clear(): String = try {
        SecretCipher.decrypt(raw)
    } catch (_: Exception) {
        // Do not attach cipher/JSON exceptions: their messages can contain source credentials.
        error("Configuration v9 to v10 migration cannot decrypt required $ownerType.$field; transaction must roll back.")
    }

    inline fun <reified T> decode(): T {
        val value = parse()
        val decoded = try {
            configurationMigrationJson.decodeFromJsonElement<T>(value)
        } catch (_: Exception) {
            invalid()
        }
        retainUnrepresented(value, configurationMigrationJson.encodeToJsonElement(decoded))
        return decoded
    }

    fun parse(): JsonElement = try {
        configurationMigrationJson.parseToJsonElement(clear())
    } catch (_: kotlinx.serialization.SerializationException) {
        invalid()
    } catch (_: IllegalArgumentException) {
        invalid()
    }

    fun invalid(): Nothing = error(
        "Configuration v9 to v10 migration cannot read required $ownerType.$field; transaction must roll back."
    )

    fun retainUnrepresented(original: JsonElement, represented: JsonElement) {
        if (!represented.containsOriginal(original)) retain("unrepresented_json")
    }

    fun retain(code: String) {
        if (retained) return
        // Preserve existing ciphertext verbatim, including unreadable envelopes. Never use a
        // plaintext fallback if encryption is unavailable: the entire migration must fail.
        val encrypted = if (SecretCipher.isEncrypted(raw)) raw else SecretCipher.encrypt(raw)
        db.execSQL("""
            INSERT INTO configuration_migration_quarantine(ownerType, ownerId, fieldName, rawValue, errorCode)
            VALUES(?, ?, ?, ?, ?)
        """.trimIndent(), migrationArgs(ownerType, ownerId, field, encrypted, code))
        retained = true
    }

    /** Optional caches may be unavailable, but their exact source is always recoverable. */
    inline fun <reified T> cache(): T? {
        val decoded = try {
            configurationMigrationJson.decodeFromString<T>(SecretCipher.decrypt(raw))
        } catch (_: Exception) {
            retain("unreadable_cache")
            return null
        }
        retainUnrepresented(parse(), configurationMigrationJson.encodeToJsonElement(decoded))
        return decoded
    }
}

/** Includes nested unknown fields, scalar coercions and null/empty distinctions. */
private fun JsonElement.containsOriginal(original: JsonElement): Boolean = when {
    this is JsonObject && original is JsonObject -> original.all { (key, child) ->
        get(key)?.containsOriginal(child) == true
    }
    this is JsonArray && original is JsonArray -> size == original.size && indices.all {
        get(it).containsOriginal(original[it])
    }
    else -> this == original
}

internal fun migrationValue(
    db: SupportSQLiteDatabase, ownerType: String, ownerId: String, field: String, raw: String
) = ConfigurationMigration10Value(db, ownerType, ownerId, field, raw)

internal fun migrationStructured(
    db: SupportSQLiteDatabase, ownerType: String, ownerId: String, field: String, value: JsonElement
) {
    // Random IDs avoid delimiter collisions in arbitrary legacy owner IDs/model IDs.
    val documentId = UUID.randomUUID().toString()
    db.execSQL("INSERT INTO structured_json_documents(id, ownerType, ownerId, fieldName) VALUES(?, ?, ?, ?)",
        migrationArgs(documentId, ownerType, ownerId, field))
    var nextId = 0
    fun visit(element: JsonElement, parent: Int?, ordinal: Int, objectKey: String?) {
        val nodeId = nextId++
        val (kind, scalar) = when (element) {
            JsonNull -> "null" to null
            is JsonObject -> "object" to null
            is JsonArray -> "array" to null
            is JsonPrimitive -> if (element.isString) "string" to element.content else {
                (if (element.content == "true" || element.content == "false") "boolean" else "number") to element.content
            }
        }
        db.execSQL("""
            INSERT INTO structured_json_nodes(documentId, nodeId, parentNodeId, ordinal, objectKey, nodeKind, scalarValue)
            VALUES(?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(), migrationArgs(documentId, nodeId, parent, ordinal, objectKey, kind, scalar))
        when (element) {
            is JsonObject -> element.entries.forEachIndexed { index, (key, child) -> visit(child, nodeId, index, key) }
            is JsonArray -> element.forEachIndexed { index, child -> visit(child, nodeId, index, null) }
            else -> Unit
        }
    }
    visit(value, null, 0, null)
}

internal inline fun <reified T> migrationEncryptedPayload(value: T): String =
    SecretCipher.encrypt(configurationMigrationJson.encodeToString(value))

internal fun migrationCount(db: SupportSQLiteDatabase, table: String): Long =
    db.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); it.getLong(0) }

internal fun migrationCheckCount(db: SupportSQLiteDatabase, table: String, expected: Int, key: String, id: String) {
    db.query("SELECT COUNT(*) FROM $table WHERE $key = ?", arrayOf(id)).use {
        check(it.moveToFirst() && it.getLong(0) == expected.toLong()) { "Configuration v9 to v10 migration child row count mismatch in $table." }
    }
}
