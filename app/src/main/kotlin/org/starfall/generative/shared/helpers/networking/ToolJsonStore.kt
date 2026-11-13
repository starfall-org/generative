package org.starfall.generative.shared.helpers.networking

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.Reader

/** Storage for the lifetime of one tool generation; implementations may load entries on demand. */
internal interface ToolJsonStore {
    fun list(): MutableList<JsonElement>
    fun indexed(): MutableMap<Int, JsonElement>
    fun textBuffer(): ToolTextBuffer
}

internal interface ToolTextBuffer : Closeable {
    val length: Long
    fun append(value: Char)
    fun reader(): Reader
    fun clear()
}

internal fun ToolJsonStore?.elementList(): MutableList<JsonElement> = this?.list() ?: mutableListOf()
internal fun ToolJsonStore?.indexedElements(): MutableMap<Int, JsonElement> = this?.indexed() ?: linkedMapOf()

/** Transform an immutable JSON array as its entries are visited, without copying the array. */
internal fun JsonArray.mapOnAccess(transform: (JsonElement) -> JsonElement): JsonArray =
    JsonArray(object : AbstractList<JsonElement>() {
        override val size: Int get() = this@mapOnAccess.size
        override fun get(index: Int): JsonElement = transform(this@mapOnAccess[index])
    })

/** Store only new argument fragments instead of rewriting all previous chunks for each delta. */
internal class ToolArgumentChunks(private val store: ToolJsonStore?) {
    private class Chunks(val values: MutableList<JsonElement>, var length: Int = 0)
    private val chunks = mutableMapOf<Int, Chunks>()

    fun length(index: Int): Int = chunks[index]?.length ?: 0

    fun append(index: Int, value: String) {
        val entry = chunks.getOrPut(index) { Chunks(store.elementList()) }
        if (value.isNotEmpty()) entry.values.add(str(value))
        entry.length += value.length
    }

    fun read(index: Int): String? = chunks[index]?.let { entry ->
        buildString(entry.length) { entry.values.forEach { append(it.jsonPrimitive.content) } }
    }
}
