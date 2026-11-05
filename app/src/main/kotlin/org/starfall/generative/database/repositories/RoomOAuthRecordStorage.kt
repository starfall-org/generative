package org.starfall.generative.database.repositories


import org.starfall.generative.database.room.AppDatabase
import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.starfall.generative.database.room.entities.OAuthTokenRecordEntity
import org.starfall.generative.integrations.security.OAuthRecordStorage
import org.starfall.generative.database.preferences.MigratedStringPreferences

/**
 * Durable, encrypted OAuth records in Room.
 *
 * Old builds used SharedPreferences, then Preferences DataStore. Reading the transitional
 * DataStore runs its SharedPreferencesMigration if needed. The encrypted bytes are copied
 * transactionally to Room before either legacy store is cleared. Existing Room rows win,
 * so a crash during migration cannot overwrite refreshed tokens on the next attempt.
 *
 * Synchronous methods preserve the existing OAuth adapter contract. SQL runs on IO.
 */
class RoomOAuthRecordStorage(
    context: Context,
    private val database: AppDatabase
) : OAuthRecordStorage {
    private val appContext = context.applicationContext
    private val dao = database.oauthTokenDao()
    private val lock = Any()
    private val migrated = mutableSetOf<String>()

    private fun <T> access(namespace: String, block: suspend () -> T): T = synchronized(lock) {
        require(namespace.startsWith("generative.") && namespace.endsWith(".oauth")) {
            "Unexpected OAuth token namespace."
        }
        runBlocking(Dispatchers.IO) {
            migrate(namespace)
            block()
        }
    }

    private suspend fun migrate(namespace: String) {
        if (namespace in migrated) return
        val previous = MigratedStringPreferences.get(appContext, namespace)
        val oldRecords = previous.all
        if (oldRecords.isNotEmpty()) {
            database.withTransaction {
                oldRecords.forEach { (id, payload) ->
                    dao.insertMigrated(OAuthTokenRecordEntity(namespace, id, payload))
                }
            }
            // Only clear after Room committed all records successfully.
            previous.replaceAll(emptyMap())
        }
        migrated += namespace
    }

    override fun get(namespace: String, id: String): String? = access(namespace) {
        dao.get(namespace, id)
    }

    override fun all(namespace: String): Map<String, String> = access(namespace) {
        dao.all(namespace).associate { it.recordId to it.encryptedPayload }
    }

    override fun put(namespace: String, id: String, encrypted: String) {
        access(namespace) {
            dao.put(OAuthTokenRecordEntity(namespace, id, encrypted))
        }
    }

    override fun remove(namespace: String, id: String) {
        access(namespace) { dao.remove(namespace, id) }
    }

    override fun replace(namespace: String, encrypted: Map<String, String>) {
        access(namespace) {
            database.withTransaction {
                dao.clear(namespace)
                encrypted.forEach { (id, value) ->
                    dao.put(OAuthTokenRecordEntity(namespace, id, value))
                }
            }
        }
    }
}
