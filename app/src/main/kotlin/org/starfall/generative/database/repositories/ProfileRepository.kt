package org.starfall.generative.database.repositories

import org.starfall.generative.shared.state.ImmediateState
import org.starfall.generative.shared.state.upsert

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.starfall.generative.shared.model.AiChatConfig
import org.starfall.generative.shared.model.ChatProfile
import org.starfall.generative.shared.model.McpAccess
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ChatProfileActiveMcpEntity
import org.starfall.generative.database.room.entities.ChatProfileActiveModelToolEntity
import org.starfall.generative.database.room.entities.ChatProfileConfigEntity
import org.starfall.generative.database.room.entities.ChatProfileEntity
import org.starfall.generative.database.room.entities.ChatProfileMcpAccessEntity
import org.starfall.generative.database.room.entities.ChatProfileMcpToolEntity

class ProfileRepository(private val db: AppDatabase) {
    private val dao = db.chatProfileDao()

    private val state = ImmediateState(load = {
        db.withTransaction { dao.getAllProfiles().map { entityToModel(it) } }
    })
    val allProfiles: Flow<List<ChatProfile>> = state.flow

    suspend fun getById(id: String): ChatProfile? =
        db.withTransaction { dao.getProfileById(id)?.let { entityToModel(it) } }

    /** A restore is an explicit action, not a Room invalidation. */
    suspend fun replaceAll(profiles: List<ChatProfile>) {
        state.mutate({ profiles.sortedBy { it.sortOrder } }) {
            db.withTransaction {
                dao.getAllProfiles().forEach { dao.deleteById(it.id) }
                profiles.forEach { persistProfile(it) }
            }
        }
    }

    suspend fun saveProfile(profile: ChatProfile) {
        state.mutate({ it.upsert(profile) { item -> item.id }.sortedBy { item -> item.sortOrder } }) {
            db.withTransaction { persistProfile(profile) }
        }
    }

    private suspend fun persistProfile(profile: ChatProfile) {
        dao.insertOrUpdate(ChatProfileEntity(profile.id, profile.name, profile.icon, profile.sortOrder))
        dao.insertConfig(ChatProfileConfigEntity(profile.id, profile.config.systemPrompt))
        dao.deleteMcpAccess(profile.id)
        dao.deleteMcpTools(profile.id)
        dao.deleteActiveMcp(profile.id)
        dao.deleteActiveModelTools(profile.id)
        dao.insertMcpAccess(profile.config.mcpAccess.entries.mapIndexed { ordinal, (serverId, access) ->
            ChatProfileMcpAccessEntity(profile.id, ordinal, serverId, access.enabled)
        })
        dao.insertMcpTools(profile.config.mcpAccess.flatMap { (serverId, access) ->
            access.tools.entries.mapIndexed { ordinal, (toolName, enabled) ->
                ChatProfileMcpToolEntity(profile.id, serverId, ordinal, toolName, enabled)
            }
        })
        dao.insertActiveMcp(profile.activeMcp.mapIndexed { ordinal, serverId -> ChatProfileActiveMcpEntity(profile.id, ordinal, serverId) })
        dao.insertActiveModelTools(profile.activeModelTools.mapIndexed { ordinal, tool -> ChatProfileActiveModelToolEntity(profile.id, ordinal, tool) })
    }

    suspend fun deleteProfile(id: String) {
        state.mutate({ it.filterNot { item -> item.id == id } }) {
            dao.deleteById(id)
        }
    }

    fun queueReorderProfiles(ids: List<String>) {
        val positions = ids.withIndex().associate { it.value to it.index }
        state.enqueue({ items -> items.map { item ->
            positions[item.id]?.let { item.copy(sortOrder = it) } ?: item
        }.sortedBy { it.sortOrder } }) {
            db.withTransaction { ids.forEachIndexed { index, id -> dao.updateSortOrder(id, index) } }
        }
    }

    suspend fun reorderProfiles(ids: List<String>) {
        state.mutate({ items -> items.map { item -> ids.indexOf(item.id).takeIf { it >= 0 }?.let { item.copy(sortOrder = it) } ?: item }.sortedBy { it.sortOrder } }) {
            db.withTransaction { ids.forEachIndexed { index, id -> dao.updateSortOrder(id, index) } }
        }
    }

    private suspend fun entityToModel(entity: ChatProfileEntity): ChatProfile {
        val accessRows = dao.getMcpAccess(entity.id)
        val tools = dao.getMcpTools(entity.id).groupBy { it.serverId }
        val access = LinkedHashMap<String, McpAccess>()
        accessRows.sortedBy { it.ordinal }.forEach { row ->
            access[row.serverId] = McpAccess(row.enabled, tools[row.serverId].orEmpty().sortedBy { it.ordinal }.associate { it.toolName to it.enabled })
        }
        return ChatProfile(
            id = entity.id,
            name = entity.name,
            icon = entity.icon,
            config = AiChatConfig(dao.getConfig(entity.id)?.systemPrompt.orEmpty(), access),
            sortOrder = entity.sortOrder,
            activeMcp = dao.getActiveMcp(entity.id).sortedBy { it.ordinal }.map { it.serverId },
            activeModelTools = dao.getActiveModelTools(entity.id).sortedBy { it.ordinal }.map { it.toolName }
        )
    }
}
