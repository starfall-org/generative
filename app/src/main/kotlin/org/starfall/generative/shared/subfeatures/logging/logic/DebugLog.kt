package org.starfall.generative.shared.subfeatures.logging.logic

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

data class DebugLogEntry(
    val id: Long,
    val timeMillis: Long,
    val category: DebugLogCategory,
    val level: DebugLogLevel,
    val tag: String,
    val message: String,
    val summary: String? = null,
    val thread: String = "",
    val finishedAtMillis: Long? = null,
    val request: Boolean = false,
    val operation: Boolean = false,
    val method: String? = null,
    val endpoint: String? = null,
    val statusCode: Int? = null,
    val stackTrace: String? = null
) {
    val time: String get() = TIME_FORMAT.format(Instant.ofEpochMilli(timeMillis))
    val dateTime: String get() = DATE_FORMAT.format(Instant.ofEpochMilli(timeMillis))
    val durationMillis: Long? get() = finishedAtMillis?.let { (it - timeMillis).coerceAtLeast(0L) }
    val preview: String get() = (summary ?: message).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    fun format(): String = buildString {
        append('[').append(time).append("] [").append(category.name).append("] [")
            .append(level.name).append("] ").append(tag).append('\n').append(message)
        append("\n\nEvent ID: ").append(id)
        append("\nTimestamp: ").append(dateTime)
        append("\nThread: ").append(thread)
        if (request || operation) {
            append("\nState: ").append(if (finishedAtMillis == null) "Pending" else if (level == DebugLogLevel.ERROR) "Failed" else "Completed")
            durationMillis?.let { append("\nDuration: ").append(it).append(" ms") }
        }
        stackTrace?.let { append("\n\nStack trace:\n").append(it) }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX")
            .withZone(ZoneId.systemDefault())
    }
}

/**
 * In-memory, bounded log shown by the floating debug panel. Nothing is recorded while debug logs are
 * off or the entry's category is not in scope, and the message lambda is then never evaluated.
 */
object DebugLog {
    const val MAX_ENTRIES = 500
    const val MAX_MESSAGE_CHARS = 16_000
    const val MAX_TRACE_CHARS = 32_000
    const val MAX_EXPORT_CHARS = 500_000

    private val nextId = AtomicLong()
    private val _entries = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val entries: StateFlow<List<DebugLogEntry>> = _entries.asStateFlow()

    @Volatile private var scopes: Set<DebugLogCategory> = emptySet()
    @Volatile var minimumLevel: DebugLogLevel = DebugLogLevel.WARN
        private set

    /** Applies the user's settings. Turning debug logs off also drops what was captured. */
    fun configure(enabled: Boolean, categories: Set<DebugLogCategory>,
        minLevel: DebugLogLevel = DebugLogLevel.WARN) {
        val newScopes = if (enabled) categories.toSet() else emptySet()
        val changed = newScopes != scopes || minLevel != minimumLevel
        scopes = newScopes
        minimumLevel = minLevel
        if (changed || !enabled) clear()
    }

    fun isEnabled(category: DebugLogCategory): Boolean = category in scopes
    fun isEnabled(category: DebugLogCategory, level: DebugLogLevel): Boolean =
        category in scopes && level.priority >= minimumLevel.priority

    /** Android Logcat is the log sink; the bounded list is only a viewer for app-owned events. */
    private fun publish(entry: DebugLogEntry) {
        Log.println(entry.level.priority, "Generative",
            buildString {
                append('[').append(entry.category.name).append("] [")
                    .append(entry.tag).append("] ").append(entry.preview)
                entry.stackTrace?.let { append('\n').append(it) }
            }.take(3500))
    }

    fun log(
        category: DebugLogCategory,
        tag: String,
        level: DebugLogLevel = DebugLogLevel.INFO,
        message: () -> String
    ) {
        if (!isEnabled(category, level)) return
        val text = try { message() } catch (e: Exception) { "(log message unavailable: ${e.message})" }
        val safe = safeText(text)
        val entry = DebugLogEntry(nextId.incrementAndGet(), System.currentTimeMillis(), category, level, tag, safe,
            thread = Thread.currentThread().name)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
        runCatching { publish(entry) }
    }

    fun error(category: DebugLogCategory, tag: String, message: String, error: Throwable? = null) {
        if (!isEnabled(category, DebugLogLevel.ERROR)) return
        val safe = safeText(if (error == null) message else
            "$message: ${error::class.java.simpleName}: ${error.message}")
        val trace = error?.let { bounded(DebugLogRedactor.redact(it.stackTraceToString()), MAX_TRACE_CHARS) }
        val entry = DebugLogEntry(nextId.incrementAndGet(), System.currentTimeMillis(), category,
            DebugLogLevel.ERROR, tag, safe, thread = Thread.currentThread().name, stackTrace = trace)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
        runCatching { publish(entry) }
    }

    /** Correlates a non-HTTP operation with its eventual result without adding a second log row. */
    fun beginOperation(category: DebugLogCategory, tag: String, summary: String, details: String = ""): Long? {
        if (!isEnabled(category)) return null
        val safeSummary = safeText(summary)
        val entry = DebugLogEntry(nextId.incrementAndGet(), System.currentTimeMillis(), category,
            DebugLogLevel.INFO, tag,
            listOf(safeSummary, details.takeIf(String::isNotBlank)?.let(::safeText))
                .filterNotNull().joinToString("\n"), safeSummary,
            thread = Thread.currentThread().name, operation = true)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
        return entry.id
    }

    fun finishOperation(id: Long?, summary: String, failed: Boolean = false,
        details: String = "", error: Throwable? = null) {
        if (id == null || scopes.isEmpty()) return
        val safeSummary = safeText(summary)
        val safeDetails = details.takeIf(String::isNotBlank)?.let(::safeText)
        finishEntry(id, safeSummary, failed, safeDetails, error)
    }

    /** One entry per HTTP call, updated in place when the response arrives. */
    internal fun beginRequest(tag: String, label: String,
        method: String? = null, endpoint: String? = null): Long? {
        if (!isEnabled(DebugLogCategory.NETWORK)) return null
        val summary = safeText("→ $label")
        val entry = DebugLogEntry(nextId.incrementAndGet(), System.currentTimeMillis(),
            DebugLogCategory.NETWORK, DebugLogLevel.INFO, tag, summary, summary,
            thread = Thread.currentThread().name, request = true,
            method = method, endpoint = endpoint?.let(DebugLogRedactor::redact))
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
        return entry.id
    }

    internal fun finishRequest(id: Long?, tag: String, summary: String, failed: Boolean,
        error: Throwable? = null, statusCode: Int? = null) {
        val safeSummary = DebugLogRedactor.redact(summary)
        if (id == null) {
            if (failed) error(DebugLogCategory.NETWORK, tag, safeSummary, error)
            return
        }
        if (scopes.isEmpty()) return
        finishEntry(id, safeSummary, failed, null, error, statusCode)
    }

    private fun finishEntry(id: Long, summary: String, failed: Boolean, details: String?,
        error: Throwable? = null, statusCode: Int? = null) {
        val finishedAt = System.currentTimeMillis()
        val trace = error?.let { bounded(DebugLogRedactor.redact(it.stackTraceToString()), MAX_TRACE_CHARS) }
        _entries.update { entries -> entries.mapNotNull { entry ->
            if (entry.id != id) entry else {
                val completed = entry.copy(
                    level = if (failed) DebugLogLevel.ERROR else DebugLogLevel.INFO,
                    finishedAtMillis = finishedAt,
                    stackTrace = trace ?: entry.stackTrace,
                    statusCode = statusCode ?: entry.statusCode,
                    summary = summary,
                    message = listOf(entry.message, summary, details).filterNotNull().filter(String::isNotBlank)
                        .joinToString("\n\n").let { bounded(it, MAX_MESSAGE_CHARS) }
                )
                completed.takeIf { isEnabled(it.category, it.level) }
            }
        } }
        _entries.value.firstOrNull { it.id == id }?.let { runCatching { publish(it) } }
    }

    private fun safeText(text: String): String = bounded(DebugLogRedactor.redact(text), MAX_MESSAGE_CHARS)

    private fun bounded(text: String, limit: Int): String = if (text.length <= limit) text
        else text.take(limit) + "\n… (${text.length - limit} more characters)"

    fun clear() {
        _entries.value = emptyList()
    }

    /** Newest entries that fit the clipboard limit, oldest first. */
    fun exportAll(entries: List<DebugLogEntry> = _entries.value): String {
        val picked = ArrayDeque<String>()
        var size = 0
        for (entry in entries.asReversed()) {
            val text = entry.format()
            if (picked.isNotEmpty() && size + text.length > MAX_EXPORT_CHARS) break
            picked.addFirst(text)
            size += text.length + 2
        }
        return picked.joinToString("\n\n")
    }
}
