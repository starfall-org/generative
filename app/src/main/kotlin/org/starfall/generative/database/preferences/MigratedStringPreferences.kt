package org.starfall.generative.database.preferences

import android.content.Context
import org.starfall.generative.shared.state.preferences.StringPreferences
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.IdentityHashMap

/**
 * DataStore-backed string records for legacy stores whose public APIs are synchronous.
 *
 * One instance per file keeps a process-wide read-through snapshot so routine reads do not
 * block rendering or network requests. Writes finish durably before returning, preserving
 * SharedPreferences.commit() and backup/restore ordering. The initial disk read runs on IO.
 * Use suspend-first repositories for new settings rather than this compatibility adapter.
 *
 * Values containing secrets must already be encrypted by their owning store before saving.
 */
internal class MigratedStringPreferences private constructor(
    context: Context,
    legacyFile: String
) : StringPreferences {
    private val store = PreferenceDataStoreFactory.create(
        migrations = listOf(SharedPreferencesMigration(context, legacyFile)),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { context.preferencesDataStoreFile("${legacyFile}_migrated") }
    )
    private val lock = Any()
    private var cached: Map<String, String>? = null

    private fun snapshot(): Map<String, String> = cached ?: runBlocking(Dispatchers.IO) {
        store.data.first().asMap().mapNotNull { (key, value) ->
            (value as? String)?.let { key.name to it }
        }.toMap()
    }.also { cached = it }

    override val all: Map<String, String>
        get() = synchronized(lock) { snapshot().toMap() }

    override fun getString(key: String, default: String?): String? =
        synchronized(lock) { snapshot()[key] ?: default }

    override fun contains(key: String): Boolean = synchronized(lock) { key in snapshot() }

    override fun putString(key: String, value: String) = update { put(key, value) }

    override fun remove(key: String) = update { remove(key) }

    override fun replaceAll(values: Map<String, String>) = update {
        clear()
        putAll(values)
    }

    /** The entire change is one atomic DataStore transaction. Failed writes leave cache unchanged. */
    override fun update(change: MutableMap<String, String>.() -> Unit) {
        synchronized(lock) {
            val old = snapshot()
            val next = old.toMutableMap().apply(change).toMap()
            if (old == next) return
            runBlocking(Dispatchers.IO) {
                store.edit { preferences ->
                    preferences.clear()
                    next.forEach { (key, value) -> preferences[stringPreferencesKey(key)] = value }
                }
            }
            cached = next
        }
    }

    companion object {
        // Application identity also isolates Robolectric application instances across tests.
        private val instances = IdentityHashMap<Context, MutableMap<String, MigratedStringPreferences>>()

        fun get(context: Context, legacyFile: String): MigratedStringPreferences {
            require(legacyFile.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid preferences file name" }
            val appContext = context.applicationContext
            return synchronized(instances) {
                instances.getOrPut(appContext) { mutableMapOf() }
                    .getOrPut(legacyFile) { MigratedStringPreferences(appContext, legacyFile) }
            }
        }
    }
}
