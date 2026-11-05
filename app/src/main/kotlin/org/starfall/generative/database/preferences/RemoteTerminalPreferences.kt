package org.starfall.generative.database.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.starfall.generative.shared.model.remote.TerminalInputMode
import org.starfall.generative.shared.state.ImmediateState
import org.starfall.generative.shared.state.LocalWriteErrors

internal const val REMOTE_TERMINAL_UI_PREFERENCES = "remote_terminal_ui"
internal const val REMOTE_TERMINAL_FONT_SIZE_KEY = "font_dp"
internal const val REMOTE_TERMINAL_FORCE_MOUSE_KEY = "force_mouse"

internal fun remoteTerminalInputModePreferenceKey(machineId: String) = "input_mode:$machineId"
internal fun remoteTerminalImePreferenceKey(machineId: String) = "local_ime:$machineId"

internal data class RemoteTerminalInputPreferences(val mode: TerminalInputMode, val imeEnabled: Boolean)

internal fun Preferences.remoteTerminalInputPreferences(machineId: String): RemoteTerminalInputPreferences {
    val stored = this[stringPreferencesKey(remoteTerminalInputModePreferenceKey(machineId))]
    // Older versions stored IME as a third (local) mode, or used local_ime with LOCAL.
    val mode = if (stored == "IME" || stored == TerminalInputMode.LOCAL.name) {
        TerminalInputMode.LOCAL
    } else TerminalInputMode.REMOTE
    // Keep the saved Local IME choice even when the user switches to Remote, but never
    // activate it while Remote is selected (including preferences saved by older versions).
    return RemoteTerminalInputPreferences(
        mode,
        mode.usesLocalInput && (stored == "IME" || this[booleanPreferencesKey(remoteTerminalImePreferenceKey(machineId))] != false)
    )
}

internal fun MutablePreferences.normalizeLegacyTerminalInput() {
    asMap().toMap().forEach { (key, value) ->
        if (key.name.startsWith("input_mode:") && value == "IME") {
            this[stringPreferencesKey(key.name)] = TerminalInputMode.LOCAL.name
            this[booleanPreferencesKey(remoteTerminalImePreferenceKey(key.name.removePrefix("input_mode:")))] = true
        }
    }
}

private val normalizeTerminalInput = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.asMap().any { (key, value) -> key.name.startsWith("input_mode:") && value == "IME" }

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences()
        .apply { normalizeLegacyTerminalInput() }.toPreferences()

    override suspend fun cleanUp() = Unit
}

internal fun terminalPreferenceMigrations(context: Context): List<DataMigration<Preferences>> =
    listOf(SharedPreferencesMigration(context, REMOTE_TERMINAL_UI_PREFERENCES), normalizeTerminalInput)

private val Context.remoteTerminalDataStore by preferencesDataStore(
    name = "remote_terminal_preferences",
    produceMigrations = ::terminalPreferenceMigrations
)

/** A process-wide writer shared by terminal screens, machine management and backup. */
internal class RemoteTerminalPreferenceStore internal constructor(private val store: DataStore<Preferences>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + LocalWriteErrors.handler)
    private val state = ImmediateState(store.data)
    val data = state.flow

    suspend fun snapshot(): Preferences = data.first()

    suspend fun updateAndWait(change: (MutablePreferences) -> Unit) {
        state.mutate({ old -> old.toMutablePreferences().apply(change).toPreferences() }) {
            store.edit { change(it) }
        }
    }

    fun update(change: (MutablePreferences) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { updateAndWait(change) }
    }

    companion object {
        @Volatile private var instance: RemoteTerminalPreferenceStore? = null

        fun getInstance(context: Context): RemoteTerminalPreferenceStore = instance ?: synchronized(this) {
            instance ?: RemoteTerminalPreferenceStore(context.applicationContext.remoteTerminalDataStore).also { instance = it }
        }
    }
}
