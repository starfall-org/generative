package org.starfall.generative.database.room.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.ConversationSummary
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage

/** Converts the v9 conversation JSON aggregate into the v10 relational conversation tables. */
internal object ConversationMigration9To10 {
    private const val SOURCE_TABLE = "conversations_v9_source"
    private val migrationJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations RENAME TO $SOURCE_TABLE")
        createTables(db)
        db.query(
            "SELECT id, title, createdAt, updatedAt, messagesJson, tokenCount, providerId, modelId, profileId, summaryJson, reasoningEffort FROM $SOURCE_TABLE ORDER BY rowid"
        ).use { rows ->
            while (rows.moveToNext()) migrateConversation(db, rows)
        }
        verifyMigration(db)
        db.execSQL("DROP TABLE $SOURCE_TABLE")
        db.query("PRAGMA foreign_key_check").use { violations ->
            check(!violations.moveToFirst()) { "Conversation v9 to v10 migration created orphaned rows." }
        }
    }

    private fun migrateConversation(db: SupportSQLiteDatabase, rows: android.database.Cursor) {
        val id = rows.getString(0)
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt, tokenCount, providerId, modelId, profileId, reasoningEffort) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            args(id, rows.getString(1), rows.getLong(2), rows.getLong(3), rows.value(5), rows.getString(6), rows.getString(7), rows.value(8), rows.value(10))
        )

        val messagesRaw = rows.getString(4)
        val messages = runCatching { migrationJson.decodeFromString<List<StoredMessage>>(messagesRaw) }.getOrElse {
            quarantine(db, id, "messages_json", messagesRaw, "invalid_json")
            null
        }
        if (messages != null) {
            val ids = messages.map { it.id }
            if (ids.size != ids.toSet().size) {
                quarantine(db, id, "messages_integrity", messagesRaw, "duplicate_message_id")
            } else {
                messages.forEachIndexed { sequence, message -> migrateMessage(db, id, sequence, message) }
            }
        }

        val summaryRaw = rows.value(9) as String?
        if (summaryRaw != null) {
            val summary = runCatching { migrationJson.decodeFromString<ConversationSummary>(summaryRaw) }.getOrElse {
                quarantine(db, id, "summary_json", summaryRaw, "invalid_json")
                null
            }
            if (summary != null) {
                val hasBoundary = db.query(
                    "SELECT 1 FROM messages WHERE conversationId = ? AND id = ? LIMIT 1",
                    arrayOf(id, summary.throughMessageId)
                ).use { it.moveToFirst() }
                if (hasBoundary) {
                    db.execSQL(
                        "INSERT INTO conversation_summaries (conversationId, id, content, throughMessageId, role, createdAt) VALUES (?, ?, ?, ?, ?, ?)",
                        args(id, summary.id, summary.content, summary.throughMessageId, summary.role.name, summary.createdAt)
                    )
                } else {
                    quarantine(db, id, "summary_boundary_missing", summaryRaw, "missing_summary_boundary")
                }
            }
        }
    }

    /** Compare every representable source value with its normalized relational counterpart. */
    private fun verifyMigration(db: SupportSQLiteDatabase) {
        check(count(db, SOURCE_TABLE) == count(db, "conversations")) {
            "Conversation v9 to v10 migration changed the conversation row count."
        }
        db.query("SELECT id, title, createdAt, updatedAt, messagesJson, tokenCount, providerId, modelId, profileId, summaryJson, reasoningEffort FROM $SOURCE_TABLE ORDER BY rowid").use { rows ->
            while (rows.moveToNext()) verifyConversation(db, rows)
        }
    }

    private fun verifyConversation(db: SupportSQLiteDatabase, source: android.database.Cursor) {
        val id = source.getString(0)
        db.query("SELECT title, createdAt, updatedAt, tokenCount, providerId, modelId, profileId, reasoningEffort FROM conversations WHERE id = ?", args(id)).use { target ->
            check(target.moveToFirst()) { "Conversation v9 to v10 migration lost conversation $id." }
            check(target.getString(0) == source.getString(1) && target.getLong(1) == source.getLong(2) &&
                target.getLong(2) == source.getLong(3) && target.string(3) == source.string(5) &&
                target.getString(4) == source.getString(6) && target.getString(5) == source.getString(7) &&
                target.string(6) == source.string(8) && target.string(7) == source.string(10)) {
                "Conversation v9 to v10 migration changed scalar values for $id."
            }
        }

        val decodedMessages = runCatching { migrationJson.decodeFromString<List<StoredMessage>>(source.getString(4)) }.getOrNull()
        val messageIdsAreUnique = decodedMessages != null && decodedMessages.map { it.id }.distinct().size == decodedMessages.size
        val expectedMessages = decodedMessages.takeIf { messageIdsAreUnique } ?: emptyList()
        check(count(db, "messages", "conversationId = ?", id) == expectedMessages.size.toLong()) {
            "Conversation v9 to v10 migration changed message count for $id."
        }
        expectedMessages.forEachIndexed { sequence, message -> verifyMessage(db, id, sequence, message) }

        val sourceSummary = source.string(9)?.let { raw -> runCatching { migrationJson.decodeFromString<ConversationSummary>(raw) }.getOrNull() }
        val expectedSummary = sourceSummary?.takeIf { summary -> expectedMessages.any { it.id == summary.throughMessageId } }
        val targetSummaryCount = count(db, "conversation_summaries", "conversationId = ?", id)
        check(targetSummaryCount == if (expectedSummary == null) 0L else 1L) {
            "Conversation v9 to v10 migration changed summary presence for $id."
        }
        expectedSummary?.let { summary ->
            db.query("SELECT id, content, throughMessageId, role, createdAt FROM conversation_summaries WHERE conversationId = ?", args(id)).use { target ->
                check(target.moveToFirst() && target.getString(0) == summary.id && target.getString(1) == summary.content &&
                    target.getString(2) == summary.throughMessageId && target.getString(3) == summary.role.name &&
                    target.getLong(4) == summary.createdAt) { "Conversation v9 to v10 migration changed summary values for $id." }
            }
        }
    }

    private fun verifyMessage(db: SupportSQLiteDatabase, conversationId: String, sequence: Int, message: StoredMessage) {
        db.query("SELECT id, sequence, role, activeVersionIndex FROM messages WHERE conversationId = ? AND id = ?", args(conversationId, message.id)).use { target ->
            check(target.moveToFirst() && target.getInt(1) == sequence && target.getString(2) == message.role.persistedValue &&
                target.getInt(3) == message.activeVersionIndex) { "Conversation v9 to v10 migration changed message ${message.id}." }
        }
        check(count(db, "message_versions", "conversationId = ? AND messageId = ?", conversationId, message.id) == message.versions.size.toLong()) {
            "Conversation v9 to v10 migration changed version count for ${message.id}."
        }
        message.versions.forEachIndexed { versionIndex, original ->
            val version = normalizeVersion(message.role, original)
            db.query("SELECT content, timestamp, reasoningContent, reasoningSignature, processingFinishedAt, providerId, modelId, modelDisplayName, generationInterrupted, generationError FROM message_versions WHERE conversationId = ? AND messageId = ? AND versionIndex = ?", args(conversationId, message.id, versionIndex)).use { target ->
                check(target.moveToFirst() && target.getString(0) == version.content && target.getString(1) == version.timestamp &&
                    target.string(2) == version.reasoningContent && target.string(3) == version.reasoningSignature &&
                    target.longOrNull(4) == version.processingFinishedAt && target.getString(5) == version.providerId &&
                    target.getString(6) == version.modelId && target.getString(7) == version.modelDisplayName &&
                    target.getInt(8) == (if (version.generationInterrupted) 1 else 0) && target.string(9) == version.generationError) {
                    "Conversation v9 to v10 migration changed version $versionIndex for ${message.id}."
                }
            }
            verifyReferences(db, "message_version_files", "conversationId = ? AND messageId = ? AND versionIndex = ?", args(conversationId, message.id, versionIndex), version.files)
            check(count(db, "tool_activities", "conversationId = ? AND messageId = ? AND versionIndex = ?", conversationId, message.id, versionIndex) == version.toolActivity.size.toLong()) {
                "Conversation v9 to v10 migration changed tool count for ${message.id} version $versionIndex."
            }
            version.toolActivity.forEachIndexed { activityIndex, activity ->
                db.query("SELECT activityId, name, wireName, status, summary, arguments, response, contentOffset, reasoningOffset, inlineMedia, responsePreviewLimit FROM tool_activities WHERE conversationId = ? AND messageId = ? AND versionIndex = ? AND activityIndex = ?", args(conversationId, message.id, versionIndex, activityIndex)).use { target ->
                    check(target.moveToFirst() && target.getString(0) == activity.id && target.getString(1) == activity.name &&
                        target.getString(2) == activity.wireName && target.getString(3) == activity.status && target.getString(4) == activity.summary &&
                        target.getString(5) == activity.arguments && target.getString(6) == activity.response && target.getInt(7) == activity.contentOffset &&
                        target.intOrNull(8) == activity.reasoningOffset && target.getInt(9) == (if (activity.inlineMedia) 1 else 0) &&
                        target.getInt(10) == activity.responsePreviewLimit) { "Conversation v9 to v10 migration changed tool activity $activityIndex for ${message.id}." }
                }
                verifyReferences(db, "tool_activity_files", "conversationId = ? AND messageId = ? AND versionIndex = ? AND activityIndex = ?", args(conversationId, message.id, versionIndex, activityIndex), activity.files)
            }
        }
    }

    private fun verifyReferences(db: SupportSQLiteDatabase, table: String, where: String, args: Array<Any?>, expected: List<String>) {
        db.query("SELECT reference FROM $table WHERE $where ORDER BY fileIndex", args).use { target ->
            val actual = buildList { while (target.moveToNext()) add(target.getString(0)) }
            check(actual == expected) { "Conversation v9 to v10 migration changed file references in $table." }
        }
    }

    private fun count(db: SupportSQLiteDatabase, table: String, where: String? = null, vararg args: Any?): Long =
        db.query("SELECT COUNT(*) FROM $table${where?.let { " WHERE $it" }.orEmpty()}", args).use {
            check(it.moveToFirst()); it.getLong(0)
        }

    private fun android.database.Cursor.string(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun android.database.Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun android.database.Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

    private fun migrateMessage(db: SupportSQLiteDatabase, conversationId: String, sequence: Int, message: StoredMessage) {
        db.execSQL(
            "INSERT INTO messages (conversationId, id, sequence, role, activeVersionIndex) VALUES (?, ?, ?, ?, ?)",
            args(conversationId, message.id, sequence, message.role.persistedValue, message.activeVersionIndex)
        )
        message.versions.forEachIndexed { versionIndex, original ->
            val version = normalizeVersion(message.role, original)
            db.execSQL(
                "INSERT INTO message_versions (conversationId, messageId, versionIndex, content, timestamp, reasoningContent, reasoningSignature, processingFinishedAt, providerId, modelId, modelDisplayName, generationInterrupted, generationError) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                args(conversationId, message.id, versionIndex, version.content, version.timestamp, version.reasoningContent, version.reasoningSignature, version.processingFinishedAt, version.providerId, version.modelId, version.modelDisplayName, version.generationInterrupted, version.generationError)
            )
            version.files.forEachIndexed { fileIndex, reference ->
                db.execSQL(
                    "INSERT INTO message_version_files (conversationId, messageId, versionIndex, fileIndex, reference) VALUES (?, ?, ?, ?, ?)",
                    args(conversationId, message.id, versionIndex, fileIndex, reference)
                )
            }
            version.toolActivity.forEachIndexed { activityIndex, activity ->
                db.execSQL(
                    "INSERT INTO tool_activities (conversationId, messageId, versionIndex, activityIndex, activityId, name, wireName, status, summary, arguments, response, contentOffset, reasoningOffset, inlineMedia, responsePreviewLimit) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    args(conversationId, message.id, versionIndex, activityIndex, activity.id, activity.name, activity.wireName, activity.status, activity.summary, activity.arguments, activity.response, activity.contentOffset, activity.reasoningOffset, activity.inlineMedia, activity.responsePreviewLimit)
                )
                activity.files.forEachIndexed { fileIndex, reference ->
                    db.execSQL(
                        "INSERT INTO tool_activity_files (conversationId, messageId, versionIndex, activityIndex, fileIndex, reference) VALUES (?, ?, ?, ?, ?, ?)",
                        args(conversationId, message.id, versionIndex, activityIndex, fileIndex, reference)
                    )
                }
            }
        }
    }

    private fun normalizeVersion(role: ChatRole, version: MessageVersion): MessageVersion {
        val recovered = if (role == ChatRole.MODEL) recoverLegacyGenerationError(version) else version
        return recovered.copy(toolActivity = recovered.toolActivity.map { activity ->
            if (activity.status == "running") activity.copy(status = "interrupted", summary = "The previous run did not finish.") else activity
        })
    }

    private fun recoverLegacyGenerationError(version: MessageVersion): MessageVersion {
        if (version.generationError != null) return version
        val marker = "[Error: "
        val markerIndex = when {
            version.content.startsWith(marker) -> 0
            else -> version.content.lastIndexOf("\n$marker").takeIf { it >= 0 }?.plus(1) ?: -1
        }
        if (markerIndex < 0 || !version.content.endsWith(']')) return version
        return version.copy(
            content = version.content.substring(0, markerIndex).removeSuffix("\n"),
            generationError = version.content.substring(markerIndex + marker.length, version.content.length - 1)
        )
    }

    private fun quarantine(db: SupportSQLiteDatabase, conversationId: String, kind: String, rawJson: String?, errorCode: String) {
        db.execSQL(
            "INSERT INTO conversation_migration_quarantine (conversationId, payloadKind, rawJson, errorCode) VALUES (?, ?, ?, ?)",
            args(conversationId, kind, rawJson, errorCode)
        )
    }

    private fun createTables(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, tokenCount INTEGER, providerId TEXT NOT NULL, modelId TEXT NOT NULL, profileId TEXT, reasoningEffort TEXT, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX index_conversations_updatedAt ON conversations(updatedAt, id)")
        db.execSQL("CREATE TABLE messages (conversationId TEXT NOT NULL, id TEXT NOT NULL, sequence INTEGER NOT NULL, role TEXT NOT NULL, activeVersionIndex INTEGER NOT NULL, PRIMARY KEY(conversationId, id), FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX index_messages_conversation_sequence ON messages(conversationId, sequence)")
        db.execSQL("CREATE TABLE message_versions (conversationId TEXT NOT NULL, messageId TEXT NOT NULL, versionIndex INTEGER NOT NULL, content TEXT NOT NULL, timestamp TEXT NOT NULL, reasoningContent TEXT, reasoningSignature TEXT, processingFinishedAt INTEGER, providerId TEXT NOT NULL, modelId TEXT NOT NULL, modelDisplayName TEXT NOT NULL, generationInterrupted INTEGER NOT NULL, generationError TEXT, PRIMARY KEY(conversationId, messageId, versionIndex), FOREIGN KEY(conversationId, messageId) REFERENCES messages(conversationId, id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_message_versions_conversation_message ON message_versions(conversationId, messageId)")
        db.execSQL("CREATE TABLE tool_activities (conversationId TEXT NOT NULL, messageId TEXT NOT NULL, versionIndex INTEGER NOT NULL, activityIndex INTEGER NOT NULL, activityId TEXT NOT NULL, name TEXT NOT NULL, wireName TEXT NOT NULL, status TEXT NOT NULL, summary TEXT NOT NULL, arguments TEXT NOT NULL, response TEXT NOT NULL, contentOffset INTEGER NOT NULL, reasoningOffset INTEGER, inlineMedia INTEGER NOT NULL, responsePreviewLimit INTEGER NOT NULL, PRIMARY KEY(conversationId, messageId, versionIndex, activityIndex), FOREIGN KEY(conversationId, messageId, versionIndex) REFERENCES message_versions(conversationId, messageId, versionIndex) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_tool_activities_conversation_message_version ON tool_activities(conversationId, messageId, versionIndex)")
        db.execSQL("CREATE INDEX index_tool_activities_activityId ON tool_activities(activityId)")
        db.execSQL("CREATE TABLE message_version_files (conversationId TEXT NOT NULL, messageId TEXT NOT NULL, versionIndex INTEGER NOT NULL, fileIndex INTEGER NOT NULL, reference TEXT NOT NULL, PRIMARY KEY(conversationId, messageId, versionIndex, fileIndex), FOREIGN KEY(conversationId, messageId, versionIndex) REFERENCES message_versions(conversationId, messageId, versionIndex) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_message_version_files_conversation_message_version ON message_version_files(conversationId, messageId, versionIndex)")
        db.execSQL("CREATE INDEX index_message_version_files_reference ON message_version_files(reference)")
        db.execSQL("CREATE TABLE tool_activity_files (conversationId TEXT NOT NULL, messageId TEXT NOT NULL, versionIndex INTEGER NOT NULL, activityIndex INTEGER NOT NULL, fileIndex INTEGER NOT NULL, reference TEXT NOT NULL, PRIMARY KEY(conversationId, messageId, versionIndex, activityIndex, fileIndex), FOREIGN KEY(conversationId, messageId, versionIndex, activityIndex) REFERENCES tool_activities(conversationId, messageId, versionIndex, activityIndex) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_tool_activity_files_conversation_message_version_activity ON tool_activity_files(conversationId, messageId, versionIndex, activityIndex)")
        db.execSQL("CREATE INDEX index_tool_activity_files_reference ON tool_activity_files(reference)")
        db.execSQL("CREATE TABLE conversation_summaries (conversationId TEXT NOT NULL, id TEXT NOT NULL, content TEXT NOT NULL, throughMessageId TEXT NOT NULL, role TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(conversationId), FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(conversationId, throughMessageId) REFERENCES messages(conversationId, id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_conversation_summaries_conversation_throughMessage ON conversation_summaries(conversationId, throughMessageId)")
        db.execSQL("CREATE TABLE conversation_migration_quarantine (conversationId TEXT NOT NULL, payloadKind TEXT NOT NULL, rawJson TEXT, errorCode TEXT NOT NULL, PRIMARY KEY(conversationId, payloadKind), FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_conversation_migration_quarantine_conversation ON conversation_migration_quarantine(conversationId)")
    }

    private fun args(vararg values: Any?): Array<Any?> {
        val result = arrayOfNulls<Any>(values.size)
        values.forEachIndexed { index, value -> result[index] = value }
        return result
    }

    private fun android.database.Cursor.value(index: Int): Any? = if (isNull(index)) null else getString(index)

    private val ChatRole.persistedValue: String
        get() = when (this) {
            ChatRole.USER -> "user"
            ChatRole.MODEL -> "model"
            ChatRole.SYSTEM -> "system"
        }
}
