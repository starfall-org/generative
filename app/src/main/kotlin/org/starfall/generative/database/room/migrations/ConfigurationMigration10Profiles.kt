package org.starfall.generative.database.room.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import org.starfall.generative.shared.model.AiChatConfig

internal object ConfigurationMigration10Profiles {
    fun copy(db: SupportSQLiteDatabase) {
        db.query("SELECT id, name, icon, configJson, activeMcpJson, activeModelToolsJson, sortOrder FROM chat_profiles_v10 ORDER BY rowid").use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val config = migrationValue(db, "profile", id, "config", rows.getString(3)).decode<AiChatConfig>()
                val activeMcp = migrationValue(db, "profile", id, "active_mcp", rows.getString(4)).decode<List<String>>()
                val activeModelTools = migrationValue(db, "profile", id, "active_model_tools", rows.getString(5)).decode<List<String>>()
                db.execSQL("INSERT INTO chat_profiles(id, name, icon, sortOrder) VALUES(?, ?, ?, ?)",
                    migrationArgs(id, rows.getString(1), rows.migrationString(2), rows.getLong(6)))
                db.execSQL("INSERT INTO chat_profile_configs(profileId, systemPrompt) VALUES(?, ?)", migrationArgs(id, config.systemPrompt))
                config.mcpAccess.entries.forEachIndexed { ordinal, (serverId, access) ->
                    db.execSQL("INSERT INTO chat_profile_mcp_access(profileId, ordinal, serverId, enabled) VALUES(?, ?, ?, ?)",
                        migrationArgs(id, ordinal, serverId, migrationBool(access.enabled)))
                    access.tools.entries.forEachIndexed { toolOrdinal, (toolName, enabled) ->
                        db.execSQL("INSERT INTO chat_profile_mcp_tools(profileId, serverId, ordinal, toolName, enabled) VALUES(?, ?, ?, ?, ?)",
                            migrationArgs(id, serverId, toolOrdinal, toolName, migrationBool(enabled)))
                    }
                }
                activeMcp.forEachIndexed { ordinal, value ->
                    db.execSQL("INSERT INTO chat_profile_active_mcp(profileId, ordinal, serverId) VALUES(?, ?, ?)", migrationArgs(id, ordinal, value))
                }
                activeModelTools.forEachIndexed { ordinal, value ->
                    db.execSQL("INSERT INTO chat_profile_active_model_tools(profileId, ordinal, toolName) VALUES(?, ?, ?)", migrationArgs(id, ordinal, value))
                }
                migrationCheckCount(db, "chat_profile_mcp_access", config.mcpAccess.size, "profileId", id)
                migrationCheckCount(db, "chat_profile_mcp_tools", config.mcpAccess.values.sumOf { it.tools.size }, "profileId", id)
                migrationCheckCount(db, "chat_profile_active_mcp", activeMcp.size, "profileId", id)
                migrationCheckCount(db, "chat_profile_active_model_tools", activeModelTools.size, "profileId", id)
            }
        }
    }
}
