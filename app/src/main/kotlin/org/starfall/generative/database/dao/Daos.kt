package org.starfall.generative.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.starfall.generative.database.entities.ChatProfileEntity
import org.starfall.generative.database.entities.ConversationMigrationQuarantineEntity
import org.starfall.generative.database.entities.ConversationSummaryEntity
import org.starfall.generative.database.entities.ConversationEntity
import org.starfall.generative.database.entities.MessageEntity
import org.starfall.generative.database.entities.MessageVersionEntity
import org.starfall.generative.database.entities.McpServerEntity
import org.starfall.generative.database.entities.ModelProviderEntity
import org.starfall.generative.database.entities.ProviderGroupEntity
import org.starfall.generative.database.entities.SpeechServiceEntity
import org.starfall.generative.database.entities.MessageVersionFileEntity
import org.starfall.generative.database.entities.ToolActivityEntity
import org.starfall.generative.database.entities.ToolActivityFileEntity
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.database.entities.ProviderAuthEntity
import org.starfall.generative.database.entities.ProviderModelSelectionEntity
import org.starfall.generative.database.entities.ProviderModelConfigurationEntity
import org.starfall.generative.database.entities.ProviderModelValueEntity
import org.starfall.generative.database.entities.ProviderCatalogModelEntity
import org.starfall.generative.database.entities.ProviderCatalogToolEntity
import org.starfall.generative.database.entities.McpAuthEntity
import org.starfall.generative.database.entities.McpToolCacheEntity
import org.starfall.generative.database.entities.McpCachedToolEntity
import org.starfall.generative.database.entities.ChatProfileConfigEntity
import org.starfall.generative.database.entities.ChatProfileMcpAccessEntity
import org.starfall.generative.database.entities.ChatProfileMcpToolEntity
import org.starfall.generative.database.entities.ChatProfileActiveMcpEntity
import org.starfall.generative.database.entities.ChatProfileActiveModelToolEntity
import org.starfall.generative.database.entities.SpeechAuthEntity
import org.starfall.generative.database.entities.StructuredJsonDocumentEntity
import org.starfall.generative.database.entities.StructuredJsonNodeEntity
import org.starfall.generative.database.entities.ConfigurationMigrationQuarantineEntity

@Dao
interface ConversationDao {
    /**
     * Reads only conversation metadata. Do not add messagesJson or summaryJson
     * to this projection: the list must not fetch or deserialize full history.
     */
    @Query("""
        SELECT c.id, c.title, c.createdAt, c.updatedAt, c.tokenCount, c.providerId, c.modelId,
            c.profileId, c.reasoningEffort,
            EXISTS (SELECT 1 FROM messages m WHERE m.conversationId = c.id)
                OR EXISTS (SELECT 1 FROM conversation_migration_quarantine q
                    WHERE q.conversationId = c.id AND q.payloadKind IN ('messages_json', 'messages_integrity')) AS hasMessages,
            EXISTS (SELECT 1 FROM conversation_summaries s WHERE s.conversationId = c.id)
                OR EXISTS (SELECT 1 FROM conversation_migration_quarantine q
                    WHERE q.conversationId = c.id AND q.payloadKind IN ('summary_json', 'summary_boundary_missing')) AS hasSummary,
            EXISTS (SELECT 1 FROM conversation_migration_quarantine q WHERE q.conversationId = c.id) AS hasMigrationIssue
        FROM conversations c
        ORDER BY c.updatedAt DESC, c.id ASC
    """)
    fun getConversationListItems(): Flow<List<ConversationListItem>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, id ASC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence DESC")
    suspend fun getMessagesNewestFirst(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM message_versions WHERE conversationId = :conversationId AND messageId = :messageId ORDER BY versionIndex ASC")
    suspend fun getMessageVersions(conversationId: String, messageId: String): List<MessageVersionEntity>

    @Query("SELECT * FROM message_version_files WHERE conversationId = :conversationId AND messageId = :messageId AND versionIndex = :versionIndex ORDER BY fileIndex ASC")
    suspend fun getMessageVersionFiles(conversationId: String, messageId: String, versionIndex: Int): List<MessageVersionFileEntity>

    @Query("SELECT * FROM tool_activities WHERE conversationId = :conversationId AND messageId = :messageId AND versionIndex = :versionIndex ORDER BY activityIndex ASC")
    suspend fun getToolActivities(conversationId: String, messageId: String, versionIndex: Int): List<ToolActivityEntity>

    @Query("SELECT * FROM tool_activity_files WHERE conversationId = :conversationId AND messageId = :messageId AND versionIndex = :versionIndex AND activityIndex = :activityIndex ORDER BY fileIndex ASC")
    suspend fun getToolActivityFiles(conversationId: String, messageId: String, versionIndex: Int, activityIndex: Int): List<ToolActivityFileEntity>

    @Query("SELECT * FROM conversation_summaries WHERE conversationId = :conversationId")
    suspend fun getSummary(conversationId: String): ConversationSummaryEntity?

    @Query("SELECT * FROM conversation_migration_quarantine WHERE conversationId = :conversationId")
    suspend fun getMigrationIssues(conversationId: String): List<ConversationMigrationQuarantineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Update
    suspend fun updateMessage(message: MessageEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET sequence = sequence + :offset WHERE conversationId = :conversationId")
    suspend fun shiftMessageSequences(conversationId: String, offset: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessageVersions(versions: List<MessageVersionEntity>)

    @Update
    suspend fun updateMessageVersion(version: MessageVersionEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageVersion(version: MessageVersionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToolActivities(activities: List<ToolActivityEntity>)

    @Update
    suspend fun updateToolActivity(activity: ToolActivityEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertToolActivity(activity: ToolActivityEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessageVersionFiles(files: List<MessageVersionFileEntity>)

    @Query("DELETE FROM message_version_files WHERE conversationId = :conversationId AND messageId = :messageId AND versionIndex = :versionIndex AND fileIndex NOT IN (:fileIndexes)")
    suspend fun deleteMessageVersionFilesExcept(conversationId: String, messageId: String, versionIndex: Int, fileIndexes: List<Int>)

    @Query("DELETE FROM message_version_files WHERE conversationId = :conversationId AND messageId = :messageId AND versionIndex = :versionIndex")
    suspend fun deleteMessageVersionFiles(conversationId: String, messageId: String, versionIndex: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToolActivityFiles(files: List<ToolActivityFileEntity>)

    @Query("DELETE FROM tool_activity_files WHERE conversationId = :conversationId AND messageId = :messageId AND versionIndex = :versionIndex AND activityIndex = :activityIndex AND fileIndex NOT IN (:fileIndexes)")
    suspend fun deleteToolActivityFilesExcept(conversationId: String, messageId: String, versionIndex: Int, activityIndex: Int, fileIndexes: List<Int>)

    @Query("DELETE FROM tool_activity_files WHERE conversationId = :conversationId AND messageId = :messageId AND versionIndex = :versionIndex AND activityIndex = :activityIndex")
    suspend fun deleteToolActivityFiles(conversationId: String, messageId: String, versionIndex: Int, activityIndex: Int)

    @Query("DELETE FROM message_versions WHERE conversationId = :conversationId AND messageId = :messageId AND versionIndex NOT IN (:versionIndexes)")
    suspend fun deleteMessageVersionsExcept(conversationId: String, messageId: String, versionIndexes: List<Int>)

    @Query("DELETE FROM message_versions WHERE conversationId = :conversationId AND messageId = :messageId")
    suspend fun deleteMessageVersions(conversationId: String, messageId: String)

    @Query("DELETE FROM tool_activities WHERE conversationId = :conversationId AND messageId = :messageId AND versionIndex = :versionIndex AND activityIndex NOT IN (:activityIndexes)")
    suspend fun deleteToolActivitiesExcept(conversationId: String, messageId: String, versionIndex: Int, activityIndexes: List<Int>)

    @Query("DELETE FROM tool_activities WHERE conversationId = :conversationId AND messageId = :messageId AND versionIndex = :versionIndex")
    suspend fun deleteToolActivities(conversationId: String, messageId: String, versionIndex: Int)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id NOT IN (:messageIds)")
    suspend fun deleteMessagesExcept(conversationId: String, messageIds: List<String>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteAllMessages(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: ConversationSummaryEntity)

    @Update
    suspend fun updateSummary(summary: ConversationSummaryEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSummaryIfAbsent(summary: ConversationSummaryEntity): Long

    @Query("DELETE FROM conversation_summaries WHERE conversationId = :conversationId AND id != :summaryId")
    suspend fun deleteOtherSummaries(conversationId: String, summaryId: String)

    @Query("DELETE FROM conversation_summaries WHERE conversationId = :conversationId")
    suspend fun deleteSummary(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMigrationIssue(issue: ConversationMigrationQuarantineEntity)

    @Query("DELETE FROM conversation_migration_quarantine WHERE conversationId = :conversationId")
    suspend fun deleteMigrationIssues(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameConversation(id: String, title: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    /** True when any stored conversation still refers to [name] in either relational file table. */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM message_version_files WHERE reference = :name OR reference = 'tool-file:' || :name
                OR reference LIKE '%/' || :name OR reference LIKE 'file:%/' || :name OR reference LIKE 'tool-file:%/' || :name
            UNION ALL
            SELECT 1 FROM tool_activity_files WHERE reference = :name OR reference = 'tool-file:' || :name
                OR reference LIKE '%/' || :name OR reference LIKE 'file:%/' || :name OR reference LIKE 'tool-file:%/' || :name
        )
    """)
    suspend fun mentionsFile(name: String): Boolean

    @Query("""
        SELECT reference FROM message_version_files
        UNION ALL
        SELECT reference FROM tool_activity_files
    """)
    suspend fun getAllFileReferences(): List<String>

    @Query("""
        SELECT f.reference FROM message_version_files f
        INNER JOIN messages m ON m.conversationId = f.conversationId AND m.id = f.messageId
        WHERE m.role = 'user'
    """)
    suspend fun getUserFileReferences(): List<String>

    @Query("""
        SELECT f.reference FROM message_version_files f
        INNER JOIN messages m ON m.conversationId = f.conversationId AND m.id = f.messageId
        WHERE m.role != 'user' AND NOT EXISTS (
            SELECT 1 FROM tool_activity_files sent
            INNER JOIN tool_activities t ON t.conversationId = sent.conversationId
                AND t.messageId = sent.messageId AND t.versionIndex = sent.versionIndex
                AND t.activityIndex = sent.activityIndex
            WHERE sent.conversationId = f.conversationId AND sent.messageId = f.messageId
                AND sent.versionIndex = f.versionIndex AND t.wireName = 'send_file'
                AND (sent.reference = f.reference OR sent.reference = 'tool-file:' || f.reference
                    OR f.reference = 'tool-file:' || sent.reference)
        )
        UNION ALL
        SELECT sent.reference FROM tool_activity_files sent
        INNER JOIN tool_activities t ON t.conversationId = sent.conversationId
            AND t.messageId = sent.messageId AND t.versionIndex = sent.versionIndex
            AND t.activityIndex = sent.activityIndex
        WHERE t.wireName != 'send_file'
    """)
    suspend fun getGeneratedFileReferences(): List<String>
}

@Dao
interface ChatProfileDao {
    @Query("SELECT * FROM chat_profiles ORDER BY sortOrder ASC, id ASC")
    fun getAllProfiles(): Flow<List<ChatProfileEntity>>

    @Query("SELECT * FROM chat_profiles WHERE id = :id")
    suspend fun getProfileById(id: String): ChatProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(profile: ChatProfileEntity)

    @Query("UPDATE chat_profiles SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM chat_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM chat_profile_configs WHERE profileId = :profileId")
    suspend fun getConfig(profileId: String): ChatProfileConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: ChatProfileConfigEntity)

    @Query("SELECT * FROM chat_profile_mcp_access WHERE profileId = :profileId ORDER BY ordinal ASC")
    suspend fun getMcpAccess(profileId: String): List<ChatProfileMcpAccessEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMcpAccess(items: List<ChatProfileMcpAccessEntity>)

    @Query("DELETE FROM chat_profile_mcp_access WHERE profileId = :profileId")
    suspend fun deleteMcpAccess(profileId: String)

    @Query("SELECT * FROM chat_profile_mcp_tools WHERE profileId = :profileId ORDER BY serverId ASC, ordinal ASC")
    suspend fun getMcpTools(profileId: String): List<ChatProfileMcpToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMcpTools(items: List<ChatProfileMcpToolEntity>)

    @Query("DELETE FROM chat_profile_mcp_tools WHERE profileId = :profileId")
    suspend fun deleteMcpTools(profileId: String)

    @Query("SELECT * FROM chat_profile_active_mcp WHERE profileId = :profileId ORDER BY ordinal ASC")
    suspend fun getActiveMcp(profileId: String): List<ChatProfileActiveMcpEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActiveMcp(items: List<ChatProfileActiveMcpEntity>)

    @Query("DELETE FROM chat_profile_active_mcp WHERE profileId = :profileId")
    suspend fun deleteActiveMcp(profileId: String)

    @Query("SELECT * FROM chat_profile_active_model_tools WHERE profileId = :profileId ORDER BY ordinal ASC")
    suspend fun getActiveModelTools(profileId: String): List<ChatProfileActiveModelToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActiveModelTools(items: List<ChatProfileActiveModelToolEntity>)

    @Query("DELETE FROM chat_profile_active_model_tools WHERE profileId = :profileId")
    suspend fun deleteActiveModelTools(profileId: String)
}

@Dao
interface ProviderGroupDao {
    @Query("SELECT * FROM provider_groups ORDER BY sortOrder ASC, id ASC")
    fun getAllGroups(): Flow<List<ProviderGroupEntity>>

    @Query("SELECT * FROM provider_groups WHERE id = :id")
    suspend fun getGroupById(id: String): ProviderGroupEntity?

    @Query("SELECT * FROM provider_groups ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllGroupsOnce(): List<ProviderGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(group: ProviderGroupEntity)

    @Query("UPDATE provider_groups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM provider_groups WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ModelProviderDao {
    @Query("SELECT * FROM model_providers ORDER BY sortOrder ASC, id ASC")
    fun getAllProviders(): Flow<List<ModelProviderEntity>>

    @Query("SELECT * FROM model_providers WHERE id = :id")
    suspend fun getProviderById(id: String): ModelProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(provider: ModelProviderEntity)

    @Query("UPDATE model_providers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("UPDATE model_providers SET groupId = :groupId WHERE id = :id")
    suspend fun updateGroup(id: String, groupId: String?)

    @Query("UPDATE model_providers SET groupId = :groupId, sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateGroupAndSortOrder(id: String, groupId: String?, sortOrder: Int)

    @Query("SELECT * FROM model_providers WHERE groupId = :groupId ORDER BY sortOrder ASC, id ASC")
    suspend fun getProvidersByGroup(groupId: String): List<ModelProviderEntity>

    @Query("SELECT * FROM model_providers WHERE groupId IS NULL ORDER BY sortOrder ASC, id ASC")
    suspend fun getUngroupedProviders(): List<ModelProviderEntity>

    @Query("UPDATE model_providers SET groupId = NULL WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: String)

    @Query("DELETE FROM model_providers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM provider_auth WHERE id = :id")
    suspend fun getAuth(id: String): ProviderAuthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuth(auth: ProviderAuthEntity)

    @Query("DELETE FROM provider_auth WHERE id = :id")
    suspend fun deleteAuth(id: String)

    @Query("SELECT * FROM provider_model_selection WHERE providerId = :providerId ORDER BY ordinal ASC")
    suspend fun getModelSelection(providerId: String): List<ProviderModelSelectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModelSelection(items: List<ProviderModelSelectionEntity>)

    @Query("DELETE FROM provider_model_selection WHERE providerId = :providerId")
    suspend fun deleteModelSelection(providerId: String)

    @Query("SELECT * FROM provider_model_configurations WHERE providerId = :providerId ORDER BY ordinal ASC")
    suspend fun getModelConfigurations(providerId: String): List<ProviderModelConfigurationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModelConfigurations(items: List<ProviderModelConfigurationEntity>)

    @Query("DELETE FROM provider_model_configurations WHERE providerId = :providerId")
    suspend fun deleteModelConfigurations(providerId: String)

    @Query("SELECT * FROM provider_model_values WHERE providerId = :providerId ORDER BY modelId ASC, listKind ASC, ordinal ASC")
    suspend fun getModelValues(providerId: String): List<ProviderModelValueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModelValues(items: List<ProviderModelValueEntity>)

    @Query("DELETE FROM provider_model_values WHERE providerId = :providerId")
    suspend fun deleteModelValues(providerId: String)

    @Query("SELECT * FROM provider_catalog_models WHERE providerId = :providerId ORDER BY ordinal ASC")
    suspend fun getCatalogModels(providerId: String): List<ProviderCatalogModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCatalogModels(items: List<ProviderCatalogModelEntity>)

    @Query("DELETE FROM provider_catalog_models WHERE providerId = :providerId")
    suspend fun deleteCatalogModels(providerId: String)

    @Query("SELECT * FROM provider_catalog_tools WHERE providerId = :providerId ORDER BY modelOrdinal ASC, ordinal ASC")
    suspend fun getCatalogTools(providerId: String): List<ProviderCatalogToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCatalogTools(items: List<ProviderCatalogToolEntity>)

    @Query("DELETE FROM provider_catalog_tools WHERE providerId = :providerId")
    suspend fun deleteCatalogTools(providerId: String)
}

@Dao
interface StructuredJsonDao {
    @Query("SELECT * FROM structured_json_documents WHERE ownerType = :ownerType AND ownerId = :ownerId AND fieldName = :fieldName LIMIT 1")
    suspend fun getDocument(ownerType: String, ownerId: String, fieldName: String): StructuredJsonDocumentEntity?

    @Query("SELECT * FROM structured_json_nodes WHERE documentId = :documentId ORDER BY nodeId ASC")
    suspend fun getNodes(documentId: String): List<StructuredJsonNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: StructuredJsonDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<StructuredJsonNodeEntity>)

    @Query("DELETE FROM structured_json_documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)
}

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY sortOrder ASC, id ASC")
    fun getAllServers(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun getServerById(id: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(server: McpServerEntity)

    @Query("UPDATE mcp_servers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM mcp_auth WHERE id = :id")
    suspend fun getAuth(id: String): McpAuthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuth(auth: McpAuthEntity)

    @Query("DELETE FROM mcp_auth WHERE id = :id")
    suspend fun deleteAuth(id: String)

    @Query("SELECT * FROM mcp_tool_caches WHERE serverId = :serverId")
    suspend fun getToolCache(serverId: String): McpToolCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToolCache(cache: McpToolCacheEntity)

    @Query("DELETE FROM mcp_cached_tools WHERE serverId = :serverId")
    suspend fun deleteCachedTools(serverId: String)

    @Query("SELECT * FROM mcp_cached_tools WHERE serverId = :serverId ORDER BY ordinal ASC")
    suspend fun getCachedTools(serverId: String): List<McpCachedToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedTools(tools: List<McpCachedToolEntity>)
}

@Dao
interface SpeechServiceDao {
    @Query("SELECT * FROM speech_services ORDER BY sortOrder ASC, id ASC")
    fun getAllSpeechServices(): Flow<List<SpeechServiceEntity>>

    @Query("SELECT * FROM speech_services WHERE id = :id")
    suspend fun getServiceById(id: String): SpeechServiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(service: SpeechServiceEntity)

    @Query("UPDATE speech_services SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM speech_services WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM speech_auth WHERE id = :id")
    suspend fun getAuth(id: String): SpeechAuthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuth(auth: SpeechAuthEntity)

    @Query("DELETE FROM speech_auth WHERE id = :id")
    suspend fun deleteAuth(id: String)
}
