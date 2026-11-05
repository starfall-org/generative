package org.starfall.generative.database.room.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "messages",
    primaryKeys = ["conversationId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId", "sequence"], unique = true, name = "index_messages_conversation_sequence")]
)
data class MessageEntity(
    val conversationId: String,
    val id: String,
    val sequence: Int,
    val role: String,
    val activeVersionIndex: Int
)

@Entity(
    tableName = "message_versions",
    primaryKeys = ["conversationId", "messageId", "versionIndex"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["conversationId", "id"],
            childColumns = ["conversationId", "messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId", "messageId"], name = "index_message_versions_conversation_message")]
)
data class MessageVersionEntity(
    val conversationId: String,
    val messageId: String,
    val versionIndex: Int,
    val content: String,
    val timestamp: String,
    val reasoningContent: String?,
    val reasoningSignature: String?,
    val processingFinishedAt: Long?,
    val providerId: String,
    val modelId: String,
    val modelDisplayName: String,
    val generationInterrupted: Boolean,
    val generationError: String?,
    val usageInputTokens: Long? = null,
    val usageOutputTokens: Long? = null,
    val usageContextKey: String? = null,
    val usageMessagesFingerprint: String? = null,
    @ColumnInfo(defaultValue = "0") val reasoningContentOffset: Int = 0,
    val filesContentOffset: Int? = null,
    @ColumnInfo(defaultValue = "'{}'") val mediaContentOffsetsJson: String = "{}",
    @ColumnInfo(defaultValue = "'{}'") val blockOrderJson: String = "{}"
)

@Entity(
    tableName = "tool_activities",
    primaryKeys = ["conversationId", "messageId", "versionIndex", "activityIndex"],
    foreignKeys = [
        ForeignKey(
            entity = MessageVersionEntity::class,
            parentColumns = ["conversationId", "messageId", "versionIndex"],
            childColumns = ["conversationId", "messageId", "versionIndex"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversationId", "messageId", "versionIndex"], name = "index_tool_activities_conversation_message_version"),
        Index(value = ["activityId"], name = "index_tool_activities_activityId")
    ]
)
data class ToolActivityEntity(
    val conversationId: String,
    val messageId: String,
    val versionIndex: Int,
    val activityIndex: Int,
    val activityId: String,
    val name: String,
    val wireName: String,
    val status: String,
    val summary: String,
    val arguments: String,
    val response: String,
    val contentOffset: Int,
    val reasoningOffset: Int?,
    val inlineMedia: Boolean,
    val responsePreviewLimit: Int,
    val actionGroup: Int? = null
)

@Entity(
    tableName = "message_version_files",
    primaryKeys = ["conversationId", "messageId", "versionIndex", "fileIndex"],
    foreignKeys = [
        ForeignKey(
            entity = MessageVersionEntity::class,
            parentColumns = ["conversationId", "messageId", "versionIndex"],
            childColumns = ["conversationId", "messageId", "versionIndex"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversationId", "messageId", "versionIndex"], name = "index_message_version_files_conversation_message_version"),
        Index(value = ["reference"], name = "index_message_version_files_reference")
    ]
)
data class MessageVersionFileEntity(
    val conversationId: String,
    val messageId: String,
    val versionIndex: Int,
    val fileIndex: Int,
    val reference: String
)

@Entity(
    tableName = "tool_activity_files",
    primaryKeys = ["conversationId", "messageId", "versionIndex", "activityIndex", "fileIndex"],
    foreignKeys = [
        ForeignKey(
            entity = ToolActivityEntity::class,
            parentColumns = ["conversationId", "messageId", "versionIndex", "activityIndex"],
            childColumns = ["conversationId", "messageId", "versionIndex", "activityIndex"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversationId", "messageId", "versionIndex", "activityIndex"], name = "index_tool_activity_files_conversation_message_version_activity"),
        Index(value = ["reference"], name = "index_tool_activity_files_reference")
    ]
)
data class ToolActivityFileEntity(
    val conversationId: String,
    val messageId: String,
    val versionIndex: Int,
    val activityIndex: Int,
    val fileIndex: Int,
    val reference: String
)

@Entity(
    tableName = "conversation_summaries",
    primaryKeys = ["conversationId"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["conversationId", "id"],
            childColumns = ["conversationId", "throughMessageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId", "throughMessageId"], name = "index_conversation_summaries_conversation_throughMessage")]
)
data class ConversationSummaryEntity(
    val conversationId: String,
    val id: String,
    val content: String,
    val throughMessageId: String,
    val role: String,
    val createdAt: Long
)

@Entity(
    tableName = "conversation_migration_quarantine",
    primaryKeys = ["conversationId", "payloadKind"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId"], name = "index_conversation_migration_quarantine_conversation")]
)
data class ConversationMigrationQuarantineEntity(
    val conversationId: String,
    val payloadKind: String,
    val rawJson: String?,
    val errorCode: String
)
