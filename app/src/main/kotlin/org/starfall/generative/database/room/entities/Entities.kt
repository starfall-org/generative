package org.starfall.generative.database.room.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["updatedAt", "id"], name = "index_conversations_updatedAt")]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val tokenCount: Int?,
    val providerId: String,
    val modelId: String,
    val profileId: String?,
    val reasoningEffort: String?,
    /** JSON array of the instruction ids switched on for this chat; null while it follows the library default. */
    @ColumnInfo(name = "selectedPromptIds") val selectedInstructionIds: String? = null,
    @ColumnInfo(defaultValue = "'{}'") val remoteWorkspacesJson: String = "{}"
)

@Entity(tableName = "chat_profiles")
data class ChatProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String?,
    val sortOrder: Int
)

@Entity(tableName = "provider_groups")
data class ProviderGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
    val icon: String?
)

@Entity(
    tableName = "model_providers",
    foreignKeys = [ForeignKey(
        entity = ProviderAuthEntity::class,
        parentColumns = ["id"],
        childColumns = ["authId"],
        onDelete = ForeignKey.SET_NULL,
        onUpdate = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["authId"], name = "index_model_providers_authId")]
)
data class ModelProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val icon: String?,
    val sortOrder: Int,
    val groupId: String?,
    val authId: String?,
    val supportStream: Boolean,
    val responsesApi: Boolean,
    val customListModelsUrl: String?,
    val maxTokens: Int,
    val multipleApiKeys: Boolean,
    val modelSelectionMode: String
)

@Entity(
    tableName = "mcp_servers",
    foreignKeys = [ForeignKey(
        entity = McpAuthEntity::class,
        parentColumns = ["id"],
        childColumns = ["authId"],
        onDelete = ForeignKey.SET_NULL,
        onUpdate = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["authId"], name = "index_mcp_servers_authId")]
)
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,
    val url: String?,
    val icon: String?,
    val sortOrder: Int,
    val authId: String?,
    val cacheState: String
)

@Entity(
    tableName = "speech_services",
    foreignKeys = [ForeignKey(
        entity = SpeechAuthEntity::class,
        parentColumns = ["id"],
        childColumns = ["authId"],
        onDelete = ForeignKey.SET_NULL,
        onUpdate = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["authId"], name = "index_speech_services_authId")]
)
data class SpeechServiceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val provider: String,
    val modelId: String?,
    val voice: String,
    val speed: Float,
    val pitch: Float,
    val sortOrder: Int,
    val icon: String?,
    val instructions: String,
    val responseFormat: String,
    val languageCode: String,
    val authId: String?
)
