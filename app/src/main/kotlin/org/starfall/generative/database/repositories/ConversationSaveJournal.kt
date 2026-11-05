package org.starfall.generative.database.repositories

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.starfall.generative.shared.model.Conversation

/** Roll-forward record for an explicitly requested save; never written by a timer or live chunk. */
@OptIn(ExperimentalSerializationApi::class)
internal class ConversationSaveJournal(private val directory: File) {
    private fun record(id: String) = AtomicFile(File(directory,
        UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString() + ".json"))

    fun write(conversation: Conversation) {
        if (conversation.isTemporary) return
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create conversation save journal")
        val file = record(conversation.id)
        val output = file.startWrite()
        try {
            json.encodeToStream(Conversation.serializer(), conversation, output)
            file.finishWrite(output) // AtomicFile flushes/syncs before publishing the complete record.
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    fun read(id: String): Conversation? = read(record(id))

    fun pending(): List<AtomicFile> = directory.listFiles().orEmpty()
        .map { it.name.removeSuffix(".bak") }
        .filter { it.endsWith(".json") }.distinct()
        .map { AtomicFile(File(directory, it)) }

    fun read(file: AtomicFile): Conversation? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { json.decodeFromStream(Conversation.serializer(), it) }
    }

    fun remove(id: String) = record(id).delete()
    fun clear() { pending().forEach { it.delete() }; directory.listFiles().orEmpty().forEach { it.delete() } }
}
