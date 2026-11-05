package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.preferences.MigratedStringPreferences
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MigratedStringPreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun legacyValuesAreMigratedOnceAndSubsequentWritesAreDurable() {
        val fileName = "migrated-string-test-${UUID.randomUUID()}"
        val legacy = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        assertTrue(legacy.edit().putString("one", "old-value").putString("two", "keep").commit())

        val first = MigratedStringPreferences.get(context, fileName)
        assertEquals(mapOf("one" to "old-value", "two" to "keep"), first.all)
        assertFalse(legacy.contains("one"))
        assertFalse(legacy.contains("two"))

        first.putString("one", "new-value")
        first.remove("two")
        assertEquals("new-value", MigratedStringPreferences.get(context, fileName).getString("one"))
        assertFalse(first.contains("two"))

        first.replaceAll(mapOf("three" to "restored"))
        assertEquals(mapOf("three" to "restored"), first.all)
        assertEquals(mapOf("three" to "restored"), MigratedStringPreferences.get(context, fileName).all)
        assertTrue(legacy.all.isEmpty())
    }

    @Test fun failedUpdateDoesNotDestroyPreviouslyCommittedData() {
        val fileName = "migrated-string-test-${UUID.randomUUID()}"
        val store = MigratedStringPreferences.get(context, fileName)
        store.putString("keep", "value")
        try {
            store.update {
                remove("keep")
                error("decode failed")
            }
            fail("Expected a failed transaction")
        } catch (_: IllegalStateException) {
        }
        assertEquals(mapOf("keep" to "value"), store.all)
    }
}
