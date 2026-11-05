package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.preferences.NotificationPermissionStore
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.database.preferences.AppPreferencesRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class NotificationPermissionStoreTest {
    @Test fun previouslyRequestedPermissionRemainsRequestedAfterMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("notification_permission", Context.MODE_PRIVATE)
            .edit().putBoolean("background_requested", true).commit()

        val directory = Files.createTempDirectory("notification-permission-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                migrations = listOf(SharedPreferencesMigration(context, "notification_permission")),
                scope = scope,
                produceFile = { File(directory, "permissions.preferences_pb") }
            )
            val store = NotificationPermissionStore(dataStore)
            assertFalse(store.claimBackgroundRequest())
            assertFalse(NotificationPermissionStore(dataStore).claimBackgroundRequest())
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }

        val store = NotificationPermissionStore(context)
        store.claimBackgroundRequest()
        AppPreferencesRepository(context).replace(AppPreferences())
        assertFalse(store.claimBackgroundRequest())
    }
}
