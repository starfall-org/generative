package org.starfall.generative.integrations.media

import java.io.File
import java.io.Reader
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.text

/** SSE data is spooled to disk; a single image event can contain hundreds of MB of base64. */
internal suspend fun ToolHttp.postMedia(url: String, body: JsonObject, provider: ModelProvider): JsonObject =
    postMedia(url, body.toString().toRequestBody("application/json".toMediaType()), provider)

internal suspend fun ToolHttp.postMedia(url: String, body: RequestBody, provider: ModelProvider): JsonObject =
    withContext(Dispatchers.IO) {
        val call = client.newCall(request(url, provider).post(body).build())
        execute(call).use { response ->
            val closeOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                requireSuccess(response)
                val store = requireFiles()
                val reader = response.body.charStream()
                if (response.header("Content-Type").orEmpty().contains("text/event-stream", true)) {
                    readImageEvents(reader, store)
                } else Json.parseToJsonElement(store.sanitize(reader)).jsonObject
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally { closeOnCancel.cancel() }
        }
    }

private suspend fun readImageEvents(reader: Reader, files: ToolFiles): JsonObject {
    val input = reader.buffered()
    val eventFile = File(files.directory, "${UUID.randomUUID()}.part")
    val results = mutableListOf<JsonElement>()
    var eventCount = 0
    var eventBytes = 0L
    var output = eventFile.bufferedWriter()
    suspend fun dispatch() {
        output.close()
        try {
            if (eventBytes > 0) {
                check(++eventCount <= 128) { "Too many image stream events" }
                val done = eventFile.length() < 20 && eventFile.readText().trim() == "[DONE]"
                if (!done) {
                    val event = eventFile.reader().use { Json.parseToJsonElement(files.sanitize(it)).jsonObject }
                    val type = event.text("type")
                    if (type.endsWith(".partial_image")) {
                        val names = Regex("tool-file:([a-zA-Z0-9._-]+)").findAll(event.toString()).map { it.groupValues[1] }.toList()
                        files.delete(names)
                    } else {
                        check(type != "error" && event["error"] == null) { "Image generation failed during streaming" }
                        if (type.endsWith(".completed") || event.containsKey("b64_json") || event.containsKey("data")) {
                            check(results.size < 10) { "Too many image results" }
                            results += event
                        }
                    }
                }
            }
        } finally { eventBytes = 0; output = eventFile.bufferedWriter() }
    }
    try {
        var ended = false
        while (!ended) {
            currentCoroutineContext().ensureActive()
            // Only retain the short SSE field prefix, never read a whole data line into memory.
            val prefix = StringBuilder()
            var c = input.read()
            if (c < 0) break
            if (c == '\r'.code) continue
            if (c == '\n'.code) { dispatch(); continue }
            while (c >= 0 && c != ':'.code && c != '\n'.code && c != '\r'.code) {
                if (prefix.length < 64) prefix.append(c.toChar())
                c = input.read()
            }
            val data = prefix.toString() == "data" && c == ':'.code
            if (c == ':'.code) {
                c = input.read()
                if (c == ' '.code) c = input.read()
            }
            var lineBytes = 0L
            while (c >= 0 && c != '\n'.code && c != '\r'.code) {
                if (++lineBytes % 32768L == 0L) {
                    currentCoroutineContext().ensureActive()
                    check(files.directory.usableSpace > 64L * 1024 * 1024) { "Not enough media storage" }
                }
                check(lineBytes <= 512L * 1024 * 1024) { "Image event exceeds size limit" }
                if (data) {
                    check(++eventBytes <= 512L * 1024 * 1024) { "Image event exceeds size limit" }
                    output.write(c)
                }
                c = input.read()
            }
            // CRLF: consume LF so it does not dispatch an event before its blank separator.
            if (c == '\r'.code) { input.mark(1); if (input.read() != '\n'.code) input.reset() }
            if (data) output.write('\n'.code)
            ended = c < 0
        }
        if (eventBytes > 0) dispatch()
        check(results.isNotEmpty()) { "Image stream ended without a completed image" }
        return obj("data" to JsonArray(results))
    } finally { output.close(); eventFile.delete() }
}
