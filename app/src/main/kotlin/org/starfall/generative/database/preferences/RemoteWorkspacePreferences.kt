package org.starfall.generative.database.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import kotlinx.coroutines.flow.first

private val Context.remoteWorkspaceStore by preferencesDataStore(name = "remote_chat_workspace")

/** Device-local UI state; contains neither paired credentials nor conversation contents. */
class RemoteWorkspacePreferences(private val context: Context) {
    private val activeKey = stringPreferencesKey("active_connection")
    suspend fun activeConnection(): String? = context.applicationContext.remoteWorkspaceStore.data.first()[activeKey]
    suspend fun setActiveConnection(id: String?) {
        context.applicationContext.remoteWorkspaceStore.edit { prefs -> if (id == null) prefs.remove(activeKey) else prefs[activeKey] = id }
    }
    private fun key(connectionId: String) = stringPreferencesKey("last_" + MessageDigest.getInstance("SHA-256")
        .digest(connectionId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) })
    suspend fun lastSession(connectionId: String): String? = context.applicationContext.remoteWorkspaceStore.data.first()[key(connectionId)]
    suspend fun setLastSession(connectionId: String, sessionId: String?) {
        context.applicationContext.remoteWorkspaceStore.edit { prefs ->
            val key = key(connectionId)
            if (sessionId == null) prefs.remove(key) else prefs[key] = sessionId
        }
    }
}
