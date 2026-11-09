package org.starfall.generative.database.repositories

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.CustomHttpToolEntity
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.shared.model.CustomHttpTool
import org.starfall.generative.shared.state.ImmediateState
import org.starfall.generative.shared.state.upsert

class CustomHttpToolRepository(private val db: AppDatabase) {
    private val dao = db.customHttpToolDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = ImmediateState(load = {
        dao.all().map { json.decodeFromString<CustomHttpTool>(SecretCipher.decrypt(it.encryptedPayload)) }
    })
    val tools = state.flow

    suspend fun snapshot(): List<CustomHttpTool> = tools.first()

    suspend fun save(tool: CustomHttpTool) {
        tool.validate()
        state.mutate({ it.upsert(tool) { item -> item.id } }) { dao.put(entity(tool)) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        state.enqueue({ items -> items.map { if (it.id == id) it.copy(enabled = enabled) else it } }) {
            // Preserve the rest of the latest persisted record when a toggle follows an edit.
            dao.all().firstOrNull { it.id == id }?.let { row ->
                val tool = json.decodeFromString<CustomHttpTool>(SecretCipher.decrypt(row.encryptedPayload))
                dao.put(entity(tool.copy(enabled = enabled)))
            }
        }
    }

    fun delete(id: String) {
        state.enqueue({ it.filterNot { tool -> tool.id == id } }) { dao.delete(id) }
    }

    suspend fun replaceAll(tools: List<CustomHttpTool>) {
        tools.forEach { it.validate() }
        state.mutate({ tools }) {
            db.withTransaction { dao.clear(); tools.forEach { dao.put(entity(it)) } }
        }
    }

    private fun entity(tool: CustomHttpTool) = CustomHttpToolEntity(tool.id, SecretCipher.encrypt(json.encodeToString(tool)))
}
