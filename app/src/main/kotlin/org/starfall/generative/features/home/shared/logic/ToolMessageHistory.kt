package org.starfall.generative.features.home.shared.logic

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.shared.helpers.networking.ToolJsonStore
import org.starfall.generative.shared.helpers.networking.text

/** Keep assistant arrays lazy while loading individual tool results only when visited. */
internal class ToolMessageHistory(
    store: ToolJsonStore,
    private val storeMessage: (JsonObject) -> Boolean = { it.text("role") == "tool" }
) : AbstractMutableList<JsonObject>() {
    private sealed interface Entry {
        data class InMemory(val value: JsonObject) : Entry
        data class Stored(val index: Int) : Entry
    }

    private val stored: MutableList<JsonElement> = store.list()
    private val entries = mutableListOf<Entry>()
    override val size: Int get() = entries.size
    override fun get(index: Int): JsonObject = when (val entry = entries[index]) {
        is Entry.InMemory -> entry.value
        is Entry.Stored -> stored[entry.index] as JsonObject
    }

    private fun entry(value: JsonObject): Entry = if (storeMessage(value)) {
        val index = stored.size
        stored.add(value)
        Entry.Stored(index)
    } else Entry.InMemory(value)

    override fun add(index: Int, element: JsonObject) { entries.add(index, entry(element)) }
    override fun set(index: Int, element: JsonObject): JsonObject {
        val previous = get(index)
        entries[index] = entry(element)
        return previous
    }
    override fun removeAt(index: Int): JsonObject {
        val previous = get(index)
        entries.removeAt(index)
        return previous
    }
}
