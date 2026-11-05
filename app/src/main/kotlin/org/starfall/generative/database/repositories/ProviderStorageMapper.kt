package org.starfall.generative.database.repositories

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.provider.AnthropicPromptCache
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.database.room.entities.ModelProviderEntity
import org.starfall.generative.database.room.entities.ProviderAuthEntity
import org.starfall.generative.database.room.entities.ProviderModelConfigurationEntity
import org.starfall.generative.database.room.entities.ProviderModelSelectionEntity
import org.starfall.generative.database.room.entities.ProviderModelValueEntity

/** Callers hold a Room transaction. Existing parents/auth must never pass through INSERT REPLACE. */
internal fun AppDatabase.updateStorageRow(table: String, id: String, vararg columns: Pair<String, Any?>): Boolean {
    check(inTransaction()) { "Storage updates require a transaction." }
    val values = ContentValues().apply {
        columns.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, value)
                else -> error("Unsupported storage column type for $key")
            }
        }
    }
    return openHelper.writableDatabase.update(table, SQLiteDatabase.CONFLICT_ABORT, values, "id = ?", arrayOf(id)) > 0
}

internal fun AppDatabase.deleteUnreferencedEndpointAuth(table: String, parent: String, id: String) {
    check(inTransaction()) { "Storage deletes require a transaction." }
    openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE id = ? AND NOT EXISTS (SELECT 1 FROM $parent WHERE authId = ?)", arrayOf(id, id))
}

internal class ProviderStorageMapper(private val db: AppDatabase) {
    private val dao = db.modelProviderDao()
    private val structured = db.structuredJsonDao()

    /** A last-resort RAM placeholder, never requiring another database query or decryption. */
    fun unreadableMetadata(entity: ModelProviderEntity) = ModelProvider(
        id = entity.id, name = entity.name,
        type = runCatching { ModelApiType.valueOf(entity.type) }.getOrDefault(ModelApiType.OPENAI),
        baseUrl = EndpointSecrets.split(entity.baseUrl).publicUrl.orEmpty(),
        icon = entity.icon, groupId = entity.groupId, sortOrder = entity.sortOrder,
        credentialState = CredentialLoadState.ERROR
    )

    /** Raw owner cleanup: never decode model JSON, and never match sibling provider ID prefixes. */
    fun deleteDocumentsByOwner(id: String) {
        check(db.inTransaction()) { "Provider cleanup requires a transaction" }
        db.openHelper.writableDatabase.execSQL(
            """DELETE FROM structured_json_documents WHERE
                (ownerType IN ('provider', 'provider_catalog') AND ownerId = ?) OR
                (ownerType = 'provider_model' AND ownerId IN (
                    SELECT (? || '/' || modelId) FROM provider_model_configurations WHERE providerId = ?))""",
            arrayOf(id, id, id)
        )
    }

    suspend fun metadata(entity: ModelProviderEntity): ModelProvider {
        val selections = dao.getModelSelection(entity.id).sortedBy { it.ordinal }
        val values = dao.getModelValues(entity.id).groupBy { it.modelId to it.listKind }
        val configs = LinkedHashMap<String, ModelConfiguration>()
        dao.getModelConfigurations(entity.id).sortedBy { it.ordinal }.forEach { model ->
            fun list(kind: String) = values[model.modelId to kind].orEmpty().sortedBy { it.ordinal }.map { it.value }
            configs[model.modelId] = ModelConfiguration(
                temperature = model.temperature, topP = model.topP, topK = model.topK, supportStream = model.supportStream,
                displayName = model.displayName, contextWindowTokens = model.contextWindowTokens,
                modelType = runCatching { ModelType.valueOf(model.modelType) }.getOrDefault(ModelType.TEXT_GENERATION),
                supportsVision = model.supportsVision, supportsVideoInput = model.supportsVideoInput,
                supportsAudioInput = model.supportsAudioInput, supportsThinking = model.supportsThinking,
                reasoningEffort = model.reasoningEffort, reasoningEfforts = list("reasoning_efforts"), thinkingLevel = model.thinkingLevel,
                supportsToolCalls = model.supportsToolCalls, modelTools = list("model_tools"), enabledModelTools = list("enabled_model_tools"),
                legacySendThinkingContent = model.legacySendThinkingContent,
                 thinkingPassback = model.thinkingPassback?.let { runCatching { ThinkingPassback.valueOf(it) }.getOrNull() },
                 icon = model.icon,
                 mediaApiType = runCatching { MediaGenerationMode.valueOf(model.mediaApiType) }
                     .getOrDefault(MediaGenerationMode.DEFAULT),
                 mediaRequestProfileId = model.mediaRequestProfileId,
                 modelJson = structured.readDocument("provider_model", "${entity.id}/${model.modelId}", "model_json") as? JsonObject
                    ?: JsonObject(emptyMap())
            )
        }
        val policy = if (entity.authId == null) {
            (structured.readDocument("provider", entity.id, "auth_method") as? JsonPrimitive)?.content
                ?.let { runCatching { AuthMethod.valueOf(it) }.getOrDefault(AuthMethod.PLATFORM_DEFAULT) }
                ?: AuthMethod.PLATFORM_DEFAULT
        } else AuthMethod.PLATFORM_DEFAULT
        return ModelProvider(
            id = entity.id, name = entity.name,
            type = runCatching { ModelApiType.valueOf(entity.type) }.getOrDefault(ModelApiType.OPENAI),
            auth = Authorization(policy), icon = entity.icon, baseUrl = EndpointSecrets.split(entity.baseUrl).publicUrl.orEmpty(),
            config = ProviderConfiguration(
                supportStream = entity.supportStream, responsesApi = entity.responsesApi,
                customListModelsUrl = EndpointSecrets.split(entity.customListModelsUrl).publicUrl,
                maxTokens = entity.maxTokens, modelConfigs = configs,
                modelIds = if (entity.modelSelectionMode == "DISCOVERY") null else selections.map { it.modelId },
                multipleApiKeys = entity.multipleApiKeys,
                codexClientVersion = (structured.readDocument("provider", entity.id, "codex_client_version") as? JsonPrimitive)?.content,
                antigravityVersion = (structured.readDocument("provider", entity.id, "antigravity_version") as? JsonPrimitive)?.content,
                antigravityChangelist = (structured.readDocument("provider", entity.id, "antigravity_changelist") as? JsonPrimitive)?.content,
                anthropicPromptCache = (structured.readDocument("provider", entity.id, "anthropic_prompt_cache") as? JsonObject)
                    ?.let { runCatching { json.decodeFromJsonElement<AnthropicPromptCache>(it) }.getOrNull() } ?: AnthropicPromptCache(),
                antigravityProjectIds = (structured.readDocument("provider", entity.id, "antigravity_project_ids") as? JsonObject)
                    ?.mapNotNull { (id, value) -> (value as? JsonPrimitive)?.contentOrNull?.trim()
                        ?.takeIf(String::isNotEmpty)?.let { id to it } }?.toMap().orEmpty()
            ),
            sortOrder = entity.sortOrder, groupId = entity.groupId,
            isHidden = (structured.readDocument("provider", entity.id, "hidden") as? JsonPrimitive)?.content == "true",
            credentialState = if (entity.authId == null) CredentialLoadState.NOT_CONFIGURED else CredentialLoadState.NOT_REQUESTED
        )
    }

    suspend fun selected(entity: ModelProviderEntity): ModelProvider =
        metadata(entity).let { if (entity.authId == null) it else resolve(it, entity) }

    suspend fun resolve(provider: ModelProvider, entity: ModelProviderEntity): ModelProvider {
        check(provider.type.name == entity.type) { "Provider ${provider.id} changed type. Reload it before resolving credentials." }
        val authId = entity.authId ?: error("Provider ${provider.id} credentials are no longer configured. Reload the provider.")
        val auth = dao.getAuth(authId)
            ?: error("Provider ${provider.id} authentication record is missing. Re-enter credentials; saved metadata has been retained.")
        val payload = decodeEndpointAuth<ProviderAuthPayload>("Provider ${provider.id}", auth.encryptedPayload,
            auth.payloadVersion, auth.cipherVersion, auth.state)
        // v1 migration payloads stored suffixes. v2 always stores the complete original endpoint.
        val privateBaseUrl = payload.privateBaseUrl?.let {
            if (auth.payloadVersion == 1 && (it.startsWith("?") || it.startsWith("#"))) entity.baseUrl + it else it
        } ?: EndpointSecrets.split(entity.baseUrl).privateUrl
        return provider.copy(
            auth = payload.authorization,
            baseUrl = restoreEndpoint(provider.baseUrl, privateBaseUrl).orEmpty(),
            config = provider.config.copy(headers = payload.headers, httpProxy = payload.httpProxy, socksProxy = payload.socksProxy,
                apiKeys = payload.apiKeys, oauthAccounts = payload.oauthAccounts,
                customListModelsUrl = restoreEndpoint(provider.config.customListModelsUrl,
                    payload.privateCustomListModelsUrl ?: entity.customListModelsUrl?.takeIf { EndpointSecrets.split(it).privateUrl != null })),
            credentialState = CredentialLoadState.LOADED
        )
    }

    suspend fun persist(provider: ModelProvider) {
        requireSavableCredentials(provider.credentialState)
        val previous = dao.getProviderById(provider.id)
        val preserve = provider.credentialState == CredentialLoadState.NOT_REQUESTED
        val payload = provider.authPayload()
        if (preserve) {
            check(previous?.authId != null) { "Provider ${provider.id} credentials are no longer available. Reload before saving." }
            check(previous.type == provider.type.name && !payload.hasSecrets()) {
                "Resolve provider ${provider.id} credentials before changing authentication, provider type, or private endpoints."
            }
            check(EndpointSecrets.split(previous.baseUrl).privateUrl == null && EndpointSecrets.split(previous.customListModelsUrl).privateUrl == null) {
                "Provider ${provider.id} has a legacy private endpoint. Open and save the selected provider before editing metadata."
            }
        }
        val authId = if (preserve) previous?.authId else if (payload.hasSecrets()) previous?.authId ?: "provider-auth-${provider.id}" else null
        if (!preserve && authId != null) {
            val auth = ProviderAuthEntity(authId, SecretCipher.encrypt(json.encodeToString(payload)), 2, 1, "READY", System.currentTimeMillis())
            if (!db.updateStorageRow("provider_auth", auth.id, "encryptedPayload" to auth.encryptedPayload,
                    "payloadVersion" to auth.payloadVersion, "cipherVersion" to auth.cipherVersion, "state" to auth.state, "updatedAt" to auth.updatedAt)) {
                dao.insertAuth(auth)
            }
        }
        val entity = ModelProviderEntity(provider.id, provider.name, provider.type.name,
            EndpointSecrets.split(provider.baseUrl).publicUrl.orEmpty(), provider.icon, provider.sortOrder, provider.groupId, authId,
            provider.config.supportStream, provider.config.responsesApi, EndpointSecrets.split(provider.config.customListModelsUrl).publicUrl,
            provider.config.maxTokens, provider.config.multipleApiKeys, if (provider.config.modelIds == null) "DISCOVERY" else "EXPLICIT")
        if (!db.updateStorageRow("model_providers", entity.id, "name" to entity.name, "type" to entity.type,
                "baseUrl" to entity.baseUrl, "icon" to entity.icon, "sortOrder" to entity.sortOrder, "groupId" to entity.groupId,
                "authId" to entity.authId, "supportStream" to entity.supportStream, "responsesApi" to entity.responsesApi,
                "customListModelsUrl" to entity.customListModelsUrl, "maxTokens" to entity.maxTokens,
                "multipleApiKeys" to entity.multipleApiKeys, "modelSelectionMode" to entity.modelSelectionMode)) {
            dao.insertOrUpdate(entity)
        }
        persistHidden(provider.id, provider.isHidden)
        // Detach the reference before deleting the old auth row (SET_NULL must not mask errors).
        previous?.authId?.takeIf { it != authId }?.let { db.deleteUnreferencedEndpointAuth("provider_auth", "model_providers", it) }
        if (!preserve) {
            if (authId == null) structured.replaceDocument(StructuredJsonDocument("provider", provider.id, "auth_method", JsonPrimitive(provider.auth.method.name)))
            else deleteDocument("provider", provider.id, "auth_method")
        }
        suspend fun persistOptionalValue(field: String, value: String?) {
            val normalized = value?.trim()?.ifEmpty { null }
            if (normalized == null) deleteDocument("provider", provider.id, field)
            else structured.replaceDocument(StructuredJsonDocument("provider", provider.id, field, JsonPrimitive(normalized)))
        }
        persistOptionalValue("codex_client_version", provider.config.codexClientVersion)
        persistOptionalValue("antigravity_version", provider.config.antigravityVersion)
        persistOptionalValue("antigravity_changelist", provider.config.antigravityChangelist)
        if (provider.config.anthropicPromptCache == AnthropicPromptCache()) deleteDocument("provider", provider.id, "anthropic_prompt_cache")
        else structured.replaceDocument(StructuredJsonDocument("provider", provider.id, "anthropic_prompt_cache",
            json.encodeToJsonElement(provider.config.anthropicPromptCache)))
        val projectIds = provider.config.antigravityProjectIds.entries.mapNotNull { (id, projectId) ->
            projectId.trim().takeIf { it.isNotEmpty() && id.isNotBlank() }?.let { id to it }
        }.toMap()
        if (projectIds.isEmpty()) deleteDocument("provider", provider.id, "antigravity_project_ids")
        else structured.replaceDocument(StructuredJsonDocument("provider", provider.id, "antigravity_project_ids",
            JsonObject(projectIds.mapValues { JsonPrimitive(it.value) })))
        persistModels(provider.id, provider.config.modelIds, provider.config.modelConfigs)
    }

    /** Visibility is metadata only; toggling it must not rewrite credentials or models. */
    suspend fun persistHidden(providerId: String, hidden: Boolean) {
        if (hidden) structured.replaceDocument(
            StructuredJsonDocument("provider", providerId, "hidden", JsonPrimitive(true))
        ) else deleteDocument("provider", providerId, "hidden")
    }

    /** Model-only write path. Nothing here reads or replaces provider credentials. */
    suspend fun persistModels(providerId: String, modelIds: List<String>?, modelConfigs: Map<String, ModelConfiguration>) {
        check(db.inTransaction()) { "Model storage updates require a transaction" }
        val oldIds = dao.getModelConfigurations(providerId).map { it.modelId }
        (oldIds - modelConfigs.keys).forEach { deleteDocument("provider_model", "$providerId/$it", "model_json") }
        // Updating a UNIQUE ordinal row in place can conflict with a simultaneous reorder;
        // delete/reinsert the dependent rows in ONE transaction and bulk-insert each table.
        dao.deleteModelSelection(providerId)
        dao.deleteModelValues(providerId)
        dao.deleteModelConfigurations(providerId)
        dao.insertModelSelection(modelIds.orEmpty().mapIndexed { ordinal, id ->
            ProviderModelSelectionEntity(providerId, ordinal, id)
        })
        dao.insertModelConfigurations(modelConfigs.entries.mapIndexed { ordinal, (modelId, model) ->
            ProviderModelConfigurationEntity(
                providerId, modelId, ordinal, model.temperature, model.topP, model.topK, model.supportStream,
                model.displayName, model.contextWindowTokens, model.modelType.name, model.supportsVision,
                model.supportsVideoInput, model.supportsAudioInput, model.supportsThinking,
                model.reasoningEffort, model.thinkingLevel, model.supportsToolCalls,
                model.legacySendThinkingContent, model.thinkingPassback?.name, model.icon,
                model.mediaApiType.name, model.mediaRequestProfileId
            )
        })
        dao.insertModelValues(modelConfigs.flatMap { (modelId, model) ->
            listOf("reasoning_efforts" to model.reasoningEfforts, "model_tools" to model.modelTools,
                "enabled_model_tools" to model.enabledModelTools).flatMap { (kind, values) ->
                values.mapIndexed { index, value -> ProviderModelValueEntity(providerId, modelId, kind, index, value) }
            }
        })
        for ((modelId, model) in modelConfigs) {
            if (model.modelJson.isNotEmpty()) structured.replaceDocument(
                StructuredJsonDocument("provider_model", "$providerId/$modelId", "model_json", model.modelJson)
            ) else deleteDocument("provider_model", "$providerId/$modelId", "model_json")
        }
        // Persist model selection mode without touching auth/parent relations.
        db.updateStorageRow("model_providers", providerId,
            "modelSelectionMode" to if (modelIds == null) "DISCOVERY" else "EXPLICIT")
    }

    /** Discard only corrupted JSON graphs belonging to one provider. Keeps healthy documents. */
    suspend fun deleteMalformedDocuments(providerId: String): Int {
        check(db.inTransaction()) { "Provider repair requires a transaction" }
        data class Document(val id: String, val type: String, val owner: String, val field: String)
        val rows = mutableListOf<Document>()
        db.openHelper.writableDatabase.query(
            """SELECT id, ownerType, ownerId, fieldName FROM structured_json_documents WHERE
                (ownerType IN ('provider', 'provider_catalog') AND ownerId = ?) OR
                (ownerType = 'provider_model' AND ownerId IN (
                    SELECT (? || '/' || modelId) FROM provider_model_configurations WHERE providerId = ?))""",
            arrayOf(providerId, providerId, providerId)
        ).use { cursor ->
            while (cursor.moveToNext()) rows += Document(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
        }
        var repaired = 0
        for (row in rows) {
            try { structured.readDocument(row.type, row.owner, row.field) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                structured.deleteDocument(row.id)
                repaired++
            }
        }
        return repaired
    }

    suspend fun deleteDocuments(providerId: String) {
        dao.getModelConfigurations(providerId).forEach { deleteDocument("provider_model", "$providerId/${it.modelId}", "model_json") }
        deleteDocument("provider", providerId, "hidden")
        deleteDocument("provider", providerId, "auth_method")
        deleteDocument("provider", providerId, "codex_client_version")
        deleteDocument("provider", providerId, "antigravity_version")
        deleteDocument("provider", providerId, "antigravity_changelist")
        deleteDocument("provider", providerId, "antigravity_project_ids")
        deleteDocument("provider", providerId, "anthropic_prompt_cache")
        deleteDocument("provider_catalog", providerId, "cache_state")
    }

    private suspend fun deleteDocument(type: String, id: String, field: String) {
        structured.getDocument(type, id, field)?.let { structured.deleteDocument(it.id) }
    }
}
