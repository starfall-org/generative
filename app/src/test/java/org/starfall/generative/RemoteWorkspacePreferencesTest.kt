package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.preferences.RemoteWorkspacePreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class RemoteWorkspacePreferencesTest {
    @Test fun lastSessionIsDurableAndIsolatedPerConnection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RemoteWorkspacePreferences(context)
        val first = "pref-first-test"; val second = "pref-second-test"
        try {
            store.setLastSession(first, "chat-a"); store.setLastSession(second, "chat-b")
            val reopened = RemoteWorkspacePreferences(context)
            assertEquals("chat-a", reopened.lastSession(first)); assertEquals("chat-b", reopened.lastSession(second))
            reopened.setLastSession(first, null)
            assertNull(store.lastSession(first)); assertEquals("chat-b", store.lastSession(second))
        } finally { store.setLastSession(first, null); store.setLastSession(second, null) }
    }

    @Test fun remoteActivationCanBeRestoredAndExplicitlyCleared() = runBlocking {
        val store = RemoteWorkspacePreferences(ApplicationProvider.getApplicationContext<Context>())
        try {
            store.setActiveConnection("active-test")
            assertEquals("active-test", RemoteWorkspacePreferences(ApplicationProvider.getApplicationContext<Context>()).activeConnection())
            store.setActiveConnection(null); assertNull(store.activeConnection())
        } finally { store.setActiveConnection(null) }
    }
}
