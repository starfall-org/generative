package org.starfall.generative.database.room.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "provider_auth",
    indices = [Index(value = ["updatedAt"], name = "index_provider_auth_updatedAt")]
)
data class ProviderAuthEntity(
    @androidx.room.PrimaryKey val id: String,
    val encryptedPayload: String,
    val payloadVersion: Int,
    val cipherVersion: Int,
    val state: String,
    val updatedAt: Long
)

@Entity(
    tableName = "provider_model_selection",
    primaryKeys = ["providerId", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = ModelProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["providerId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["providerId", "modelId"], name = "index_provider_model_selection_model")]
)
data class ProviderModelSelectionEntity(
    val providerId: String,
    val ordinal: Int,
    val modelId: String
)

@Entity(
    tableName = "provider_model_configurations",
    primaryKeys = ["providerId", "modelId"],
    foreignKeys = [ForeignKey(
        entity = ModelProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["providerId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["providerId", "ordinal"], unique = true, name = "index_provider_model_configurations_order")]
)
data class ProviderModelConfigurationEntity(
    val providerId: String,
    val modelId: String,
    val ordinal: Int,
    val temperature: Double?,
    val topP: Double?,
    val topK: Int?,
    val supportStream: Boolean?,
    val displayName: String,
    val contextWindowTokens: Int,
    val modelType: String,
    val supportsVision: Boolean,
    val supportsVideoInput: Boolean,
    val supportsAudioInput: Boolean,
    val supportsThinking: Boolean,
    val reasoningEffort: String?,
    val thinkingLevel: String?,
    val supportsToolCalls: Boolean,
    val legacySendThinkingContent: Boolean?,
    val thinkingPassback: String?,
    val icon: String?,
    /** Image/video API integration selected for this model. */
    val mediaApiType: String,
    /** Reusable custom media profile selected for this model. */
    val mediaRequestProfileId: String
)

@Entity(
    tableName = "provider_model_values",
    primaryKeys = ["providerId", "modelId", "listKind", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = ProviderModelConfigurationEntity::class,
        parentColumns = ["providerId", "modelId"],
        childColumns = ["providerId", "modelId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["providerId", "modelId", "listKind"], name = "index_provider_model_values_list")]
)
data class ProviderModelValueEntity(
    val providerId: String,
    val modelId: String,
    val listKind: String,
    val ordinal: Int,
    val value: String
)

@Entity(
    tableName = "provider_catalog_models",
    primaryKeys = ["providerId", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = ModelProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["providerId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["providerId", "modelId"], name = "index_provider_catalog_models_model")]
)
data class ProviderCatalogModelEntity(
    val providerId: String,
    val ordinal: Int,
    val modelId: String,
    val displayName: String,
    val icon: String?,
    val catalogProviderId: String,
    val inputText: Boolean,
    val inputImage: Boolean,
    val inputVideo: Boolean,
    val inputEmbed: Boolean,
    val inputAudio: Boolean,
    val inputOthers: String?,
    val outputText: Boolean,
    val outputImage: Boolean,
    val outputVideo: Boolean,
    val outputEmbed: Boolean,
    val outputAudio: Boolean,
    val outputOthers: String?
)

@Entity(
    tableName = "provider_catalog_tools",
    primaryKeys = ["providerId", "modelOrdinal", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = ProviderCatalogModelEntity::class,
        parentColumns = ["providerId", "ordinal"],
        childColumns = ["providerId", "modelOrdinal"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ProviderCatalogToolEntity(
    val providerId: String,
    val modelOrdinal: Int,
    val ordinal: Int,
    val toolName: String
)

@Entity(tableName = "mcp_auth")
data class McpAuthEntity(
    @androidx.room.PrimaryKey val id: String,
    val encryptedPayload: String,
    val payloadVersion: Int,
    val cipherVersion: Int,
    val state: String,
    val updatedAt: Long
)

@Entity(
    tableName = "mcp_tool_caches",
    primaryKeys = ["serverId"],
    foreignKeys = [ForeignKey(
        entity = McpServerEntity::class,
        parentColumns = ["id"],
        childColumns = ["serverId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class McpToolCacheEntity(
    val serverId: String,
    val state: String
)

@Entity(
    tableName = "mcp_cached_tools",
    primaryKeys = ["serverId", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = McpServerEntity::class,
        parentColumns = ["id"],
        childColumns = ["serverId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class McpCachedToolEntity(
    val serverId: String,
    val ordinal: Int,
    val name: String,
    val description: String,
    val toolServerId: String?,
    val originalName: String,
    val remoteMachineId: String?
)

@Entity(
    tableName = "chat_profile_configs",
    primaryKeys = ["profileId"],
    foreignKeys = [ForeignKey(
        entity = ChatProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ChatProfileConfigEntity(
    val profileId: String,
    val systemPrompt: String
)

@Entity(
    tableName = "chat_profile_mcp_access",
    primaryKeys = ["profileId", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = ChatProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["profileId", "serverId"], unique = true, name = "index_chat_profile_mcp_access_server")]
)
data class ChatProfileMcpAccessEntity(
    val profileId: String,
    val ordinal: Int,
    val serverId: String,
    val enabled: Boolean
)

@Entity(
    tableName = "chat_profile_mcp_tools",
    primaryKeys = ["profileId", "serverId", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = ChatProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["profileId", "serverId", "toolName"], unique = true, name = "index_chat_profile_mcp_tools_name")]
)
data class ChatProfileMcpToolEntity(
    val profileId: String,
    val serverId: String,
    val ordinal: Int,
    val toolName: String,
    val enabled: Boolean
)

@Entity(
    tableName = "chat_profile_active_mcp",
    primaryKeys = ["profileId", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = ChatProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ChatProfileActiveMcpEntity(val profileId: String, val ordinal: Int, val serverId: String)

@Entity(
    tableName = "chat_profile_active_model_tools",
    primaryKeys = ["profileId", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = ChatProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ChatProfileActiveModelToolEntity(val profileId: String, val ordinal: Int, val toolName: String)

@Entity(tableName = "speech_auth")
data class SpeechAuthEntity(
    @androidx.room.PrimaryKey val id: String,
    val encryptedApiKey: String,
    val cipherVersion: Int,
    val updatedAt: Long
)

@Entity(
    tableName = "structured_json_documents",
    indices = [Index(value = ["ownerType", "ownerId", "fieldName"], unique = true, name = "index_structured_json_documents_owner")]
)
data class StructuredJsonDocumentEntity(
    @androidx.room.PrimaryKey val id: String,
    val ownerType: String,
    val ownerId: String,
    val fieldName: String
)

@Entity(
    tableName = "structured_json_nodes",
    primaryKeys = ["documentId", "nodeId"],
    foreignKeys = [ForeignKey(
        entity = StructuredJsonDocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["documentId", "parentNodeId", "ordinal"], name = "index_structured_json_nodes_order")]
)
data class StructuredJsonNodeEntity(
    val documentId: String,
    val nodeId: Int,
    val parentNodeId: Int?,
    val ordinal: Int,
    val objectKey: String?,
    val nodeKind: String,
    val scalarValue: String?
)

@Entity(tableName = "configuration_migration_quarantine")
data class ConfigurationMigrationQuarantineEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerType: String,
    val ownerId: String,
    val fieldName: String,
    val rawValue: String?,
    val errorCode: String
)
