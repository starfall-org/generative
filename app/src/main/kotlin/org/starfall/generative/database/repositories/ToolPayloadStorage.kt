package org.starfall.generative.database.repositories

import androidx.room.withTransaction
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.shared.model.ToolPayloadSource

internal enum class ToolPayloadField { ARGUMENTS, RESPONSE }

/**
 * Identifies one stored tool payload. The activity id and size are part of the key so that a row
 * which is later overwritten by a different call can never be mistaken for the one that was cached.
 */
internal data class ToolPayloadKey(
    val conversationId: String,
    val messageId: String,
    val versionIndex: Int,
    val activityIndex: Int,
    val activityId: String,
    val field: ToolPayloadField,
    val size: Int
)

/** Reads a tool payload from Room in bounded chunks; callers run it off the UI thread. */
internal class RoomToolPayloadSource(
    private val db: AppDatabase,
    override val key: ToolPayloadKey
) : ToolPayloadSource {
    private val dao = db.conversationDao()

    // One transaction keeps the chunks consistent even if the row is rewritten while reading.
    override suspend fun load(): String = db.withTransaction {
        val builder = StringBuilder()
        var start = 1
        while (true) {
            val part = chunk(start)
            if (part == null) error("Tool payload ${key.field} of ${key.activityId} is no longer stored")
            if (part.isEmpty()) break
            builder.append(part)
            val length = part.codePointCount(0, part.length)
            start += length
            if (length < CHUNK_CODE_POINTS) break
        }
        // The size can read short for text with NUL characters (SQLite length() stops there), so it is
        // only a sanity check that something was stored; the activity id in the key guards identity.
        check(builder.isNotEmpty()) { "Tool payload ${key.field} of ${key.activityId} is empty in storage" }
        builder.toString()
    }

    private suspend fun chunk(start: Int): String? = when (key.field) {
        ToolPayloadField.ARGUMENTS -> dao.getToolArgumentsChunk(
            key.conversationId, key.messageId, key.versionIndex, key.activityIndex, key.activityId, start, CHUNK_CODE_POINTS)
        ToolPayloadField.RESPONSE -> dao.getToolResponseChunk(
            key.conversationId, key.messageId, key.versionIndex, key.activityIndex, key.activityId, start, CHUNK_CODE_POINTS)
    }

    private companion object {
        const val CHUNK_CODE_POINTS = 16_384 // SQLite substr() counts code points, not UTF-16 units.
    }
}
