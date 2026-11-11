package org.starfall.generative.database.room

import org.starfall.generative.database.room.dao.CustomHttpToolDao
import org.starfall.generative.database.room.entities.CustomHttpToolEntity


import org.starfall.generative.database.room.migrations.ConfigurationMigration9To10
import org.starfall.generative.database.room.migrations.ConversationMigration9To10
import android.content.Context
import org.starfall.generative.database.room.dao.RemoteAttachmentDao
import org.starfall.generative.database.room.entities.RemoteAttachmentEntity
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.starfall.generative.database.room.dao.OAuthTokenDao
import org.starfall.generative.database.room.dao.ChatProfileDao
import org.starfall.generative.database.room.dao.ConversationDao
import org.starfall.generative.database.room.dao.McpServerDao
import org.starfall.generative.database.room.dao.ModelProviderDao
import org.starfall.generative.database.room.dao.ProviderGroupDao
import org.starfall.generative.database.room.dao.SpeechServiceDao
import org.starfall.generative.database.room.dao.StructuredJsonDao
import org.starfall.generative.database.room.entities.OAuthTokenRecordEntity
import org.starfall.generative.database.room.entities.ChatProfileEntity
import org.starfall.generative.database.room.entities.ConversationEntity
import org.starfall.generative.database.room.entities.MessageEntity
import org.starfall.generative.database.room.entities.MessageVersionEntity
import org.starfall.generative.database.room.entities.ToolActivityEntity
import org.starfall.generative.database.room.entities.MessageVersionFileEntity
import org.starfall.generative.database.room.entities.ToolActivityFileEntity
import org.starfall.generative.database.room.entities.ConversationSummaryEntity
import org.starfall.generative.database.room.entities.ConversationMigrationQuarantineEntity
import org.starfall.generative.database.room.entities.McpServerEntity
import org.starfall.generative.database.room.entities.ModelProviderEntity
import org.starfall.generative.database.room.entities.ProviderGroupEntity
import org.starfall.generative.database.room.entities.SpeechServiceEntity
import org.starfall.generative.database.room.entities.ProviderAuthEntity
import org.starfall.generative.database.room.entities.ProviderModelSelectionEntity
import org.starfall.generative.database.room.entities.ProviderModelConfigurationEntity
import org.starfall.generative.database.room.entities.ProviderModelValueEntity
import org.starfall.generative.database.room.entities.ProviderCatalogModelEntity
import org.starfall.generative.database.room.entities.ProviderCatalogToolEntity
import org.starfall.generative.database.room.entities.McpAuthEntity
import org.starfall.generative.database.room.entities.McpToolCacheEntity
import org.starfall.generative.database.room.entities.McpCachedToolEntity
import org.starfall.generative.database.room.entities.ChatProfileConfigEntity
import org.starfall.generative.database.room.entities.ChatProfileMcpAccessEntity
import org.starfall.generative.database.room.entities.ChatProfileMcpToolEntity
import org.starfall.generative.database.room.entities.ChatProfileActiveMcpEntity
import org.starfall.generative.database.room.entities.ChatProfileActiveModelToolEntity
import org.starfall.generative.database.room.entities.SpeechAuthEntity
import org.starfall.generative.database.room.entities.StructuredJsonDocumentEntity
import org.starfall.generative.database.room.entities.StructuredJsonNodeEntity
import org.starfall.generative.database.room.entities.ConfigurationMigrationQuarantineEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageVersionEntity::class,
        ToolActivityEntity::class,
        MessageVersionFileEntity::class,
        ToolActivityFileEntity::class,
        ConversationSummaryEntity::class,
        ConversationMigrationQuarantineEntity::class,
        ChatProfileEntity::class,
        ProviderGroupEntity::class,
        ModelProviderEntity::class,
        McpServerEntity::class,
        SpeechServiceEntity::class,
        ProviderAuthEntity::class,
        ProviderModelSelectionEntity::class,
        ProviderModelConfigurationEntity::class,
        ProviderModelValueEntity::class,
        ProviderCatalogModelEntity::class,
        ProviderCatalogToolEntity::class,
        McpAuthEntity::class,
        McpToolCacheEntity::class,
        McpCachedToolEntity::class,
        ChatProfileConfigEntity::class,
        ChatProfileMcpAccessEntity::class,
        ChatProfileMcpToolEntity::class,
        ChatProfileActiveMcpEntity::class,
        ChatProfileActiveModelToolEntity::class,
        SpeechAuthEntity::class,
        StructuredJsonDocumentEntity::class,
        StructuredJsonNodeEntity::class,
        ConfigurationMigrationQuarantineEntity::class,
        OAuthTokenRecordEntity::class,
        CustomHttpToolEntity::class,
        RemoteAttachmentEntity::class
    ],
    version = 18,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun remoteAttachmentDao(): RemoteAttachmentDao
    abstract fun conversationDao(): ConversationDao
    abstract fun chatProfileDao(): ChatProfileDao
    abstract fun providerGroupDao(): ProviderGroupDao
    abstract fun modelProviderDao(): ModelProviderDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun speechServiceDao(): SpeechServiceDao
    abstract fun structuredJsonDao(): StructuredJsonDao
    abstract fun customHttpToolDao(): CustomHttpToolDao
    abstract fun oauthTokenDao(): OAuthTokenDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS speech_services (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, provider TEXT NOT NULL, voice TEXT NOT NULL, speed REAL NOT NULL, pitch REAL NOT NULL, apiKey TEXT NOT NULL)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE speech_services ADD COLUMN modelId TEXT")
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN cachedToolsJson TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("chat_profiles", "llm_providers", "mcp_servers", "speech_services").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE $table SET sortOrder = rowid")
                }
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN summaryJson TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN reasoningEffort TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS provider_groups (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, sortOrder INTEGER NOT NULL)")
                db.execSQL("ALTER TABLE llm_providers ADD COLUMN groupId TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_groups ADD COLUMN icon TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE speech_services ADD COLUMN optionsJson TEXT")
            }
        }

        // First-launch upgrade of the existing "generative_db" file: Room runs this
        // automatically the first time the updated app opens an old (v8 or earlier)
        // database, renaming the legacy tables in place so all existing rows survive.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_providers RENAME TO model_providers")
                db.execSQL("ALTER TABLE llm_models RENAME TO provider_models")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room owns the upgrade transaction; direct callers need the same atomicity.
                val ownsTransaction = !db.inTransaction()
                if (ownsTransaction) db.beginTransaction()
                try {
                    ConversationMigration9To10.migrate(db)
                    ConfigurationMigration9To10.migrate(db)
                    if (ownsTransaction) db.setTransactionSuccessful()
                } finally {
                    if (ownsTransaction) db.endTransaction()
                }
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_model_configurations ADD COLUMN mediaApiType TEXT NOT NULL DEFAULT 'DEFAULT'")
                db.execSQL("ALTER TABLE provider_model_configurations ADD COLUMN mediaRequestProfileId TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message_versions ADD COLUMN usageInputTokens INTEGER")
                db.execSQL("ALTER TABLE message_versions ADD COLUMN usageOutputTokens INTEGER")
                db.execSQL("ALTER TABLE message_versions ADD COLUMN usageContextKey TEXT")
                db.execSQL("ALTER TABLE message_versions ADD COLUMN usageMessagesFingerprint TEXT")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN selectedPromptIds TEXT")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message_versions ADD COLUMN reasoningContentOffset INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE message_versions ADD COLUMN filesContentOffset INTEGER")
                db.execSQL("ALTER TABLE message_versions ADD COLUMN mediaContentOffsetsJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE message_versions ADD COLUMN blockOrderJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE tool_activities ADD COLUMN actionGroup INTEGER")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS oauth_token_records (namespace TEXT NOT NULL, recordId TEXT NOT NULL, encryptedPayload TEXT NOT NULL, PRIMARY KEY(namespace, recordId))")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN remoteWorkspacesJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS custom_http_tools (id TEXT NOT NULL PRIMARY KEY, encryptedPayload TEXT NOT NULL)")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS remote_attachments (`key` TEXT NOT NULL PRIMARY KEY, localReference TEXT NOT NULL, remoteId TEXT NOT NULL, remoteName TEXT NOT NULL, expiresAt INTEGER)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "generative_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
