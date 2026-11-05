package org.starfall.generative.database.room.migrations

import androidx.sqlite.db.SupportSQLiteDatabase

/** Converts released v9 configuration aggregates to final v10, retaining unrepresented data encrypted. */
internal object ConfigurationMigration9To10 {
    private val sources = listOf("chat_profiles", "model_providers", "provider_models", "mcp_servers", "speech_services")

    fun migrate(db: SupportSQLiteDatabase) {
        // Room already owns a transaction. Also make direct migration callers rollback-safe.
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            val counts = sources.associateWith { migrationCount(db, it) }
            sources.forEach { db.execSQL("ALTER TABLE $it RENAME TO ${it}_v10") }
            ConfigurationMigration10Schema.create(db)
            ConfigurationMigration10Profiles.copy(db)
            ConfigurationMigration10Providers.copy(db)
            ConfigurationMigration10Catalogs.copy(db)
            ConfigurationMigration10Mcp.copy(db)
            ConfigurationMigration10Speech.copy(db)
            verifySources(db, counts)
            checkForeignKeys(db) // Validate while every original cell is still available.
            sources.forEach { db.execSQL("DROP TABLE ${it}_v10") }
            checkForeignKeys(db)
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun verifySources(db: SupportSQLiteDatabase, counts: Map<String, Long>) {
        sources.forEach { table ->
            check(migrationCount(db, "${table}_v10") == counts.getValue(table)) {
                "Configuration v9 to v10 migration source row count changed in $table."
            }
            if (table != "provider_models") {
                check(migrationCount(db, table) == counts.getValue(table)) {
                    "Configuration v9 to v10 migration parent row count mismatch in $table."
                }
            }
        }
        verifyScalars(db, "chat_profiles", "id, name, icon, sortOrder")
        verifyScalars(db, "model_providers", "id, name, type, icon, sortOrder, groupId")
        verifyScalars(db, "mcp_servers", "id, name, protocol, sortOrder")
        verifyScalars(db, "speech_services", "id, name, provider, modelId, voice, speed, pitch, sortOrder")
        check(migrationCount(db, "chat_profile_configs") == counts.getValue("chat_profiles")) {
            "Configuration v9 to v10 migration profile config row count mismatch."
        }
        check(migrationCount(db, "mcp_tool_caches") == counts.getValue("mcp_servers")) {
            "Configuration v9 to v10 migration MCP cache row count mismatch."
        }
        db.query("""
            SELECT COUNT(*) FROM provider_models_v10 AS source
            WHERE EXISTS (
                SELECT 1 FROM structured_json_documents AS doc
                WHERE doc.ownerType = 'provider_catalog' AND doc.ownerId = source.id AND doc.fieldName = 'cache_state'
            ) OR EXISTS (
                SELECT 1 FROM configuration_migration_quarantine AS issue
                WHERE issue.ownerType = 'provider_catalog' AND issue.ownerId = source.id AND issue.fieldName = 'models'
                  AND issue.rawValue IS NOT NULL AND issue.errorCode IN ('orphan_catalog', 'unreadable_cache')
            )
        """.trimIndent()).use {
            check(it.moveToFirst() && it.getLong(0) == counts.getValue("provider_models")) {
                "Configuration v9 to v10 migration did not preserve every source catalog."
            }
        }
    }

    private fun verifyScalars(db: SupportSQLiteDatabase, table: String, columns: String) {
        db.query("SELECT $columns FROM ${table}_v10 EXCEPT SELECT $columns FROM $table").use {
            check(!it.moveToFirst()) { "Configuration v9 to v10 migration changed legacy scalar values in $table." }
        }
    }

    private fun checkForeignKeys(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use {
            check(!it.moveToFirst()) { "Configuration v9 to v10 migration produced a foreign-key violation." }
        }
    }
}
