package org.starfall.generative.shared.files

import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.system.exitProcess

@Serializable
internal data class CrashReport(
    val id: String,
    val timestamp: Long,
    val appVersion: String,
    val device: String,
    val androidVersion: String,
    val thread: String,
    val cause: String,
    val stackTrace: String
) {
    /**
     * Opens GitHub's "new issue" form prefilled with only the essentials. The whole report lives in
     * the URL, so the stack trace is condensed to exception headers plus the app's own frames and the
     * encoded URL is kept under [MAX_ISSUE_URL_LENGTH]; otherwise GitHub rejects the request as too long.
     */
    fun issueUri(): Uri {
        val title = "Crash: ${cause.lineSequence().first().take(140)}"
        var frames = MAX_FRAMES_PER_CAUSE
        var trace = coreStackTrace(stackTrace, frames)
        var uri = buildIssueUri(title, trace)
        while (uri.toString().length > MAX_ISSUE_URL_LENGTH && frames > 1) {
            trace = coreStackTrace(stackTrace, --frames)
            uri = buildIssueUri(title, trace)
        }
        while (uri.toString().length > MAX_ISSUE_URL_LENGTH && trace.length > 200) {
            trace = trace.take(trace.length * 3 / 4)
            uri = buildIssueUri(title, trace)
        }
        return uri
    }

    /** Complete, untruncated report for saving to a file; the issue link only carries a condensed copy. */
    fun logText(): String = listOf(
        "Generative crash report",
        "App: $appVersion",
        "Device: $device",
        "Android: $androidVersion",
        "Time: ${Date(timestamp)}",
        "Thread: $thread",
        "",
        "Cause:",
        cause,
        "",
        "Stack trace:",
        stackTrace
    ).joinToString("\n") + "\n"

    fun logFileName(): String =
        "generative-crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestamp))}.log"

    private fun buildIssueUri(title: String, trace: String): Uri {
        val body = listOf(
            "## Crash report",
            "- App: $appVersion",
            "- Device: $device",
            "- Android: $androidVersion",
            "- Thread: $thread",
            "",
            "## Steps to reproduce",
            "Please describe what you were doing before the crash.",
            "",
            "## Cause",
            cause.take(500),
            "",
            "## Stack trace (condensed)",
            "```text",
            trace,
            "```"
        ).joinToString("\n")
        return Uri.parse("https://github.com/starfall-org/generative/issues/new").buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", body)
            .build()
    }
}

private const val APP_PACKAGE = "org.starfall.generative"
private const val MAX_FRAMES_PER_CAUSE = 6
private const val MAX_LINE_LENGTH = 200
private const val MAX_ISSUE_URL_LENGTH = 6500

/**
 * Reduces a full stack trace to what is needed to triage: each exception header ("Caused by: ..."),
 * and up to [maxFrames] frames per exception, preferring the app's own frames over framework noise.
 */
internal fun coreStackTrace(stackTrace: String, maxFrames: Int = MAX_FRAMES_PER_CAUSE): String {
    fun isFrame(line: String) = line.trimStart().startsWith("at ")
    fun isElision(line: String) = line.trimStart().startsWith("...")

    val out = mutableListOf<String>()
    var header: String? = null
    var frames = mutableListOf<String>()

    fun flush() {
        val h = header ?: return
        out += h.take(MAX_LINE_LENGTH)
        val own = frames.filter { it.contains(APP_PACKAGE) }
        val chosen = if (own.isNotEmpty()) own.take(maxFrames) else frames.take(minOf(3, maxFrames))
        chosen.forEach { out += "\t${it.trim().take(MAX_LINE_LENGTH)}" }
        val omitted = frames.size - chosen.size
        if (omitted > 0) out += "\t... $omitted more frames omitted"
        header = null
        frames = mutableListOf()
    }

    for (line in stackTrace.lineSequence()) {
        when {
            line.isBlank() || isElision(line) -> Unit
            isFrame(line) -> if (header != null) frames += line
            else -> { flush(); header = line }
        }
    }
    flush()
    return out.joinToString("\n")
}

/** Synchronous, atomic storage: the process may be terminated immediately after the handler returns. */
internal class CrashReportStore(file: File, private val appVersion: String = "unknown") {
    private val file = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun record(thread: Thread, error: Throwable) {
        val causes = generateSequence(error) { it.cause }.take(16).toList()
        val rootCause = causes.last()
        val report = CrashReport(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            appVersion = appVersion,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            thread = thread.name,
            cause = "${rootCause.javaClass.name}: ${rootCause.message.orEmpty()}".take(2000),
            stackTrace = error.stackTraceToString().take(32000)
        )
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(report).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    @Synchronized
    fun pending(): CrashReport? = runCatching {
        json.decodeFromString<CrashReport>(file.openRead().use { input ->
            input.reader(Charsets.UTF_8).readText()
        })
    }.getOrNull()

    @Synchronized
    fun dismiss(id: String) {
        // Dismissing an older dialog must not erase a newer background crash.
        if (pending()?.id == id) file.delete()
    }
}

internal class CrashRecordingHandler(
    private val store: CrashReportStore,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            runCatching { store.record(thread, error) }
        } finally {
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
