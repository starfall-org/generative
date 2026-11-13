package org.starfall.generative.integrations.modelapis

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.io.Reader
import java.io.StringReader
import java.io.Writer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.starfall.generative.shared.helpers.networking.ToolJsonStore
import org.starfall.generative.shared.helpers.networking.ToolTextBuffer

/** Private, generation-scoped append-only storage. The in-memory indexes contain offsets only. */
internal class ToolJsonFileStore(private val directory: File) : ToolJsonStore, Closeable {
    companion object {
        private val initialized = mutableSetOf<String>()

        fun inCache(cacheDir: File): ToolJsonFileStore {
            val root = File(cacheDir, "tool_turns")
            synchronized(initialized) {
                check(root.isDirectory || root.mkdirs()) { "Cannot create tool cache" }
                if (initialized.add(root.canonicalPath)) {
                    root.listFiles()?.filter { it.name.endsWith(".spill") }?.forEach { it.delete() }
                }
            }
            return ToolJsonFileStore(root)
        }
    }

    private val file = File.createTempFile("tool_calls_", ".spill", directory)
    private val data = RandomAccessFile(file, "rw")

    @Synchronized
    private fun write(value: JsonElement): Long {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        val offset = data.length()
        data.seek(offset)
        data.writeInt(bytes.size)
        data.write(bytes)
        return offset
    }

    @Synchronized
    private fun read(offset: Long): JsonElement {
        data.seek(offset)
        val bytes = ByteArray(data.readInt())
        data.readFully(bytes)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
    }

    override fun list(): MutableList<JsonElement> = object : AbstractMutableList<JsonElement>() {
        private val offsets = mutableListOf<Long>()
        override val size: Int get() = offsets.size
        override fun get(index: Int): JsonElement = read(offsets[index])
        override fun add(index: Int, element: JsonElement) { offsets.add(index, write(element)) }
        override fun set(index: Int, element: JsonElement): JsonElement {
            val old = read(offsets[index])
            offsets[index] = write(element)
            return old
        }
        override fun removeAt(index: Int): JsonElement = read(offsets.removeAt(index))
    }

    override fun indexed(): MutableMap<Int, JsonElement> = object : AbstractMutableMap<Int, JsonElement>() {
        private val offsets = linkedMapOf<Int, Long>()
        override fun get(key: Int): JsonElement? = offsets[key]?.let(::read)
        override fun put(key: Int, value: JsonElement): JsonElement? {
            val old = offsets.put(key, write(value))
            return old?.let(::read)
        }
        override val entries: MutableSet<MutableMap.MutableEntry<Int, JsonElement>> =
            object : AbstractMutableSet<MutableMap.MutableEntry<Int, JsonElement>>() {
                override val size: Int get() = offsets.size
                override fun add(element: MutableMap.MutableEntry<Int, JsonElement>): Boolean =
                    throw UnsupportedOperationException()
                override fun iterator(): MutableIterator<MutableMap.MutableEntry<Int, JsonElement>> {
                    val keys = offsets.keys.iterator()
                    return object : MutableIterator<MutableMap.MutableEntry<Int, JsonElement>> {
                        override fun hasNext(): Boolean = keys.hasNext()
                        override fun next(): MutableMap.MutableEntry<Int, JsonElement> {
                            val index = keys.next()
                            return object : MutableMap.MutableEntry<Int, JsonElement> {
                                override val key: Int = index
                                override val value: JsonElement get() = get(index)!!
                                override fun setValue(newValue: JsonElement): JsonElement = put(index, newValue)!!
                            }
                        }
                        override fun remove() = keys.remove()
                    }
                }
            }
    }

    override fun textBuffer(): ToolTextBuffer = object : ToolTextBuffer {
        private val memory = StringBuilder()
        private var spill: File? = null
        private var writer: Writer? = null
        override var length: Long = 0
            private set

        override fun append(value: Char) {
            if (writer == null && memory.length >= 64 * 1024) {
                val file = File.createTempFile("tool_event_", ".spill", directory)
                spill = file
                writer = file.bufferedWriter().also { it.append(memory) }
                memory.setLength(0)
            }
            writer?.append(value) ?: memory.append(value)
            length++
        }

        override fun reader(): Reader {
            writer?.flush()
            return spill?.bufferedReader() ?: StringReader(memory.toString())
        }

        override fun clear() {
            try { writer?.close() } finally { spill?.delete() }
            writer = null
            spill = null
            memory.setLength(0)
            length = 0
        }

        override fun close() = clear()
    }

    override fun close() {
        try { data.close() } finally { file.delete() }
    }
}
