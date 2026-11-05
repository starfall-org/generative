package org.starfall.generative.database.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit

internal val backgroundNotificationRequestedKey = booleanPreferencesKey("background_requested")

/** Records whether the background notification permission has already been requested. */
class NotificationPermissionStore internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.dataStore)

    /** Atomically claims the first request, including after an upgrade from SharedPreferences. */
    suspend fun claimBackgroundRequest(): Boolean {
        var claimed = false
        store.edit { preferences ->
            if (preferences[backgroundNotificationRequestedKey] != true) {
                preferences[backgroundNotificationRequestedKey] = true
                claimed = true
            }
        }
        return claimed
    }
}
