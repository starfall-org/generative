package org.starfall.generative.database.room.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.database.repositories.EndpointSecrets
import org.starfall.generative.database.repositories.ProviderAuthPayload
import org.starfall.generative.database.repositories.hasSecrets

internal object ConfigurationMigration10Providers {
    fun copy(db: SupportSQLiteDatabase) {
        db.query("SELECT id, name, type, baseUrl, authJson, configJson, icon, sortOrder, groupId FROM model_providers_v10 ORDER BY rowid").use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val endpoint = EndpointSecrets.split(migrationValue(db, "provider", id, "baseUrl", rows.getString(3)).clear())
                val authSource = migrationValue(db, "provider", id, "auth", rows.getString(4))
                val auth = repairLegacyAuthorization(id, rows.getString(2), authorization(authSource))
                val configSource = migrationValue(db, "provider", id, "config", rows.getString(5))
                val config = configSource.decode<ProviderConfiguration>()
                val customEndpoint = EndpointSecrets.split(config.customListModelsUrl)
                val payload = ProviderAuthPayload(auth, config.headers, config.httpProxy, config.socksProxy,
                    config.apiKeys, config.oauthAccounts, endpoint.privateUrl, customEndpoint.privateUrl)
                val authId = if (payload.hasSecrets()) "provider-auth-$id" else null
                if (authId != null) {
                    db.execSQL("""
                        INSERT INTO provider_auth(id, encryptedPayload, payloadVersion, cipherVersion, state, updatedAt)
                        VALUES(?, ?, ?, ?, ?, ?)
                    """.trimIndent(), migrationArgs(authId, migrationEncryptedPayload(payload), 2, 1, "READY", System.currentTimeMillis()))
                } else if (auth != Authorization(method = auth.method)) {
                    // Empty nullable credentials cannot be represented by a policy-only record.
                    authSource.retain("unrepresented_empty_auth")
                }
                migrationStructured(db, "provider", id, "auth_method", JsonPrimitive(auth.method.name))
                db.execSQL("""
                    INSERT INTO model_providers(id, name, type, baseUrl, icon, sortOrder, groupId, authId,
                        supportStream, responsesApi, customListModelsUrl, maxTokens, multipleApiKeys, modelSelectionMode)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(), migrationArgs(id, rows.getString(1), rows.getString(2), checkNotNull(endpoint.publicUrl),
                    rows.migrationString(6), rows.getLong(7), rows.migrationString(8), authId,
                    migrationBool(config.supportStream), migrationBool(config.responsesApi), customEndpoint.publicUrl,
                    config.maxTokens, migrationBool(config.multipleApiKeys), if (config.modelIds == null) "DISCOVERY" else "EXPLICIT"))
                config.modelIds?.forEachIndexed { ordinal, modelId ->
                    db.execSQL("INSERT INTO provider_model_selection(providerId, ordinal, modelId) VALUES(?, ?, ?)",
                        migrationArgs(id, ordinal, modelId))
                }
                config.modelConfigs.entries.forEachIndexed { ordinal, (modelId, model) -> copyModel(db, id, modelId, ordinal, model) }
                migrationCheckCount(db, "provider_model_selection", config.modelIds.orEmpty().size, "providerId", id)
                migrationCheckCount(db, "provider_model_configurations", config.modelConfigs.size, "providerId", id)
                migrationCheckCount(db, "provider_model_values", config.modelConfigs.values.sumOf {
                    it.reasoningEfforts.size + it.modelTools.size + it.enabledModelTools.size
                }, "providerId", id)
            }
        }
    }

    /** Former startup repairs run once at the versioned conversion, never in metadata queries. */
    private fun repairLegacyAuthorization(id: String, type: String, original: Authorization): Authorization {
        val auth = when {
            id == "google" && original.method == AuthMethod.QUERY_PARAM && original.key.isNullOrBlank() -> original.copy(key = "key")
            id == "anthropic" && original.method == AuthMethod.CUSTOM_HEADER && original.key.isNullOrBlank() ->
                Authorization(method = AuthMethod.PLATFORM_DEFAULT, value = original.value.orEmpty())
            else -> original
        }
        return if (type in listOf("GOOGLE", "ANTHROPIC") && auth.method == AuthMethod.BEARER_TOKEN && auth.key == null) {
            auth.copy(method = AuthMethod.PLATFORM_DEFAULT)
        } else auth
    }

    private fun authorization(source: ConfigurationMigration10Value): Authorization {
        val raw = source.clear()
        if (raw.isEmpty()) return Authorization()
        val value = try {
            configurationMigrationJson.parseToJsonElement(raw)
        } catch (_: kotlinx.serialization.SerializationException) {
            // A legacy key can be an unquoted string, but malformed composite JSON is not a key.
            if (raw.trimStart().firstOrNull() in listOf('{', '[', '"')) source.invalid()
            return Authorization(value = raw)
        }
        if (value is JsonObject) {
            val auth = try { configurationMigrationJson.decodeFromJsonElement<Authorization>(value) }
                catch (_: Exception) { source.invalid() }
            val represented = configurationMigrationJson.encodeToJsonElement(auth) as JsonObject
            val method = value["method"]
            // The enum name and its lowercase @SerialName have the same persisted policy.
            val equivalentMethod = method is JsonPrimitive && method.isString && method.content.equals(auth.method.name, true)
            source.retainUnrepresented(value, if (equivalentMethod) JsonObject(represented + ("method" to checkNotNull(method))) else represented)
            return auth
        }
        if (value is JsonPrimitive && value.isString) return Authorization(value = value.content)
        // Numeric/boolean-looking legacy API keys are still exact strings, not JSON scalars.
        if (value is JsonPrimitive) return Authorization(value = raw)
        source.invalid()
    }

    private fun copyModel(db: SupportSQLiteDatabase, id: String, modelId: String, ordinal: Int, model: ModelConfiguration) {
        db.execSQL("""
            INSERT INTO provider_model_configurations(providerId, modelId, ordinal, temperature, topP, topK,
                supportStream, displayName, contextWindowTokens, modelType, supportsVision, supportsVideoInput,
                supportsAudioInput, supportsThinking, reasoningEffort, thinkingLevel, supportsToolCalls,
                legacySendThinkingContent, thinkingPassback, icon)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(), migrationArgs(id, modelId, ordinal, model.temperature, model.topP, model.topK,
            model.supportStream?.let(::migrationBool), model.displayName, model.contextWindowTokens, model.modelType.name,
            migrationBool(model.supportsVision), migrationBool(model.supportsVideoInput), migrationBool(model.supportsAudioInput),
            migrationBool(model.supportsThinking), model.reasoningEffort, model.thinkingLevel, migrationBool(model.supportsToolCalls),
            model.legacySendThinkingContent?.let(::migrationBool), model.thinkingPassback?.name, model.icon))
        listOf("reasoning_efforts" to model.reasoningEfforts, "model_tools" to model.modelTools,
            "enabled_model_tools" to model.enabledModelTools).forEach { (kind, values) ->
            values.forEachIndexed { index, value ->
                db.execSQL("INSERT INTO provider_model_values(providerId, modelId, listKind, ordinal, value) VALUES(?, ?, ?, ?, ?)",
                    migrationArgs(id, modelId, kind, index, value))
            }
        }
        if (model.modelJson.isNotEmpty()) migrationStructured(db, "provider_model", "$id/$modelId", "model_json", model.modelJson)
    }
}
