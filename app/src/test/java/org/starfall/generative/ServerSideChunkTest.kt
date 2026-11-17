package org.starfall.generative

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.serverside.*

class ServerSideChunkTest {
    private class Wire(var bytes: ByteArray) : ServerSideWire {
        private var received = ByteArrayOutputStream()
        var writes = 0
        var shortRead = false
        private var expectedHash = ""
        private var expectedSize = 0
        override fun authenticate(identity: PairedIdentity) = AccessToken("token", 900)
        override fun events(token: AccessToken, sessionId: String, after: Long) = emptyList<SessionEvent>()
        override fun rpc(token: AccessToken, request: RpcRequest): RpcReply {
            val data = request.data ?: buildJsonObject { }
            if (request.op == "session.open") return RpcReply(request.id, false, error = RpcError("resource_exhausted", "large"))
            val result = when (request.op) {
                "session.open_chunked" -> buildJsonObject { put("version", 1); put("session_id", "c"); put("revision", 1); put("size", bytes.size) }
                "session.read_chunk" -> { val offset = data.getValue("offset").jsonPrimitive.int; val end = minOf(offset + 256 * 1024, bytes.size)
                    buildJsonObject { put("revision", 1); put("offset", offset); put("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, if (shortRead) end - 1 else end))); put("done", end == bytes.size) } }
                "session.upload.begin" -> { received = ByteArrayOutputStream(); expectedHash = data.getValue("sha256").jsonPrimitive.content; expectedSize = data.getValue("size").jsonPrimitive.int; check(data.getValue("expected_revision").jsonPrimitive.long == 1L); buildJsonObject { put("version", 1); put("upload_id", "upload"); put("chunk_max_bytes", 256 * 1024) } }
                "session.upload.chunk" -> { val offset = data.getValue("offset").jsonPrimitive.int; check(offset == received.size()); received.write(Base64.getDecoder().decode(data.getValue("data").jsonPrimitive.content)); writes++
                    buildJsonObject { put("next_offset", received.size()) } }
                "session.upload.commit" -> { bytes = received.toByteArray(); check(bytes.size == expectedSize); check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } == expectedHash); buildJsonObject { put("session_id", "c"); put("version", 1); put("revision", 2); put("size", bytes.size) } }
                else -> error("unexpected ${request.op}")
            }
            return RpcReply(request.id, true, result)
        }
    }
    @Test fun largeReadAndWriteRoundTripInBoundedChunks() = runBlocking {
        val initial = buildJsonObject { put("id", "c"); put("title", "title"); put("created_at", 1); put("updated_at", 1); put("messages", JsonArray(emptyList())); put("future", "é".repeat(1_600_000)) }
        val wire = Wire(initial.toString().toByteArray())
        val sessions = ServerSideSessions(ServerSideClient(wire, PairedIdentity("id", "secret", "server")))
        val opened = sessions.open("c"); assertEquals(initial, opened.conversation)
        val updated = JsonObject(opened.conversation + ("title" to JsonPrimitive("changed")))
        val saved = sessions.save("c", 1, "op", updated)
        assertEquals(2L, saved.revision); assertEquals(updated, saved.conversation); assertTrue(wire.writes > 1)
        assertEquals(updated, Json.parseToJsonElement(wire.bytes.toString(Charsets.UTF_8)))
    }

    @Test fun rejectsTruncatedChunk() = runBlocking {
        val initial = buildJsonObject { put("id", "c"); put("future", "é".repeat(1_600_000)) }
        val wire = Wire(initial.toString().toByteArray()); wire.shortRead = true
        val sessions = ServerSideSessions(ServerSideClient(wire, PairedIdentity("id", "secret", "server")))
        try { sessions.open("c"); fail("accepted truncated chunk") } catch (_: ServerSideProtocolException) { }
    }
}
