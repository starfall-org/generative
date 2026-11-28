package org.starfall.generative.integrations.opencode

import java.io.EOFException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenCode 2.x HTTP API. Requests are bound to the configured origin and never retried. */
class OpenCodeApi(endpoint: String, private val credentials: OpenCodeCredentials) {
    private val transport = OpenCodeAuthentication(endpoint)

    suspend fun defaultDirectory(): String = string(obj(request("GET", listOf("api", "location"))), "directory")

    suspend fun session(sessionId: String): JsonObject =
        obj(obj(request("GET", listOf("api", "session", validId(sessionId)))).required("data"))

    suspend fun projects(): List<OpenCodeProject> = array(request("GET", listOf("api", "project"))).map { value ->
        val project = obj(value)
        val id = string(project, "id")
        val directory = string(project, "canonical")
        OpenCodeProject(id, project["name"]?.let { stringValue(it) }.orEmpty().ifBlank { directory }, directory)
    }

    /** Paths are interpreted by the server, never by the Android filesystem. */
    suspend fun directories(path: String): List<String> {
        val response = obj(request("GET", listOf("api", "fs", "list"), mapOf("path" to path)))
        return array(response.required("data")).mapNotNull { value ->
            val entry = obj(value)
            if (string(entry, "type") == "directory") string(entry, "path") else null
        }
    }

    suspend fun models(directory: String): List<JsonObject> {
        val response = obj(request("GET", listOf("api", "model"), mapOf("location[directory]" to directory)))
        return array(response.required("data")).map(::obj)
    }

    /** A null directory uses the server's default location. */
    suspend fun providers(directory: String? = null): List<JsonObject> = locationList("provider", directory)

    suspend fun mcpServers(directory: String? = null): List<JsonObject> = locationList("mcp", directory)

    suspend fun integration(id: String, directory: String? = null): JsonObject =
        obj(obj(request("GET", listOf("api", "integration", validId(id)), locationQuery(directory))).required("data"))

    suspend fun connectIntegrationKey(id: String, key: String, answer: JsonObject? = null, directory: String? = null) {
        require(key.isNotBlank())
        request("POST", listOf("api", "integration", validId(id), "connect", "key"), locationQuery(directory),
            buildJsonObject { put("key", key); if (answer != null) put("answer", answer) })
    }

    suspend fun beginIntegrationOAuth(id: String, methodId: String, answer: JsonObject? = null, directory: String? = null): JsonObject {
        require(methodId.isNotBlank())
        return obj(obj(request("POST", listOf("api", "integration", validId(id), "connect", "oauth"), locationQuery(directory),
            buildJsonObject { put("methodID", methodId); if (answer != null) put("answer", answer) })).required("data"))
    }

    suspend fun integrationOAuthStatus(id: String, attemptId: String, directory: String? = null): JsonObject =
        obj(obj(request("GET", listOf("api", "integration", validId(id), "connect", "oauth", validId(attemptId)), locationQuery(directory))).required("data"))

    suspend fun completeIntegrationOAuth(id: String, attemptId: String, code: String?, directory: String? = null) {
        request("POST", listOf("api", "integration", validId(id), "connect", "oauth", validId(attemptId), "complete"),
            locationQuery(directory), buildJsonObject { if (code != null) put("code", code) })
    }

    suspend fun cancelIntegrationOAuth(id: String, attemptId: String, directory: String? = null) {
        request("DELETE", listOf("api", "integration", validId(id), "connect", "oauth", validId(attemptId)), locationQuery(directory))
    }

    /** OpenCode runtime overrides last only until the server restarts. */
    suspend fun setMcpConnected(name: String, connected: Boolean, directory: String? = null) {
        request("POST", listOf("api", "experimental", "mcp", validId(name),
            if (connected) "connect" else "disconnect"), locationQuery(directory))
    }

    suspend fun addMcp(name: String, config: JsonObject, directory: String? = null) {
        request("PUT", listOf("api", "experimental", "mcp", validId(name)), locationQuery(directory),
            buildJsonObject { put("config", config) })
    }

    suspend fun removeMcp(name: String, directory: String? = null) {
        request("DELETE", listOf("api", "experimental", "mcp", validId(name)), locationQuery(directory))
    }

    private suspend fun configDocuments(directory: String?): List<JsonObject> =
        array(request("GET", listOf("api", "config"), locationQuery(directory))).map(::obj)

    suspend fun providerConfigFile(directory: String?): OpenCodeProviderConfigFile =
        selectOpenCodeProviderConfig(configDocuments(directory), directory)

    internal suspend fun catalogConfigEntry(path: List<String>, directory: String?): OpenCodeCatalogEntry {
        val file = selectOpenCodeProviderConfig(configDocuments(directory), directory, path)
        val value = requireNotNull(openCodeConfigObject(readConfig(file), path))
        return OpenCodeCatalogEntry(file, path, value)
    }

    suspend fun addConfiguredProvider(file: OpenCodeProviderConfigFile, id: String, config: JsonObject,
        directory: String?): Boolean {
        require(providerConfigFile(directory) == file)
        require(providers(directory).none { it["id"]?.jsonPrimitive?.content == id })
        val raw = readConfig(file)
        return writeConfig(file, insertOpenCodeProvider(raw, id, config))
    }

    internal suspend fun changeCatalogConfigEntry(
        entry: OpenCodeCatalogEntry, replacement: JsonObject?, directory: String?, newKey: String? = null
    ): Boolean {
        val documents = configDocuments(directory)
        val file = selectOpenCodeProviderConfig(documents, directory, entry.path)
        if (newKey != null) {
            validId(newKey)
            val newPath = entry.path.dropLast(1) + newKey
            require(documents.none { (it["info"] as? JsonObject)?.configObject(newPath) != null }) { "Name already exists" }
        }
        require(file == entry.file) { "Configuration source changed" }
        val raw = readConfig(file)
        val updated = replaceOpenCodeConfigEntry(raw, entry, replacement, newKey)
        if (updated == raw) return true
        // The endpoint has no conditional write. Detect a change observed just before the write.
        require(readConfig(file) == raw) { "Configuration file changed before saving" }
        return writeConfig(file, updated)
    }

    private suspend fun readConfig(file: OpenCodeProviderConfigFile): String =
        try {
            rawRequest("GET", listOf("api", "fs", "read", file.name), locationQuery(file.parent))
        } catch (e: OpenCodeHttpException) {
            if (e.status != 404 || file.exists) throw e
            "{}"
        }

    private suspend fun writeConfig(file: OpenCodeProviderConfigFile, raw: String): Boolean {
        val written = rawRequest("POST", listOf("api", "experimental", "fs", "write"),
            locationQuery(file.parent) + ("path" to file.path), raw.toRequestBody("application/octet-stream".toMediaType()))
        val confirmedPath = string(obj(obj(Json.parseToJsonElement(written)).required("data")), "path")
        require(confirmedPath == file.path) { "Unexpected written config path" }
        // Reload may fail independently of the write. Do not retry the mutation.
        return try {
            request("POST", listOf("api", "location", "reload"))
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    private fun locationQuery(directory: String?): Map<String, String> =
        directory?.let { require(it.isNotBlank()); mapOf("location[directory]" to it) } ?: emptyMap()

    private suspend fun locationList(resource: String, directory: String?): List<JsonObject> {
        val response = obj(request("GET", listOf("api", resource), locationQuery(directory)))
        return array(response.required("data")).map(::obj)
    }

    suspend fun sessions(directory: String): List<JsonObject> = pages(
        listOf("api", "session"), mapOf("directory" to directory, "order" to "desc"))

    suspend fun messages(sessionId: String): List<JsonObject> = messages(sessionId) { it }

    internal suspend fun <T> messages(sessionId: String, transform: suspend (JsonObject) -> T): List<T> = pages(
        listOf("api", "session", validId(sessionId), "message"), mapOf("order" to "asc"),
        attachmentHistory = true, transform = transform
    )

    suspend fun createSession(directory: String, providerId: String, modelId: String): JsonObject {
        require(directory.isNotBlank())
        val body = buildJsonObject {
            put("location", buildJsonObject { put("directory", directory) })
            put("model", modelRef(providerId, modelId))
        }
        return obj(obj(request("POST", listOf("api", "session"), body = body)).required("data"))
    }

    suspend fun setModel(sessionId: String, providerId: String, modelId: String) {
        val body = buildJsonObject { put("model", modelRef(providerId, modelId)) }
        request("POST", listOf("api", "session", validId(sessionId), "model"), body = body)
    }

    private fun modelRef(providerId: String, modelId: String): JsonObject {
        require(providerId.isNotBlank() && modelId.isNotBlank())
        return buildJsonObject { put("providerID", providerId); put("id", modelId) }
    }

    /** An accepted prompt is never automatically retried: the server may already have executed it. */
    suspend fun prompt(sessionId: String, text: String): String = prompt(sessionId, text, emptyList())

    internal suspend fun prompt(sessionId: String, text: String, files: List<OpenCodePromptFile>): String {
        require(text.isNotBlank() || files.isNotEmpty())
        val response = obj(request("POST", listOf("api", "session", validId(sessionId), "prompt"),
            body = buildJsonObject {
                put("text", text)
                if (files.isNotEmpty()) put("files", buildJsonArray {
                    files.forEach { file ->
                        add(buildJsonObject {
                            put("uri", file.uri)
                            put("name", file.name)
                        })
                    }
                })
            }))
        return validId(string(obj(response.required("data")), "id"))
    }

    suspend fun rename(sessionId: String, title: String) {
        require(title.isNotBlank())
        request("PATCH", listOf("api", "session", validId(sessionId)), body = buildJsonObject { put("title", title) })
    }

    suspend fun delete(sessionId: String) {
        request("DELETE", listOf("api", "session", validId(sessionId)))
    }

    suspend fun interrupt(sessionId: String) {
        request("POST", listOf("api", "session", validId(sessionId), "interrupt"))
    }

    /** Volatile server events: callers must reload authoritative state after disconnection. */
    fun events(): Flow<JsonObject> = channelFlow {
        val url = transport.base.newBuilder().addPathSegments("api/event").build()
        val request = Request.Builder().url(url).header("Accept", "text/event-stream")
            .header("Authorization", credentials.authorization()).get().build()
        val client = transport.client.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val call = client.newCall(request)
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (response.code !in 200..299) throw OpenCodeHttpException(response.code)
                    if (response.header("Content-Type")?.substringBefore(';')?.trim()
                            ?.equals("text/event-stream", ignoreCase = true) != true) {
                        throw OpenCodeProtocolException("Invalid event stream type")
                    }
                    val source = response.body.source()
                    val data = StringBuilder()
                    while (true) {
                        val line = try { source.readUtf8LineStrict(MAX_EVENT_BYTES) } catch (_: EOFException) {
                            if (source.buffer.size != 0L) throw OpenCodeProtocolException("Invalid event stream")
                            break
                        }
                        if (line.isEmpty()) {
                            if (data.isNotEmpty()) {
                                val event = try { Json.parseToJsonElement(data.toString()).jsonObject }
                                catch (_: Exception) { throw OpenCodeProtocolException("Invalid event") }
                                send(event)
                                data.clear()
                            }
                        } else if (line.startsWith("data:")) {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").removePrefix(" "))
                            if (data.length > MAX_EVENT_BYTES) throw OpenCodeProtocolException("Event too large")
                        }
                    }
                }
                close()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { call.cancel(); reader.cancel() }
    }

    private suspend fun pages(path: List<String>, query: Map<String, String>): List<JsonObject> {
        return pages(path, query, attachmentHistory = false) { it }
    }

    private suspend fun <T> pages(
        path: List<String>, query: Map<String, String>, attachmentHistory: Boolean,
        transform: suspend (JsonObject) -> T,
    ): List<T> {
        val output = mutableListOf<T>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var limit = 100
        do {
            val parameters = (cursor?.let { query - "order" + ("cursor" to it) } ?: query) + ("limit" to limit.toString())
            val page = try {
                val maxBytes = if (attachmentHistory && limit == 1) 32L shl 20 else MAX_RESPONSE_BYTES
                obj(request("GET", path, parameters, maxResponseBytes = maxBytes))
            } catch (tooLarge: OpenCodeResponseTooLarge) {
                if (!attachmentHistory || limit == 1) throw tooLarge
                // History embeds Base64. Retry only this read with fewer messages per page.
                limit = (limit / 2).coerceAtLeast(1)
                continue
            }
            val items = array(page.required("data")).map(::obj)
            if (items.size > limit || output.size + items.size > 100_000) throw OpenCodeProtocolException("Invalid page size")
            items.forEach { output.add(transform(it)) }
            val nextValue = obj(page.required("cursor"))["next"]
            cursor = if (nextValue == null || nextValue is JsonNull) null else stringValue(nextValue).takeIf { it.isNotEmpty() }
            if (cursor != null && (items.isEmpty() || !cursors.add(cursor))) throw OpenCodeProtocolException("Invalid page cursor")
            if (cursor == null) return output
        } while (true)
    }

    private suspend fun request(method: String, path: List<String>, query: Map<String, String> = emptyMap(),
        body: JsonObject? = null, maxResponseBytes: Long = MAX_RESPONSE_BYTES): JsonElement {
        val response = rawRequest(method, path, query,
            body?.toString()?.toRequestBody("application/json".toMediaType()), maxResponseBytes)
        if (response.isEmpty()) return JsonNull
        return try { Json.parseToJsonElement(response) } catch (_: Exception) { throw OpenCodeProtocolException("Invalid API response") }
    }

    private suspend fun rawRequest(method: String, path: List<String>, query: Map<String, String> = emptyMap(),
        body: RequestBody? = null, maxResponseBytes: Long = MAX_RESPONSE_BYTES): String {
        val url = transport.base.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
            .header("Authorization", credentials.authorization())
        val request = when (method) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(body ?: "".toRequestBody("application/json".toMediaType())).build()
            "PUT" -> builder.put(requireNotNull(body)).build()
            "PATCH" -> builder.patch(requireNotNull(body)).build()
            "DELETE" -> builder.delete().build()
            else -> error("Unsupported method")
        }
        return transport.request(request, maxResponseBytes)
    }

    private fun validId(value: String): String = value.also { require(it.isNotBlank() && '/' !in it && it != "." && it != "..") }
    private fun JsonObject.required(key: String): JsonElement = this[key] ?: throw OpenCodeProtocolException("Missing API field")
    private fun obj(value: JsonElement): JsonObject = try { value.jsonObject } catch (_: Exception) { throw OpenCodeProtocolException("Invalid API object") }
    private fun array(value: JsonElement): JsonArray = try { value.jsonArray } catch (_: Exception) { throw OpenCodeProtocolException("Invalid API list") }
    private fun string(value: JsonObject, key: String): String = stringValue(value.required(key))
    private fun stringValue(value: JsonElement): String = try {
        value.jsonPrimitive.takeIf { it.isString }?.content ?: throw IllegalArgumentException()
    } catch (_: Exception) { throw OpenCodeProtocolException("Invalid API text") }
}

data class OpenCodeProject(val id: String, val name: String, val directory: String)

private const val MAX_EVENT_BYTES = 1L shl 20

internal data class OpenCodePromptFile(val uri: String, val name: String)

internal const val OPEN_CODE_MAX_ATTACHMENT_BYTES = 20L shl 20
