package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.serverside.*

class ServerSideSessionsTest {
    private class Wire : ServerSideWire {
        val requests = mutableListOf<RpcRequest>()
        override fun authenticate(identity: PairedIdentity) = AccessToken("bearer", 900)
        override fun events(token: AccessToken, sessionId: String, after: Long) = emptyList<SessionEvent>()
        override fun rpc(token: AccessToken, request: RpcRequest): RpcReply {
            requests += request
            val payload = when (request.op) {
                "provider.import", "mcp.import" -> """{"imported":true}"""
                "session.save" -> """{"session_id":"chat","revision":8,"conversation":{"id":"chat","future":{"keep":true}}}"""
                else -> """{"session_id":"chat"}"""
            }
            return RpcReply(request.id, true, Json.parseToJsonElement(payload))
        }
    }
    @Test fun importSendsFullSelectedConfigurationAndCredentials() = runBlocking {
        val wire = Wire()
        val sessions = ServerSideSessions(ServerSideClient(wire, PairedIdentity("c", "secret", "s")))
        val provider = Json.decodeFromString<org.starfall.generative.shared.model.provider.ModelProvider>(
            """{"id":"provider","name":"Provider","type":"openai","baseUrl":"https://example.invalid/v1","auth":{"method":"bearer_token","value":"dummy-key"},"config":{"modelIds":["a","b"],"maxTokens":8192}}"""
        )
        val mcp = Json.decodeFromString<org.starfall.generative.shared.model.McpInfo>(
            """{"id":"mcp","name":"Tools","url":"https://example.invalid/mcp","auth":{"method":"CUSTOM_HEADER","key":"X-Token","value":"dummy-mcp-key"}}"""
        )
        sessions.importProvider(provider)
        sessions.importMcp(mcp)
        assertEquals(listOf("provider.import", "mcp.import"), wire.requests.map { it.op })
        assertEquals(provider, ServerSideCodec.json.decodeFromString<org.starfall.generative.shared.model.provider.ModelProvider>(wire.requests[0].data.toString()))
        assertEquals(mcp, ServerSideCodec.json.decodeFromString<org.starfall.generative.shared.model.McpInfo>(wire.requests[1].data.toString()))
    }

    @Test fun configureAndCasPreserveUnknownFields() = runBlocking {
        val wire = Wire(); val sessions = ServerSideSessions(ServerSideClient(wire, PairedIdentity("c", "secret", "s")))
        sessions.configure("chat", "provider", "model", "key")
        val runtime = wire.requests.last().data!!.getValue("runtime").jsonObject
        assertEquals("stored", runtime.getValue("source").jsonPrimitive.content)
        assertEquals("key", runtime.getValue("key_id").jsonPrimitive.content)
        val original = Json.parseToJsonElement("""{"id":"chat","future":{"keep":true}}""").jsonObject
        val saved = sessions.save("chat", 7, "operation", original)
        assertEquals(8L, saved.revision)
        val sent = wire.requests.last().data!!
        assertEquals("7", sent.getValue("expected_revision").jsonPrimitive.content)
        assertEquals("operation", sent.getValue("idempotency_key").jsonPrimitive.content)
        assertEquals(original, sent.getValue("conversation"))
        assertEquals(original, saved.conversation)
    }

    @Test fun rejectsSnapshotsFromAnotherSession() = runBlocking {
        for (payload in listOf(
            """{"session_id":"other","revision":1,"conversation":{"id":"chat"}}""",
            """{"session_id":"chat","revision":1,"conversation":{"id":"other"}}""",
            """{"session_id":"chat","revision":0,"conversation":{"id":"chat"}}"""
        )) {
            val wire = object : ServerSideWire {
                override fun authenticate(identity: PairedIdentity) = AccessToken("bearer", 900)
                override fun events(token: AccessToken, sessionId: String, after: Long) = emptyList<SessionEvent>()
                override fun rpc(token: AccessToken, request: RpcRequest) = RpcReply(request.id, true, Json.parseToJsonElement(payload))
            }
            val sessions = ServerSideSessions(ServerSideClient(wire, PairedIdentity("c", "secret", "s")))
            try { sessions.open("chat"); fail("Accepted invalid snapshot") }
            catch (_: ServerSideProtocolException) { }
        }
    }

    @Test fun snapshotsExposeServerLiveEventCursorAndRejectInvalidCursor() = runBlocking {
        var raw = """{"session_id":"chat","revision":7,"event_seq":4096,"conversation":{"id":"chat"}}"""
        val wire = object : ServerSideWire {
            override fun authenticate(identity: PairedIdentity) = AccessToken("bearer", 900)
            override fun events(token: AccessToken, sessionId: String, after: Long) = emptyList<SessionEvent>()
            override fun rpc(token: AccessToken, request: RpcRequest) = RpcReply(request.id, true, Json.parseToJsonElement(raw))
        }
        val sessions = ServerSideSessions(ServerSideClient(wire, PairedIdentity("c", "secret", "s")))
        assertEquals(4096L, sessions.open("chat").eventSeq)
        assertEquals(4096L, sessions.get("chat").eventSeq)
        raw = """{"session_id":"chat","revision":7,"conversation":{"id":"chat"}}"""
        assertNull(sessions.open("chat").eventSeq) // Older v5 servers omit the optional field.
        raw = """{"session_id":"chat","revision":7,"event_seq":-1,"conversation":{"id":"chat"}}"""
        try { sessions.open("chat"); fail("Accepted invalid cursor") }
        catch (_: ServerSideProtocolException) { }
    }

    @Test fun conditionalOpenReusesOnlyVerifiedSameSessionRevision() = runBlocking {
        val conversation = Json.parseToJsonElement("""{"id":"chat","title":"Offline copy","future":{"preserved":true}}""").jsonObject
        val cached = ServerSnapshot("chat", 7, conversation)
        var reply = """{"session_id":"chat","revision":7,"event_seq":123,"unchanged":true}"""
        val requests = mutableListOf<RpcRequest>()
        val wire = object : ServerSideWire {
            override fun authenticate(identity: PairedIdentity) = AccessToken("bearer", 900)
            override fun events(token: AccessToken, sessionId: String, after: Long) = emptyList<SessionEvent>()
            override fun rpc(token: AccessToken, request: RpcRequest): RpcReply {
                requests += request
                return RpcReply(request.id, true, Json.parseToJsonElement(reply))
            }
        }
        val api = ServerSideSessions(ServerSideClient(wire, PairedIdentity("c", "secret", "s")))
        val accepted = api.open("chat", cached)
        assertEquals(7L, accepted.revision)
        assertEquals(123L, accepted.eventSeq)
        assertEquals(conversation, accepted.conversation)
        assertEquals("7", requests.last().data?.get("if_revision")?.jsonPrimitive?.content)

        // Server-provided 'unchanged' without an eligible cache entry is untrusted.
        try { api.open("chat"); fail("Accepted omitted conversation without cache") }
        catch (_: ServerSideProtocolException) { }
        try { api.open("another", cached); fail("Accepted wrong-session cache") }
        catch (_: ServerSideProtocolException) { }
        reply = """{"session_id":"chat","revision":8,"event_seq":125,"unchanged":true}"""
        try { api.open("chat", cached); fail("Accepted changed revision as unchanged") }
        catch (_: ServerSideProtocolException) { }
        reply = """{"session_id":"chat","revision":8,"event_seq":125,"conversation":{"id":"chat","title":"Updated","future":{"new":true}}}"""
        val updated = api.open("chat", cached)
        assertEquals(8L, updated.revision)
        assertEquals("Updated", updated.conversation.getValue("title").jsonPrimitive.content)
        assertEquals(125L, updated.eventSeq)
        // Legacy server replies containing full snapshots remain supported.
        reply = """{"session_id":"chat","revision":8,"conversation":{"id":"chat","title":"Legacy"}}"""
        assertEquals("Legacy", api.open("chat", cached).conversation.getValue("title").jsonPrimitive.content)
    }

    @Test fun readsServerGenerationLimitsAndRejectsImpossibleBounds() = runBlocking {
        var runLimit = 64 * 1024
        val wire = object : ServerSideWire {
            override fun authenticate(identity: PairedIdentity) = AccessToken("bearer", 900)
            override fun events(token: AccessToken, sessionId: String, after: Long) = emptyList<SessionEvent>()
            override fun rpc(token: AccessToken, request: RpcRequest) = RpcReply(request.id, true, Json.parseToJsonElement(
                """{"protocol":5,"operations":["session.list","session.open","session.get","session.configure","session.run","session.cancel","session.events","session.save","session.delete","provider.catalog"],"chat_features":{"version":1,"text_generation":true,"streaming":true,"conversation_max_bytes":3145728,"attachments":false,"negotiation":{"generation":{"max_run_content_bytes":$runLimit,"max_history_messages":256}}}}"""))
        }
        val api = ServerSideSessions(ServerSideClient(wire, PairedIdentity("c", "secret", "s")))
        assertEquals(64 * 1024, api.verifyCapabilities().maxRunContentBytes)
        assertEquals(3 * 1024 * 1024, api.verifyCapabilities().maxConversationBytes)
        assertFalse(api.verifyCapabilities().attachments)
        runLimit = -1
        try { api.verifyCapabilities(); fail("Accepted an invalid size") }
        catch (_: ServerSideProtocolException) { }
    }

    @Test fun rejectsCapabilitiesWithoutConfigurationOrEvents() = runBlocking {
        val wire = object : ServerSideWire {
            override fun authenticate(identity: PairedIdentity) = AccessToken("bearer", 900)
            override fun events(token: AccessToken, sessionId: String, after: Long) = emptyList<SessionEvent>()
            override fun rpc(token: AccessToken, request: RpcRequest) = RpcReply(request.id, true, Json.parseToJsonElement(
                """{"protocol":5,"operations":["session.list","session.open","session.get","session.run","session.cancel","session.save","session.delete","provider.catalog"],"chat_features":{"version":1,"text_generation":true,"streaming":true}}"""))
        }
        val sessions = ServerSideSessions(ServerSideClient(wire, PairedIdentity("c", "secret", "s")))
        try { sessions.verifyCapabilities(); fail("Accepted incomplete capabilities") }
        catch (_: ServerSideProtocolException) { }
    }
}
