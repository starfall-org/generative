package org.starfall.generative.shared.helpers.networking

import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Parse response arrays incrementally so one provider turn need not fit in memory. */
internal suspend fun readStoredJson(reader: Reader, store: ToolJsonStore): JsonObject {
    val input = JsonReader(reader)
    suspend fun value(name: String? = null): JsonElement {
        currentCoroutineContext().ensureActive()
        return when (input.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val fields = linkedMapOf<String, JsonElement>()
            input.beginObject()
            while (input.hasNext()) {
                val key = input.nextName()
                fields[key] = value(key)
            }
            input.endObject()
            JsonObject(fields)
        }
        JsonToken.BEGIN_ARRAY -> {
            val items = if (name in setOf("tool_calls", "parts", "content", "output")) store.list()
                else mutableListOf()
            input.beginArray()
            while (input.hasNext()) items.add(value())
            input.endArray()
            JsonArray(items)
        }
        JsonToken.STRING -> JsonPrimitive(input.nextString())
        JsonToken.NUMBER -> Json.parseToJsonElement(input.nextString())
        JsonToken.BOOLEAN -> JsonPrimitive(input.nextBoolean())
        JsonToken.NULL -> { input.nextNull(); JsonNull }
        else -> error("Provider returned invalid JSON")
        }
    }
    val result = value() as? JsonObject ?: error("Provider returned invalid JSON: expected an object")
    check(input.peek() == JsonToken.END_DOCUMENT) { "Provider returned trailing JSON data" }
    return result
}
