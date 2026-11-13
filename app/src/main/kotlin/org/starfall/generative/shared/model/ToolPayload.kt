package org.starfall.generative.shared.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Durable location a [ToolPayload] can be read from again after its RAM copy is dropped. */
interface ToolPayloadSource {
    /** Identity of the stored value. Equal keys must always hold equal text. */
    val key: Any

    /** Reads the complete stored text; throws if the value no longer exists. */
    suspend fun load(): String
}

/**
 * Bounded LRU of payload text that can be recovered from a [ToolPayloadSource].
 * An entry is a convenience copy and is never the only copy of a payload.
 */
object ToolPayloadCache {
    /** Budget in UTF-16 units. A payload larger than this is simply re-read when needed. */
    @Volatile var maxChars: Int = 2_000_000
    private val entries = LinkedHashMap<Any, String>(16, 0.75f, true)
    private var chars = 0L

    @Synchronized fun get(key: Any): String? = entries[key]

    @Synchronized fun put(key: Any, text: String) {
        entries.remove(key)?.let { chars -= it.length }
        if (text.length > maxChars) return
        entries[key] = text
        chars += text.length
        val oldest = entries.entries.iterator()
        while (chars > maxChars && oldest.hasNext()) {
            chars -= oldest.next().value.length
            oldest.remove()
        }
    }

    /** Drops cached text whose key matches [predicate], e.g. after its rows were deleted or replaced. */
    @Synchronized fun invalidate(predicate: (Any) -> Boolean) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) { chars -= entry.value.length; iterator.remove() }
        }
    }

    /** Releases cached text under memory pressure without dropping the recoverable backing store. */
    @Synchronized fun clear() {
        entries.clear()
        chars = 0
    }
}

/**
 * Tool-call arguments or a tool result. The text is held strongly only while it is the sole copy
 * (in flight, unsaved, temporary chat or after a failed save). Once [attachSource] records that the
 * text is durable, the strong reference is released: later reads come from [ToolPayloadCache] or
 * are re-read from the source off the UI thread.
 */
@kotlinx.serialization.Serializable(with = ToolPayloadSerializer::class)
class ToolPayload private constructor(
    @Volatile private var resident: String?,
    @Volatile private var source: ToolPayloadSource?,
    /** Unicode code points, matching SQLite's `length()`, so resident and stored payloads agree. */
    val size: Int
) {
    val isEmpty: Boolean get() = size == 0

    /** True when the text is held only by this object, so it must not be dropped. */
    val isUnrecoverable: Boolean get() = source == null

    /** True while this object itself holds the text (not merely a cache entry). */
    val isResident: Boolean get() = resident != null

    /** Identity of the durable copy, or null while this object holds the only copy. */
    val sourceKey: Any? get() = source?.key

    /** The text if it is available without I/O. Safe on any thread. */
    fun peek(): String? = resident ?: source?.let { ToolPayloadCache.get(it.key) }

    /** The complete text. Reads from the source on the IO dispatcher when it is not in RAM. */
    suspend fun text(): String {
        peek()?.let { return it }
        val origin = checkNotNull(source) { "Tool payload is unavailable" }
        val loaded = withContext(Dispatchers.IO) { origin.load() }
        ToolPayloadCache.put(origin.key, loaded)
        return loaded
    }

    /**
     * Holds the text strongly again, reading it first if needed. A writer calls this before an operation
     * that could delete the row the text is recovered from; [attachSource] releases it after the commit.
     */
    suspend fun pin() {
        if (resident != null || size == 0) return
        resident = text()
    }

    /** The text of a payload that is known to be resident (see [pin]), for a synchronous write. */
    fun residentText(): String = checkNotNull(resident) { "Tool payload must be pinned before it is written" }

    /** Blocking variant for serialization and other already off-main work; never call on the UI thread. */
    fun textBlocking(): String = peek() ?: runBlocking(Dispatchers.IO) { text() }

    /**
     * Records that [origin] now durably holds exactly this text, then releases the strong copy.
     * Call only after the write committed. Both short and long stored values use the bounded LRU;
     * thousands of short tool calls must not accumulate unbounded resident strings.
     */
    fun attachSource(origin: ToolPayloadSource) {
        if (size == 0) return // The shared EMPTY instance has nothing to recover.
        val text = resident
        source = origin
        if (text != null) ToolPayloadCache.put(origin.key, text)
        resident = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolPayload) return false
        val mine = resident
        val theirs = other.resident
        if (mine != null && theirs != null) return mine == theirs
        val a = source?.key
        return a != null && a == other.source?.key
    }

    // Payloads of equal content may be resident or stored, so no content-derived hash is possible.
    override fun hashCode(): Int = 17

    /** Never prints content: payloads can hold secrets and may need I/O to read. */
    override fun toString(): String = "ToolPayload(size=$size)"

    companion object {
        const val SMALL_PAYLOAD_CHARS = 1_024
        val EMPTY = ToolPayload("", null, 0)

        fun of(text: String): ToolPayload =
            if (text.isEmpty()) EMPTY else ToolPayload(text, null, text.codePointCount(0, text.length))

        /** A payload that exists only in [origin]; nothing is held in RAM until it is read. */
        fun stored(size: Int, origin: ToolPayloadSource): ToolPayload = ToolPayload(null, origin, size)

        /** A short persisted payload starts in the bounded cache, never in a permanent strong field. */
        fun stored(text: String, origin: ToolPayloadSource): ToolPayload {
            if (text.isEmpty()) return EMPTY
            ToolPayloadCache.put(origin.key, text)
            return ToolPayload(null, origin, text.codePointCount(0, text.length))
        }
    }
}

fun String.toToolPayload(): ToolPayload = ToolPayload.of(this)

/** Keeps the backup/export format unchanged: a payload is written and read as a plain JSON string. */
object ToolPayloadSerializer : KSerializer<ToolPayload> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ToolPayload", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ToolPayload) = encoder.encodeString(value.textBlocking())
    override fun deserialize(decoder: Decoder): ToolPayload = ToolPayload.of(decoder.decodeString())
}
