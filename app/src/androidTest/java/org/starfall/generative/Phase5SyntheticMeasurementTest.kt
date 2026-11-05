package org.starfall.generative

import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.database.room.AppDatabase

/**
 * Synthetic-only Android measurement. It opens an in-memory Room database and
 * never opens the app's named database or an authorized existing database.
 */
class Phase5SyntheticMeasurementTest {
    @Test
    fun syntheticDatabaseReportsRowsAndMetadataSelectedHistoryMetrics() = runBlocking<Unit> {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val sql = database.openHelper.writableDatabase
            insertConversation(sql, "synthetic-selected", 3)
            insertConversation(sql, "synthetic-unrelated", 2)
            insertConversation(sql, "synthetic-empty", 1)
            insertMessages(sql, "synthetic-selected", 2)
            insertMessages(sql, "synthetic-unrelated", 2)

            val baseline = measure(database, "synthetic-selected")
            insertMessages(sql, "synthetic-unrelated", 4, 2)
            val afterUnrelatedGrowth = measure(database, "synthetic-selected")
            insertMessages(sql, "synthetic-selected", 2, 2)
            val afterSelectedGrowth = measure(database, "synthetic-selected")

            assertEquals(10, sql.version)
            assertEquals(3, baseline.metadataRows)
            assertEquals(2, baseline.selectedHistoryRows)
            assertEquals(baseline.metadataRows, afterUnrelatedGrowth.metadataRows)
            assertEquals(baseline.selectedHistoryRows, afterUnrelatedGrowth.selectedHistoryRows)
            assertEquals(4, afterSelectedGrowth.selectedHistoryRows)
            assertTrue(sql.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })

            val counts = tableCounts(sql)
            Log.i(TAG, "SYNTHETIC_ONLY schemaVersion=${sql.version} tableRows=$counts " +
                "baseline=${baseline.render()} afterUnrelated=${afterUnrelatedGrowth.render()} " +
                "afterSelected=${afterSelectedGrowth.render()}")
        } finally {
            database.close()
        }
    }

    private suspend fun measure(database: AppDatabase, selectedId: String): Metrics {
        var metadataRows = 0
        val metadataNanos = measureNanoTime {
            metadataRows = database.conversationDao().getConversationListItems().size
        }
        var selectedHistoryRows = 0
        val selectedNanos = measureNanoTime {
            selectedHistoryRows = database.conversationDao().getMessages(selectedId).size
        }
        return Metrics(
            metadataRows,
            selectedHistoryRows,
            TimeUnit.NANOSECONDS.toMicros(metadataNanos),
            TimeUnit.NANOSECONDS.toMicros(selectedNanos)
        )
    }

    private fun insertConversation(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String, updatedAt: Long) {
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt, tokenCount, providerId, modelId, profileId, reasoningEffort) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, "Synthetic conversation", 0L, updatedAt, null, "synthetic-provider", "synthetic-model", null, null)
        )
    }

    private fun insertMessages(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        conversationId: String,
        count: Int,
        offset: Int = 0
    ) {
        repeat(count) { index ->
            val messageId = "$conversationId-message-${offset + index}"
            db.execSQL(
                "INSERT INTO messages (conversationId, id, sequence, role, activeVersionIndex) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(conversationId, messageId, offset + index, "user", 0)
            )
            db.execSQL(
                "INSERT INTO message_versions (conversationId, messageId, versionIndex, content, timestamp, reasoningContent, reasoningSignature, processingFinishedAt, providerId, modelId, modelDisplayName, generationInterrupted, generationError) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(conversationId, messageId, 0, "Synthetic body", "2026-01-01T00:00:00Z", null, null, null,
                    "synthetic-provider", "synthetic-model", "Synthetic model", 0, null)
            )
        }
    }

    private fun tableCounts(db: androidx.sqlite.db.SupportSQLiteDatabase): Map<String, Int> {
        val names = buildList {
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'room_%' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { rows ->
                while (rows.moveToNext()) add(rows.getString(0))
            }
        }
        return names.associateWith { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { rows ->
                check(rows.moveToFirst())
                rows.getInt(0)
            }
        }
    }

    private data class Metrics(
        val metadataRows: Int,
        val selectedHistoryRows: Int,
        val metadataMicros: Long,
        val selectedMicros: Long
    ) {
        fun render(): String = "metadataRows=$metadataRows selectedHistoryRows=$selectedHistoryRows " +
            "metadataMicros=$metadataMicros selectedHistoryMicros=$selectedMicros"
    }

    private companion object {
        const val TAG = "Phase5SyntheticMeasurement"
    }
}
