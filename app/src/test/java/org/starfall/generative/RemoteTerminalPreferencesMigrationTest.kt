package org.starfall.generative

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.preferences.REMOTE_TERMINAL_UI_PREFERENCES
import org.starfall.generative.database.preferences.RemoteTerminalPreferenceStore
import org.starfall.generative.database.preferences.terminalPreferenceMigrations

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class RemoteTerminalPreferencesMigrationTest {
    @Test fun legacyTerminalSettingsAndImeModeSurviveMigrationAndQueuedUpdates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(REMOTE_TERMINAL_UI_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("input_mode:machine", "IME")
            .putBoolean("local_ime:machine", false)
            .putInt("font_dp", 19)
            .putBoolean("force_mouse", true)
            .commit()
        val directory = Files.createTempDirectory("terminal-preferences-migration").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                migrations = terminalPreferenceMigrations(context), scope = scope,
                produceFile = { File(directory, "terminal.preferences_pb") }
            )
            val preferences = dataStore.data.first()
            assertEquals("LOCAL", preferences[stringPreferencesKey("input_mode:machine")])
            assertEquals(true, preferences[booleanPreferencesKey("local_ime:machine")])
            assertEquals(19, preferences[intPreferencesKey("font_dp")])
            assertEquals(true, preferences[booleanPreferencesKey("force_mouse")])

            val store = RemoteTerminalPreferenceStore(dataStore)
            store.update { it[intPreferencesKey("font_dp")] = 22 }
            store.updateAndWait { it[booleanPreferencesKey("force_mouse")] = false }
            val updated = store.snapshot()
            assertEquals(22, updated[intPreferencesKey("font_dp")])
            assertEquals(false, updated[booleanPreferencesKey("force_mouse")])
            assertEquals("LOCAL", dataStore.data.first()[stringPreferencesKey("input_mode:machine")])
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
