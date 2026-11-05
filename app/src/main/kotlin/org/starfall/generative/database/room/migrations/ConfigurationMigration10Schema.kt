package org.starfall.generative.database.room.migrations

import androidx.sqlite.db.SupportSQLiteDatabase

/** DDL for the configuration portion of final v10; do not add migration-only indices. */
internal object ConfigurationMigration10Schema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE provider_auth (
                id TEXT NOT NULL PRIMARY KEY, encryptedPayload TEXT NOT NULL,
                payloadVersion INTEGER NOT NULL, cipherVersion INTEGER NOT NULL,
                state TEXT NOT NULL, updatedAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_provider_auth_updatedAt ON provider_auth(updatedAt)")
        db.execSQL("""
            CREATE TABLE model_providers (
                id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL,
                baseUrl TEXT NOT NULL, icon TEXT, sortOrder INTEGER NOT NULL, groupId TEXT, authId TEXT,
                supportStream INTEGER NOT NULL, responsesApi INTEGER NOT NULL, customListModelsUrl TEXT,
                maxTokens INTEGER NOT NULL, multipleApiKeys INTEGER NOT NULL, modelSelectionMode TEXT NOT NULL,
                FOREIGN KEY(authId) REFERENCES provider_auth(id) ON UPDATE CASCADE ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_model_providers_authId ON model_providers(authId)")
        db.execSQL("""
            CREATE TABLE provider_model_selection (
                providerId TEXT NOT NULL, ordinal INTEGER NOT NULL, modelId TEXT NOT NULL,
                PRIMARY KEY(providerId, ordinal),
                FOREIGN KEY(providerId) REFERENCES model_providers(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_provider_model_selection_model ON provider_model_selection(providerId, modelId)")
        db.execSQL("""
            CREATE TABLE provider_model_configurations (
                providerId TEXT NOT NULL, modelId TEXT NOT NULL, ordinal INTEGER NOT NULL,
                temperature REAL, topP REAL, topK INTEGER, supportStream INTEGER,
                displayName TEXT NOT NULL, contextWindowTokens INTEGER NOT NULL, modelType TEXT NOT NULL,
                supportsVision INTEGER NOT NULL, supportsVideoInput INTEGER NOT NULL,
                supportsAudioInput INTEGER NOT NULL, supportsThinking INTEGER NOT NULL,
                reasoningEffort TEXT, thinkingLevel TEXT, supportsToolCalls INTEGER NOT NULL,
                legacySendThinkingContent INTEGER, thinkingPassback TEXT, icon TEXT,
                PRIMARY KEY(providerId, modelId),
                FOREIGN KEY(providerId) REFERENCES model_providers(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_provider_model_configurations_order ON provider_model_configurations(providerId, ordinal)")
        db.execSQL("""
            CREATE TABLE provider_model_values (
                providerId TEXT NOT NULL, modelId TEXT NOT NULL, listKind TEXT NOT NULL,
                ordinal INTEGER NOT NULL, value TEXT NOT NULL,
                PRIMARY KEY(providerId, modelId, listKind, ordinal),
                FOREIGN KEY(providerId, modelId) REFERENCES provider_model_configurations(providerId, modelId) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_provider_model_values_list ON provider_model_values(providerId, modelId, listKind)")
        db.execSQL("""
            CREATE TABLE provider_catalog_models (
                providerId TEXT NOT NULL, ordinal INTEGER NOT NULL, modelId TEXT NOT NULL,
                displayName TEXT NOT NULL, icon TEXT, catalogProviderId TEXT NOT NULL,
                inputText INTEGER NOT NULL, inputImage INTEGER NOT NULL, inputVideo INTEGER NOT NULL,
                inputEmbed INTEGER NOT NULL, inputAudio INTEGER NOT NULL, inputOthers TEXT,
                outputText INTEGER NOT NULL, outputImage INTEGER NOT NULL, outputVideo INTEGER NOT NULL,
                outputEmbed INTEGER NOT NULL, outputAudio INTEGER NOT NULL, outputOthers TEXT,
                PRIMARY KEY(providerId, ordinal),
                FOREIGN KEY(providerId) REFERENCES model_providers(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_provider_catalog_models_model ON provider_catalog_models(providerId, modelId)")
        db.execSQL("""
            CREATE TABLE provider_catalog_tools (
                providerId TEXT NOT NULL, modelOrdinal INTEGER NOT NULL, ordinal INTEGER NOT NULL, toolName TEXT NOT NULL,
                PRIMARY KEY(providerId, modelOrdinal, ordinal),
                FOREIGN KEY(providerId, modelOrdinal) REFERENCES provider_catalog_models(providerId, ordinal) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE chat_profiles (
                id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, icon TEXT, sortOrder INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE chat_profile_configs (
                profileId TEXT NOT NULL PRIMARY KEY, systemPrompt TEXT NOT NULL,
                FOREIGN KEY(profileId) REFERENCES chat_profiles(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE chat_profile_mcp_access (
                profileId TEXT NOT NULL, ordinal INTEGER NOT NULL, serverId TEXT NOT NULL, enabled INTEGER NOT NULL,
                PRIMARY KEY(profileId, ordinal),
                FOREIGN KEY(profileId) REFERENCES chat_profiles(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_chat_profile_mcp_access_server ON chat_profile_mcp_access(profileId, serverId)")
        db.execSQL("""
            CREATE TABLE chat_profile_mcp_tools (
                profileId TEXT NOT NULL, serverId TEXT NOT NULL, ordinal INTEGER NOT NULL,
                toolName TEXT NOT NULL, enabled INTEGER NOT NULL,
                PRIMARY KEY(profileId, serverId, ordinal),
                FOREIGN KEY(profileId) REFERENCES chat_profiles(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_chat_profile_mcp_tools_name ON chat_profile_mcp_tools(profileId, serverId, toolName)")
        db.execSQL("""
            CREATE TABLE chat_profile_active_mcp (
                profileId TEXT NOT NULL, ordinal INTEGER NOT NULL, serverId TEXT NOT NULL,
                PRIMARY KEY(profileId, ordinal),
                FOREIGN KEY(profileId) REFERENCES chat_profiles(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE chat_profile_active_model_tools (
                profileId TEXT NOT NULL, ordinal INTEGER NOT NULL, toolName TEXT NOT NULL,
                PRIMARY KEY(profileId, ordinal),
                FOREIGN KEY(profileId) REFERENCES chat_profiles(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE mcp_auth (
                id TEXT NOT NULL PRIMARY KEY, encryptedPayload TEXT NOT NULL,
                payloadVersion INTEGER NOT NULL, cipherVersion INTEGER NOT NULL,
                state TEXT NOT NULL, updatedAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE mcp_servers (
                id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, protocol TEXT NOT NULL,
                url TEXT, icon TEXT, sortOrder INTEGER NOT NULL, authId TEXT, cacheState TEXT NOT NULL,
                FOREIGN KEY(authId) REFERENCES mcp_auth(id) ON UPDATE CASCADE ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_mcp_servers_authId ON mcp_servers(authId)")
        db.execSQL("""
            CREATE TABLE mcp_tool_caches (
                serverId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL,
                FOREIGN KEY(serverId) REFERENCES mcp_servers(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE mcp_cached_tools (
                serverId TEXT NOT NULL, ordinal INTEGER NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL,
                toolServerId TEXT, originalName TEXT NOT NULL, remoteMachineId TEXT,
                PRIMARY KEY(serverId, ordinal),
                FOREIGN KEY(serverId) REFERENCES mcp_servers(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE speech_auth (
                id TEXT NOT NULL PRIMARY KEY, encryptedApiKey TEXT NOT NULL,
                cipherVersion INTEGER NOT NULL, updatedAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE speech_services (
                id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, provider TEXT NOT NULL, modelId TEXT,
                voice TEXT NOT NULL, speed REAL NOT NULL, pitch REAL NOT NULL, sortOrder INTEGER NOT NULL,
                icon TEXT, instructions TEXT NOT NULL, responseFormat TEXT NOT NULL, languageCode TEXT NOT NULL, authId TEXT,
                FOREIGN KEY(authId) REFERENCES speech_auth(id) ON UPDATE CASCADE ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_speech_services_authId ON speech_services(authId)")
        db.execSQL("""
            CREATE TABLE structured_json_documents (
                id TEXT NOT NULL PRIMARY KEY, ownerType TEXT NOT NULL, ownerId TEXT NOT NULL, fieldName TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_structured_json_documents_owner ON structured_json_documents(ownerType, ownerId, fieldName)")
        db.execSQL("""
            CREATE TABLE structured_json_nodes (
                documentId TEXT NOT NULL, nodeId INTEGER NOT NULL, parentNodeId INTEGER, ordinal INTEGER NOT NULL,
                objectKey TEXT, nodeKind TEXT NOT NULL, scalarValue TEXT,
                PRIMARY KEY(documentId, nodeId),
                FOREIGN KEY(documentId) REFERENCES structured_json_documents(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_structured_json_nodes_order ON structured_json_nodes(documentId, parentNodeId, ordinal)")
        db.execSQL("""
            CREATE TABLE configuration_migration_quarantine (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ownerType TEXT NOT NULL,
                ownerId TEXT NOT NULL, fieldName TEXT NOT NULL, rawValue TEXT, errorCode TEXT NOT NULL
            )
        """.trimIndent())
    }
}
