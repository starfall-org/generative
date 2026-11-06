package org.starfall.generative

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.shared.ui.layout.InputAreaAppearance

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class InputAreaAppearanceTest {
    @Test fun defaultPreferencesUseTenPercentTransparencyAndTwoDpBlur() {
        val defaults = Json.decodeFromString<AppPreferences>("{}")
        val appearance = InputAreaAppearance(defaults.inputAreaTransparency, defaults.inputAreaBlur)
        assertEquals(0.9f, appearance.alpha, 0.0001f)
        assertEquals(2f, appearance.blurRadius, 0f)
        assertEquals(appearance, InputAreaAppearance())
        assertEquals(1f, InputAreaAppearance(-1f).surfaceColor(Color.Red).alpha, 0f)
        assertEquals(0f, InputAreaAppearance(2f).surfaceColor(Color.Blue).alpha, 0f)
        assertEquals(32f, InputAreaAppearance(blur = 100f).blurRadius, 0f)
        assertEquals(0f, InputAreaAppearance(blur = -1f).blurRadius, 0f)
    }

    @Test fun changesPersistIndependentlyAndSurviveBackupRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppPreferencesRepository(context)
        val original = repository.appPreferencesFlow.first()
        try {
            repository.setInputAreaTransparency(0.65f)
            repository.setInputAreaBlur(18f)
            val reloaded = AppPreferencesRepository(context).appPreferencesFlow.first()
            assertEquals(0.65f, reloaded.inputAreaTransparency, 0f)
            assertEquals(18f, reloaded.inputAreaBlur, 0f)
            assertEquals(original.itemTransparency, reloaded.itemTransparency, 0f)
            assertEquals(original.userProfile, reloaded.userProfile)
            val backup = Json.decodeFromString<AppPreferences>(Json.encodeToString(reloaded))
            repository.setInputAreaTransparency(0f)
            repository.setInputAreaBlur(0f)
            repository.replace(backup)
            val restored = AppPreferencesRepository(context).appPreferencesFlow.first()
            assertEquals(0.65f, restored.inputAreaTransparency, 0f)
            assertEquals(18f, restored.inputAreaBlur, 0f)
        } finally {
            repository.replace(original)
        }
    }

    @Test fun settersAndRestoreClampOutOfRangeValues() = runBlocking {
        val repository = AppPreferencesRepository(ApplicationProvider.getApplicationContext())
        val original = repository.appPreferencesFlow.first()
        try {
            repository.setInputAreaTransparency(5f)
            repository.setInputAreaBlur(-10f)
            val first = AppPreferencesRepository(ApplicationProvider.getApplicationContext()).appPreferencesFlow.first()
            assertEquals(1f, first.inputAreaTransparency, 0f)
            assertEquals(0f, first.inputAreaBlur, 0f)
            repository.replace(original.copy(inputAreaTransparency = -2f, inputAreaBlur = 100f))
            val restored = AppPreferencesRepository(ApplicationProvider.getApplicationContext()).appPreferencesFlow.first()
            assertEquals(0f, restored.inputAreaTransparency, 0f)
            assertEquals(32f, restored.inputAreaBlur, 0f)
        } finally {
            repository.replace(original)
        }
    }
}
