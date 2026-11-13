package org.starfall.generative.database.repositories

import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import org.starfall.generative.shared.model.Conversation

/** Read-only compatibility for JSON save records left by older application releases. */
@OptIn(ExperimentalSerializationApi::class)
internal class LegacyConversationSaveJournal(private val directory: File) {
    private fun record(id: String) = AtomicFile(File(directory,
        UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString() + ".json"))


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
