package org.starfall.generative.database.room.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.starfall.generative.shared.model.McpAuthorization
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.database.repositories.EndpointSecrets
import org.starfall.generative.database.repositories.McpAuthPayload
import org.starfall.generative.database.repositories.hasSecrets

internal object ConfigurationMigration10Mcp {
    @Serializable
    private data class LegacyHttpConfig(
        val headers: Map<String, String>? = null,
        val auth: McpAuthorization = McpAuthorization(),
        val icon: String? = null
    )

    fun copy(db: SupportSQLiteDatabase) {
        db.query("SELECT id, name, protocol, url, headersJson, cachedToolsJson, sortOrder FROM mcp_servers_v10 ORDER BY rowid").use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val endpoint = EndpointSecrets.split(rows.migrationString(3)?.let { migrationValue(db, "mcp", id, "url", it).clear() })
                val source = rows.migrationString(4)?.let { migrationValue(db, "mcp", id, "headers", it) }
                val config = source?.let(::httpConfig) ?: LegacyHttpConfig()
                val payload = McpAuthPayload(config.headers.orEmpty(), config.auth, endpoint.privateUrl)
                val authId = if (payload.hasSecrets()) "mcp-auth-$id" else null
                if (authId != null) {
                    db.execSQL("""
                        INSERT INTO mcp_auth(id, encryptedPayload, payloadVersion, cipherVersion, state, updatedAt)
                        VALUES(?, ?, ?, ?, ?, ?)
                    """.trimIndent(), migrationArgs(authId, migrationEncryptedPayload(payload), 2, 1, "READY", System.currentTimeMillis()))
                } else if (config.auth != McpAuthorization(method = config.auth.method)) {
                    source?.retain("unrepresented_empty_auth")
                }
                if (config.headers?.isEmpty() == true) source?.retain("unrepresented_empty_headers")
                migrationStructured(db, "mcp", id, "auth_method", JsonPrimitive(config.auth.method.name))
                val cacheRaw = rows.migrationString(5)
                val tools = cacheRaw?.let { migrationValue(db, "mcp", id, "cachedTools", it).cache<List<ToolDefinition>>() }
                val state = when {
                    cacheRaw == null -> "NO_CACHE"
                    tools == null -> "ERROR"
                    else -> "READY"
                }
                db.execSQL("""
                    INSERT INTO mcp_servers(id, name, protocol, url, icon, sortOrder, authId, cacheState)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(), migrationArgs(id, rows.getString(1), rows.getString(2), endpoint.publicUrl,
                    config.icon, rows.getLong(6), authId, state))
                db.execSQL("INSERT INTO mcp_tool_caches(serverId, state) VALUES(?, ?)", migrationArgs(id, state))
                tools.orEmpty().forEachIndexed { ordinal, tool ->
                    db.execSQL("""
                        INSERT INTO mcp_cached_tools(serverId, ordinal, name, description, toolServerId, originalName, remoteMachineId)
                        VALUES(?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(), migrationArgs(id, ordinal, tool.name, tool.description, tool.serverId, tool.originalName, tool.remoteMachineId))
                    migrationStructured(db, "mcp_tool", "$id/$ordinal", "schema", tool.schema)
                }
                migrationCheckCount(db, "mcp_cached_tools", tools.orEmpty().size, "serverId", id)
            }
        }
    }

    private fun httpConfig(source: ConfigurationMigration10Value): LegacyHttpConfig {
        val raw = source.clear()
        if (raw.isEmpty()) {
            source.retain("unrepresented_empty_headers")
            return LegacyHttpConfig()
        }
        val value = try {
            configurationMigrationJson.parseToJsonElement(raw)
        } catch (_: kotlinx.serialization.SerializationException) {
            if (raw.trimStart().firstOrNull() in listOf('{', '[', '"')) source.invalid()
            return LegacyHttpConfig(headers = mapOf("Authorization" to raw))
        }
        if (value is JsonObject) {
            // Inspect the shape BEFORE decoding an all-default composite. In particular,
            // header names "auth", "headers" and "icon" may legitimately have string values.
            if (value.values.all { it is JsonPrimitive && it.isString }) {
                return LegacyHttpConfig(headers = configurationMigrationJson.decodeFromJsonElement<Map<String, String>>(value))
            }
            return source.decode<LegacyHttpConfig>()
        }
        if (value is JsonPrimitive) {
            // parseToJsonElement can also accept an unquoted legacy token as a primitive.
            return LegacyHttpConfig(headers = mapOf("Authorization" to if (value.isString) value.content else raw))
        }
        source.invalid()
    }
}
