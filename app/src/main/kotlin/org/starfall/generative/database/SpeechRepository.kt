package org.starfall.generative.database

import org.starfall.generative.shared.storage.ImmediateState

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.database.AppDatabase
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.database.entities.SpeechAuthEntity
import org.starfall.generative.database.entities.SpeechServiceEntity

class SpeechRepository(private val db: AppDatabase) {
    private val dao = db.speechServiceDao()
    private val structured = db.structuredJsonDao()

    private val stored: Flow<List<SpeechService>> = flow {
        dao.getAllSpeechServices().collect { entities -> emit(db.withTransaction { entities.map { entityToModel(it) } }) }
    }
    private val state = ImmediateState(stored)
    val allServices: Flow<List<SpeechService>> = state.flow

    suspend fun getById(id: String): SpeechService? = withContext(Dispatchers.IO) {
        db.withTransaction { dao.getServiceById(id)?.let { entityToModel(it) } }
    }

    /** Replaces every speech service during a full application restore. */
    suspend fun replaceAll(services: List<SpeechService>) {
        dao.getAllSpeechServices().first().map { it.id }.forEach { deleteService(it) }
        services.forEach { saveService(it) }
    }

    suspend fun saveService(service: SpeechService) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val authId = service.apiKey.takeIf { it.isNotEmpty() }?.let { "speech-auth-${service.id}" }
            if (authId != null) dao.insertAuth(SpeechAuthEntity(authId, SecretCipher.encrypt(service.apiKey), 1, System.currentTimeMillis()))
            else dao.getServiceById(service.id)?.authId?.let { dao.deleteAuth(it) }
            dao.insertOrUpdate(SpeechServiceEntity(
                id = service.id,
                name = service.name,
                provider = service.provider,
                modelId = service.modelId,
                voice = service.voice,
                speed = service.speed,
                pitch = service.pitch,
                sortOrder = service.sortOrder,
                icon = service.icon,
                instructions = service.instructions,
                responseFormat = service.responseFormat,
                languageCode = service.languageCode,
                authId = authId
            ))
            structured.replaceDocument(StructuredJsonDocument("speech_service", service.id, "engine_settings", buildJsonObject {
                put("engine", service.engine.name)
                put("voiceName", service.voiceName)
                put("sentencesPerChunk", service.sentencesPerChunk)
            }))
            val old = structured.getDocument("speech_service", service.id, "extra_body")
            if (service.extraBody.isNotEmpty()) structured.replaceDocument(StructuredJsonDocument("speech_service", service.id, "extra_body", service.extraBody))
            else old?.let { structured.deleteDocument(it.id) }
        }
    }

    suspend fun deleteService(id: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.getServiceById(id)?.authId?.let { dao.deleteAuth(it) }
            dao.deleteById(id)
            listOf("extra_body", "engine_settings").forEach { kind ->
                structured.getDocument("speech_service", id, kind)?.let { structured.deleteDocument(it.id) }
            }
        }
    }

    fun queueReorderServices(ids: List<String>) {
        val positions = ids.withIndex().associate { it.value to it.index }
        state.enqueue({ items -> items.map { item ->
            positions[item.id]?.let { item.copy(sortOrder = it) } ?: item
        }.sortedBy { it.sortOrder } }) {
            db.withTransaction { ids.forEachIndexed { index, id -> dao.updateSortOrder(id, index) } }
        }
    }

    suspend fun reorderServices(ids: List<String>) {
        state.mutate({ items -> items.map { item -> ids.indexOf(item.id).takeIf { it >= 0 }?.let { item.copy(sortOrder = it) } ?: item }.sortedBy { it.sortOrder } }) {
            db.withTransaction { ids.forEachIndexed { index, id -> dao.updateSortOrder(id, index) } }
        }
    }

    private suspend fun entityToModel(entity: SpeechServiceEntity): SpeechService {
        val apiKey = entity.authId?.let { id ->
            val auth = dao.getAuth(id) ?: error("Speech service ${entity.id} authentication record is missing.")
            SecretCipher.decryptStrict(auth.encryptedApiKey)
        }.orEmpty()
        val extraBody = structured.readDocument("speech_service", entity.id, "extra_body") as? JsonObject ?: JsonObject(emptyMap())
        val settings = structured.readDocument("speech_service", entity.id, "engine_settings") as? JsonObject
        val engine = settings?.get("engine")?.jsonPrimitive?.contentOrNull?.let { value ->
            SpeechEngine.entries.firstOrNull { it.name == value }
        } ?: if (entity.provider.equals("system", true)) SpeechEngine.SYSTEM else SpeechEngine.PROVIDER
        return SpeechService(
            id = entity.id,
            name = entity.name,
            icon = entity.icon,
            provider = entity.provider,
            modelId = entity.modelId,
            voice = entity.voice,
            speed = entity.speed,
            pitch = entity.pitch,
            apiKey = apiKey,
            sortOrder = entity.sortOrder,
            instructions = entity.instructions,
            responseFormat = entity.responseFormat,
            languageCode = entity.languageCode,
            engine = engine,
            voiceName = settings?.get("voiceName")?.jsonPrimitive?.contentOrNull.orEmpty(),
            sentencesPerChunk = (settings?.get("sentencesPerChunk") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: 10,
            extraBody = extraBody
        )
    }
}
