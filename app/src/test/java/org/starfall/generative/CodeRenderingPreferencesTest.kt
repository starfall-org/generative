package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.database.preferences.WordWrapMode
import org.starfall.generative.database.preferences.MessageFontFamily

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CodeRenderingPreferencesTest {
    @Test fun renderingPreferencesSurviveReopeningAndInvalidColumnDoesNotOverwriteThem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppPreferencesRepository(context)
        repository.setWordWrapMode(WordWrapMode.BOUNDED)
        repository.setWordWrapColumn(37)
        repository.setCodePreviewEnabled(false)
        repository.setMessageFontSize(20)
        repository.setMessageFontFamily(MessageFontFamily.SERIF)
        val restored = AppPreferencesRepository(context).appPreferencesFlow.first()
        assertEquals(WordWrapMode.BOUNDED, restored.wordWrapMode)
        assertEquals(37, restored.wordWrapColumn)
        assertFalse(restored.codePreviewEnabled)
        assertEquals(20, restored.messageFontSize)
        assertEquals(MessageFontFamily.SERIF, restored.messageFontFamily)
        try {
            repository.setWordWrapColumn(0)
            fail("Zero columns must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(37, AppPreferencesRepository(context).appPreferencesFlow.first().wordWrapColumn)
    }

    @Test fun messageFontSizeRejectsValuesOutsideSupportedRange() = runBlocking {
        val repository = AppPreferencesRepository(ApplicationProvider.getApplicationContext())
        try {
            repository.setMessageFontSize(11)
            fail("Font sizes below 12sp must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            repository.setMessageFontSize(25)
            fail("Font sizes above 24sp must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
