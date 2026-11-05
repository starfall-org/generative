package org.starfall.generative.database.preferences


import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.validateMusicSecrets
import org.starfall.generative.shared.state.ImmediateState

private val Context.toolDataStore by preferencesDataStore(name = "tool_settings")
class ToolSettingsStore(context: Context) {
    private val store = context.applicationContext.toolDataStore
    private val key = stringPreferencesKey("config")
    private val json = Json { ignoreUnknownKeys = true }
    private val stored = store.data.map { p -> runCatching { json.decodeFromString<ToolSettings>(p[key] ?: "{}") }.getOrDefault(ToolSettings()) }
    private val state = ImmediateState(stored)
    val settings = state.flow

    suspend fun snapshot(): ToolSettings = settings.first()

    suspend fun update(transform: (ToolSettings) -> ToolSettings) {
        state.mutate({ transform(it).validateMusicSecrets() }) {
            store.edit { p ->
                val old = runCatching { json.decodeFromString<ToolSettings>(p[key] ?: "{}") }.getOrDefault(ToolSettings())
                p[key] = json.encodeToString(transform(old).validateMusicSecrets())
            }
        }
    }

    suspend fun replace(settings: ToolSettings) {
        settings.validateMusicSecrets()
        state.mutate({ settings }) {
            store.edit { preferences ->
                preferences.clear()
                preferences[key] = json.encodeToString(settings)
            }
        }
    }
}
