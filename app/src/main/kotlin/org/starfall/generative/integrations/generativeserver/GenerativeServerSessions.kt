package org.starfall.generative.integrations.generativeserver

import java.util.UUID
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put

/** Server-advertised chat restrictions, bounded by the Android wire's supported sizes. */
data class ServerChatCapabilities(
    val maxConversationBytes: Int = 3 shl 20,
    val maxRunContentBytes: Int = 64 shl 10,
    val maxHistoryMessages: Int = 256,
    val attachments: Boolean = false,
    val multimodalInput: Boolean = false,
    val toolLoop: Boolean = false,
    val reasoningHistory: Boolean = false,
    val mobileProviders: Boolean = false,
    val providerOptions: ServerProviderOptions = ServerProviderOptions(),
    val attachmentUpload: ServerAttachmentUploadLimits? = null,
    val serverTools: Boolean = false,
)

/** Session RPCs preserve the complete server snapshot, including unrecognised fields. */
class GenerativeServerSessions(private val client: GenerativeServerClient) {
    private suspend fun request(op: String, sessionId: String? = null, data: JsonObject? = null): JsonElement {
        val reply = client.rpc(RpcRequest(UUID.randomUUID().toString(), op, sessionId, data))
        return GenerativeServerCodec.requireSuccess(reply) ?: throw GenerativeServerProtocolException("Missing RPC data")
    }

    fun close() = client.close()

    /** Reuse the same wire for one user action, including its follow-up reads. */
    internal suspend fun <T> withConnection(block: suspend () -> T): T = client.withConnection(block)

    suspend fun verifyCapabilities(): ServerChatCapabilities {
        val capabilities = request("capabilities.get").jsonObject
        val operations = capabilities["operations"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        val features = capabilities["chat_features"]?.jsonObject
        if (capabilities["protocol"]?.jsonPrimitive?.intOrNull != 5 ||
            !operations.containsAll(setOf("session.list", "session.open", "session.get", "session.configure", "session.run", "session.cancel", "session.events", "session.save", "session.delete", "provider.catalog")) ||
            features?.get("version")?.jsonPrimitive?.intOrNull != 1 || features["text_generation"]?.jsonPrimitive?.booleanOrNull != true ||
            features["streaming"]?.jsonPrimitive?.booleanOrNull != true) {
            throw GenerativeServerProtocolException("Incompatible server capabilities")
        }
        fun bounded(value: kotlinx.serialization.json.JsonElement?, fallback: Int, maximum: Int): Int {
            if (value == null) return fallback // Compatible with earlier v5 servers.
            val parsed = value.jsonPrimitive.intOrNull
                ?: throw GenerativeServerProtocolException("Invalid server capability limit")
            if (parsed !in 1..maximum) throw GenerativeServerProtocolException("Invalid server capability limit")
            return parsed
        }
        val negotiation = features["negotiation"]?.jsonObject
        val generation = negotiation?.get("generation")?.jsonObject
        return ServerChatCapabilities(
            maxConversationBytes = bounded(features["conversation_max_bytes"], 3 shl 20, 8 shl 20),
            maxRunContentBytes = bounded(generation?.get("max_run_content_bytes"), 64 shl 10, 512 shl 10),
            maxHistoryMessages = bounded(generation?.get("max_history_messages"), 256, 100_000),
            attachments = features["attachments"]?.jsonPrimitive?.booleanOrNull == true,
            multimodalInput = features["multimodal_input"]?.jsonPrimitive?.booleanOrNull == true,
            toolLoop = features["generation_tool_loop"]?.jsonPrimitive?.booleanOrNull == true,
            reasoningHistory = features["reasoning_history"]?.jsonPrimitive?.booleanOrNull == true,
            mobileProviders = features["runtime_sources"]?.jsonArray?.any { it.jsonPrimitive.content == "mobile_ephemeral" } == true,
            providerOptions = ServerProviderOptions.fromNegotiation(negotiation),
            attachmentUpload = ServerAttachmentUploadLimits.negotiate(
                features["attachments"]?.jsonPrimitive?.booleanOrNull == true,
                operations,
                negotiation?.get("attachment_upload"),
            ),
            serverTools = "tools.list" in operations,
        )
    }

    suspend fun list(): List<SessionListItem> {
        val result = mutableListOf<SessionListItem>()
        var cursor = ""
        val seen = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        do {
            val data = buildJsonObject { put("pagination", "snapshot"); put("limit", 100); if (cursor.isNotEmpty()) put("cursor", cursor) }
            val page = GenerativeServerCodec.json.decodeFromString<SessionListPage>(request("session.list", data = data).toString())
            if (page.version != 1 || page.items.any { !seen.add(it.id) } ||
                (page.items.isEmpty() && page.nextCursor.isNotEmpty()) || page.items.size > 100 || result.size + page.items.size > 100_000) {
                throw GenerativeServerProtocolException("Invalid session list")
            }
            result.addAll(page.items)
            val next = page.nextCursor
            if (next.isNotEmpty() && !cursors.add(next)) throw GenerativeServerProtocolException("Invalid session cursor")
            cursor = next
        } while (cursor.isNotEmpty())
        return result
    }

    /** A cache hit avoids transferring unchanged conversation JSON, never server validation. */
    suspend fun open(id: String, cached: ServerSnapshot? = null): ServerSnapshot = wholeOrChunked(id, "session.open", cached)
    suspend fun get(id: String, cached: ServerSnapshot? = null): ServerSnapshot = wholeOrChunked(id, "session.get", cached)
    private suspend fun wholeOrChunked(id: String, operation: String, cached: ServerSnapshot?): ServerSnapshot = try {
        val hint = cached?.takeIf { it.sessionId == id && it.revision > 0 && it.conversation["id"]?.jsonPrimitive?.content == id }
        val data = request(operation, id, buildJsonObject { if (hint != null) put("if_revision", hint.revision) })
        val obj = data.jsonObject
        if (obj["unchanged"]?.jsonPrimitive?.booleanOrNull == true) {
            if (hint == null || obj["session_id"]?.jsonPrimitive?.content != id ||
                obj["revision"]?.jsonPrimitive?.long != hint.revision) {
                throw GenerativeServerProtocolException("Unverified cached session")
            }
            val cursor = obj["event_seq"]?.jsonPrimitive?.long
            if (cursor != null && cursor < 0) throw GenerativeServerProtocolException("Invalid cached session cursor")
            hint.copy(eventSeq = cursor)
        } else snapshot(data, id)
    } catch (e: GenerativeServerRpcException) {
        if (e.rpcError.code != "resource_exhausted") throw e
        readChunked(id)
    }
    private suspend fun readChunked(id: String): ServerSnapshot {
        val info = request("session.open_chunked", id, buildJsonObject {}).jsonObject
        val revision = info.getValue("revision").jsonPrimitive.long
        val cursor = info["event_seq"]?.jsonPrimitive?.long
        val size = info.getValue("size").jsonPrimitive.int
        if (info.getValue("version").jsonPrimitive.int != 1 || info.getValue("session_id").jsonPrimitive.content != id || revision <= 0 || (cursor != null && cursor < 0) || size !in 1..MAX_CONVERSATION) throw GenerativeServerProtocolException("Invalid chunk metadata")
        val output = ByteArrayOutputStream(size)
        while (output.size() < size) {
            val offset = output.size()
            val part = request("session.read_chunk", id, buildJsonObject { put("revision", revision); put("offset", offset); put("limit", CHUNK_SIZE) }).jsonObject
            if (part.getValue("revision").jsonPrimitive.long != revision || part.getValue("offset").jsonPrimitive.int != offset) throw GenerativeServerProtocolException("Invalid chunk position")
            val bytes = try { Base64.getDecoder().decode(part.getValue("data").jsonPrimitive.content) } catch (_: Exception) { throw GenerativeServerProtocolException("Invalid chunk data") }
            if (bytes.size != minOf(CHUNK_SIZE, size - offset)) throw GenerativeServerProtocolException("Invalid chunk length")
            output.write(bytes)
            if (part.getValue("done").jsonPrimitive.boolean != (output.size() == size)) throw GenerativeServerProtocolException("Invalid chunk termination")
        }
        val conversation = withContext(Dispatchers.Default) {
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString() } catch (_: Exception) { throw GenerativeServerProtocolException("Invalid chunk UTF-8") }
            try { GenerativeServerCodec.json.parseToJsonElement(text).jsonObject } catch (_: Exception) { throw GenerativeServerProtocolException("Invalid chunked conversation") }
        }
        if (conversation["id"]?.jsonPrimitive?.content != id) throw GenerativeServerProtocolException("Invalid conversation identity")
        return ServerSnapshot(id, revision, conversation, cursor)
    }
    suspend fun configure(id: String, providerId: String, modelId: String, keyId: String? = null) {
        require(providerId.isNotBlank() && modelId.isNotBlank())
        val runtime = buildJsonObject { put("source", "stored"); put("provider_id", providerId); put("model_id", modelId); if (!keyId.isNullOrBlank()) put("key_id", keyId) }
        configureRuntime(id, runtime)
    }
    internal suspend fun configureRuntime(id: String, runtime: JsonObject) {
        request("session.configure", id, buildJsonObject { put("runtime", runtime) })
    }
    suspend fun run(id: String, requestId: String, content: String, attachmentIds: List<String> = emptyList()) {
        require(requestId.isNotBlank() && (content.isNotBlank() || attachmentIds.isNotEmpty()))
        request("session.run", id, buildJsonObject {
            put("request_id", requestId)
            put("content", content)
            if (attachmentIds.isNotEmpty()) put("attachment_ids", JsonArray(attachmentIds.map(::JsonPrimitive)))
        })
    }

    internal suspend fun uploadAttachment(source: ServerAttachmentSource): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val file = source.file
        val size = source.sizeBytes
        val buffer = ByteArray(CHUNK_SIZE)
        file.inputStream().buffered().use { input ->
            var offset = 0L
            do {
                val count = minOf(buffer.size.toLong(), size - offset).toInt()
                var read = 0
                while (read < count) {
                    val amount = input.read(buffer, read, count - read)
                    if (amount < 0) throw IOException("Attachment changed during upload")
                    read += amount
                }
                if (offset + count == size && input.read() != -1) throw IOException("Attachment changed during upload")
                val reply = request("attachment.upload_chunk", data = buildJsonObject {
                    put("upload_id", id)
                    put("name", file.name)
                    put("mime_type", source.mimeType)
                    put("size_bytes", size)
                    put("offset", offset)
                    put("data_base64", Base64.getEncoder().encodeToString(buffer.copyOf(count)))
                }) as? JsonObject
                offset += count
                if ((reply?.get("upload_id") as? JsonPrimitive)?.content != id ||
                    (reply?.get("received_bytes") as? JsonPrimitive)?.longOrNull != offset
                ) throw GenerativeServerProtocolException("Invalid attachment upload acknowledgement")
            } while (offset < size)
        }
        id
    }
    suspend fun cancel(id: String, requestId: String) {
        request("session.cancel", id, buildJsonObject { put("request_id", requestId) })
    }
    suspend fun save(id: String, revision: Long, operationId: String, conversation: JsonObject): ServerSnapshot {
        val bytes = withContext(Dispatchers.Default) { conversation.toString().toByteArray(Charsets.UTF_8) }
        if (bytes.size <= WHOLE_LIMIT) return snapshot(request("session.save", id, buildJsonObject { put("expected_revision", revision); put("idempotency_key", operationId); put("conversation", conversation) }), id)
        if (bytes.size > MAX_CONVERSATION) throw GenerativeServerProtocolException("Conversation too large")
        val digest = withContext(Dispatchers.Default) { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } }
        val begin = request("session.upload.begin", id, buildJsonObject { put("expected_revision", revision); put("idempotency_key", operationId); put("size", bytes.size); put("sha256", digest) }).jsonObject
        if (begin.getValue("version").jsonPrimitive.int != 1 || begin.getValue("chunk_max_bytes").jsonPrimitive.int != CHUNK_SIZE) throw GenerativeServerProtocolException("Invalid upload metadata")
        val token = begin.getValue("upload_id").jsonPrimitive.content
        if (token.isBlank()) throw GenerativeServerProtocolException("Invalid upload identifier")
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + CHUNK_SIZE, bytes.size)
            val encoded = Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end))
            val part = request("session.upload.chunk", id, buildJsonObject { put("upload_id", token); put("offset", offset); put("data", encoded) }).jsonObject
            if (part.getValue("next_offset").jsonPrimitive.int != end) throw GenerativeServerProtocolException("Invalid upload offset")
            offset = end
        }
        val result = request("session.upload.commit", id, buildJsonObject { put("upload_id", token) }).jsonObject
        val nextRevision = result.getValue("revision").jsonPrimitive.long
        if (result.getValue("session_id").jsonPrimitive.content != id || nextRevision != revision + 1 || result.getValue("size").jsonPrimitive.int != bytes.size) throw GenerativeServerProtocolException("Invalid upload commit")
        return ServerSnapshot(id, nextRevision, conversation)
    }
    suspend fun delete(id: String, revision: Long, operationId: String) {
        request("session.delete", id, buildJsonObject { put("expected_revision", revision); put("idempotency_key", operationId) })
    }
    suspend fun supportsCatalogImport(providers: Boolean): Boolean {
        val operation = if (providers) "provider.import" else "mcp.import"
        return operation in catalogOperations()
    }

    internal suspend fun catalogImportKinds(): List<Boolean> {
        val operations = catalogOperations()
        return buildList {
            if ("provider.import" in operations) add(true)
            if ("mcp.import" in operations) add(false)
        }
    }

    private suspend fun catalogOperations(): List<String> =
        request("capabilities.get").jsonObject["operations"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

    suspend fun importProvider(provider: org.starfall.generative.shared.model.provider.ModelProvider) {
        val payload = GenerativeServerCodec.json.encodeToJsonElement(
            org.starfall.generative.shared.model.provider.ModelProvider.serializer(), provider).jsonObject
        // Older servers require an explicit label even when Android's serializer omits the default.
        val config = payload["config"] as? JsonObject
        val keys = config?.get("apiKeys") as? JsonArray
        val compatible = if (config != null && keys != null) JsonObject(payload + ("config" to JsonObject(
            config + ("apiKeys" to JsonArray(keys.map { item ->
                val key = item.jsonObject
                if ("label" in key) key else JsonObject(key + ("label" to JsonPrimitive("")))
            }))
        ))) else payload
        confirmImport("provider.import", compatible)
    }

    suspend fun importMcp(server: org.starfall.generative.shared.model.McpInfo) {
        val payload = GenerativeServerCodec.json.encodeToJsonElement(
            org.starfall.generative.shared.model.McpInfo.serializer(), server).jsonObject
        confirmImport("mcp.import", payload)
    }

    private suspend fun confirmImport(operation: String, payload: JsonObject) {
        if (request(operation, data = payload).jsonObject["imported"]?.jsonPrimitive?.booleanOrNull != true) {
            throw GenerativeServerProtocolException("Server did not confirm import")
        }
    }

    suspend fun mcpCatalog(): List<JsonObject>? = try {
        request("mcp.catalog").jsonArray.map { it.jsonObject }
    } catch (e: GenerativeServerRpcException) {
        if (e.rpcError.code == "unsupported_feature") null else throw e
    }

    internal suspend fun listTools(): List<ServerTool> = decodeServerTools(request("tools.list"))

    suspend fun catalog(): ProviderCatalog {
        val catalog = GenerativeServerCodec.json.decodeFromString<ProviderCatalog>(request("provider.catalog").toString())
        if (catalog.version != 1) throw GenerativeServerProtocolException("Incompatible provider catalog")
        return catalog
    }
    fun events(id: String, after: Long = 0): Flow<SessionEvent> = client.events(id, after)

    private fun snapshot(data: JsonElement, expectedId: String): ServerSnapshot = try {
        val obj = data.jsonObject
        val id = obj.getValue("session_id").jsonPrimitive.content
        val revision = obj.getValue("revision").jsonPrimitive.long
        val cursor = obj["event_seq"]?.jsonPrimitive?.long
        val conversation = obj.getValue("conversation").jsonObject
        require(id == expectedId && revision > 0 && (cursor == null || cursor >= 0) && conversation["id"]?.jsonPrimitive?.content == expectedId)
        ServerSnapshot(id, revision, conversation, cursor)
    } catch (_: Exception) { throw GenerativeServerProtocolException("Invalid session snapshot") }
}

private const val WHOLE_LIMIT = 3 shl 20
private const val MAX_CONVERSATION = 8 shl 20
private const val CHUNK_SIZE = 256 shl 10

/** eventSeq is a live session cursor, not a durable conversation revision. */
data class ServerSnapshot(val sessionId: String, val revision: Long, val conversation: JsonObject, val eventSeq: Long? = null)
