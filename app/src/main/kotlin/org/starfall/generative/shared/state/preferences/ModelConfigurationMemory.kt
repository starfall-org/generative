package org.starfall.generative.shared.state.preferences


import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.starfall.generative.shared.state.LocalWriteErrors
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.normalizedForTemplate

internal fun rememberedModelName(modelId: String): String =
    modelId.trim().substringAfterLast('/').trim().lowercase(Locale.ROOT)

/** Portable model behavior, independent of provider prefixes and entity-specific icons. */
class ModelConfigurationMemory(context: Context) {
    private val preferences = context.applicationPreferences().stringPreferences("model-configuration-memory")
    private companion object {
        // Editor callbacks must not synchronously await DataStore during a click.
        val backgroundWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO + LocalWriteErrors.handler)
        val backgroundWriteLock = Mutex()
    }

    fun rememberInBackground(modelId: String, configuration: ModelConfiguration, onlyIfMissing: Boolean = false) {
        // Register this write in FIFO order before the editor closes. The actual disk work
        // starts on IO, and a backup can await the same lock without capturing stale data.
        backgroundWrites.launch(start = CoroutineStart.UNDISPATCHED) {
            backgroundWriteLock.withLock {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    remember(modelId, configuration, onlyIfMissing)
                }
            }
        }
    }

    suspend fun awaitBackgroundWrites() {
        backgroundWriteLock.withLock { /* All previously registered writes have completed. */ }
    }
    private val json = Json { ignoreUnknownKeys = true }

    fun get(modelId: String): ModelConfiguration? = preferences.getString(rememberedModelName(modelId), null)?.let {
        runCatching { json.decodeFromString<ModelConfiguration>(it) }.getOrNull()?.copy(modelJson = JsonObject(emptyMap()))?.normalizedForTemplate()
    }

    fun remember(modelId: String, configuration: ModelConfiguration, onlyIfMissing: Boolean = false) {
        val key = rememberedModelName(modelId)
        if (key.isBlank()) return
        val encoded = json.encodeToString(configuration.copy(displayName = "", icon = null, modelJson = JsonObject(emptyMap())).normalizedForTemplate())
        preferences.update {
            if (!onlyIfMissing || key !in this) put(key, encoded)
        }
    }

    fun configurationFor(model: DiscoveredModel): ModelConfiguration {
        val saved = get(model.id) ?: return model.configuration()
        return saved.copy(displayName = model.displayName,
            contextWindowTokens = model.contextWindowTokens ?: saved.contextWindowTokens,
            modelJson = model.metadata)
    }

    internal fun exportAll(): Map<String, JsonObject> = preferences.all.mapNotNull { (key, value) ->
        runCatching { key to json.encodeToJsonElement<ModelConfiguration>(json.decodeFromString(value)).jsonObject }.getOrNull()
    }.toMap()

    internal fun importAll(values: Map<String, JsonObject>) {
        val encoded = values.mapValues { (_, value) ->
            json.encodeToString(json.decodeFromJsonElement<ModelConfiguration>(value))
        }
        preferences.replaceAll(encoded)
    }
}
