package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.features.home.chat.logic.remote.RemoteWorkspace
import org.starfall.generative.features.home.chat.logic.remote.RemoteConnectionFailure
import org.starfall.generative.features.home.chat.logic.remote.RemoteActionFailure
import org.starfall.generative.features.home.chat.logic.remote.remoteConnectionFailure
import org.starfall.generative.integrations.serverside.*

class RemoteWorkspaceTest {
    private class Wire(private val owner: String, private val fail: Boolean = false) : ServerSideWire {
        val operations = java.util.concurrent.CopyOnWriteArrayList<String>()
        var runRequest = ""
        var configureSource = ""
        var gap = false
        var foreignSnapshot = false
        var withMessage = false
        var saved = ""
        var storedProvider = "p"
        var storedModel = "m"
        var conflictOnSave = false
        var unavailableConversation = false
        var unavailableCatalog = false
        var evictPriorRunEvents = false
        val polledCursors = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()
        private val runsPerSession = java.util.concurrent.ConcurrentHashMap<String, Int>()
        fun restartServerSessions() { runsPerSession.clear() }
        override fun authenticate(identity: PairedIdentity): AccessToken {
            if (fail) throw ServerSideHttpException(401)
            return AccessToken("bearer", 900)
        }
        override fun events(token: AccessToken, sessionId: String, after: Long): List<SessionEvent> {
            polledCursors += sessionId to after
            if (gap) throw ServerSideRpcException(RpcError("event_gap", "gap"))
            if (evictPriorRunEvents) {
                val run = runsPerSession[sessionId] ?: 0
                val start = (run - 1L) * 2
                if (run > 1 && after < start) throw ServerSideRpcException(RpcError("event_gap", "old events evicted"))
                return if (run > 0 && after == start) listOf(
                    SessionEvent(sessionId, start + 1, runRequest, "delta", Json.parseToJsonElement("""{"text":"hello"}""")),
                    SessionEvent(sessionId, start + 2, runRequest, "completed"),
                ) else emptyList()
            }
            return if (foreignSnapshot && after == 0L) listOf(SessionEvent(sessionId, 1, runRequest, "conversation", Json.parseToJsonElement("""{"revision":2,"conversation":{"id":"another-chat"}}"""))) else if (after == 0L) listOf(SessionEvent(sessionId, 1, runRequest, "delta", Json.parseToJsonElement("""{"text":"hello"}""")),
                SessionEvent(sessionId, 2, runRequest, "completed")) else emptyList()
        }
        override fun rpc(token: AccessToken, request: RpcRequest): RpcReply {
            operations += request.op
            if (request.op == "session.save" && conflictOnSave)
                return RpcReply(request.id, false, error = RpcError("revision_conflict", "changed"))
            if (request.op == "session.open" && unavailableConversation)
                return RpcReply(request.id, false, error = RpcError("not_found", "conversation unavailable"))
            if (request.op == "provider.catalog" && unavailableCatalog)
                return RpcReply(request.id, false, error = RpcError("internal_error", "catalog unavailable"))
            val body = when (request.op) {
                "capabilities.get" -> """{"protocol":5,"operations":["session.list","session.open","session.get","session.configure","session.run","session.cancel","session.events","session.save","session.delete","provider.catalog"],"chat_features":{"version":1,"text_generation":true,"streaming":true,"runtime_sources":["stored","mobile_ephemeral"]}}"""
                "provider.catalog" -> """{"version":1,"revision":1,"providers":[{"id":"p","name":"Provider","type":"openai","custom_model_id":false,"key_selection_required":false,"keys":[],"models":[{"id":"m","name":"Model","availability":"available","text_generation":true}]}]}"""
                "session.list" -> """{"version":1,"items":[{"id":"$owner-chat","title":"$owner","created_at":1,"updated_at":2,"revision":1}],"next_cursor":""}"""
                "session.open", "session.get" -> {
                    val cursor = (runsPerSession[request.sessionId] ?: 0) * 2L
                    val messages = if (withMessage) """[{"id":"msg","role":"user","versions":[{"content":"old","future_version":"kept"}]}]""" else "[]"
                    """{"session_id":"${request.sessionId}","revision":1,"event_seq":$cursor,"conversation":{"id":"${request.sessionId}","title":"$owner","created_at":1,"updated_at":2,"messages":$messages,"provider_id":"$storedProvider","model_id":"$storedModel","future_root":"kept"}}"""
                }
                "session.save" -> { saved = request.data.toString(); """{"session_id":"${request.sessionId}","revision":2,"conversation":${request.data!!.getValue("conversation")}}""" }
                "session.configure" -> { configureSource = request.data!!.getValue("runtime").toString(); """{"session_id":"${request.sessionId}"}""" }
                "session.run" -> {
                    runRequest = request.data!!.getValue("request_id").toString().trim('"')
                    runsPerSession[request.sessionId!!] = (runsPerSession[request.sessionId] ?: 0) + 1
                    """{"request_id":"$runRequest"}"""
                }
                else -> error("unexpected operation ${request.op}")
            }
            return RpcReply(request.id, true, Json.parseToJsonElement(body))
        }
    }
    @Test fun distinguishesRemoteAuthenticationTlsAndCapabilityFailures() {
        val classify = ::remoteConnectionFailure
        assertEquals(RemoteConnectionFailure.UNAUTHORIZED, classify(ServerSideHttpException(401)))
        assertEquals(RemoteConnectionFailure.CERTIFICATE, classify(javax.net.ssl.SSLException("pin mismatch")))
        assertEquals(RemoteConnectionFailure.INCOMPATIBLE, classify(ServerSideProtocolException("missing capability")))
        assertEquals(RemoteConnectionFailure.UNAVAILABLE, classify(java.io.IOException("offline")))
    }

    @Test fun switchingServersClearsPreviousHistoryAndDisableRestoresLocalMode() = runBlocking {
        val a = Wire("a"); val b = Wire("b")
        val workspace = RemoteWorkspace({ id -> ServerSideSessions(ServerSideClient(if (id == "a") a else b, PairedIdentity("device", "secret", id))) }, this)
        suspend fun ready() = withTimeout(5000) { while (workspace.state.value.busy) yield() }
        workspace.enable("a"); ready(); assertEquals("a-chat", workspace.state.value.items.single().id)
        workspace.select("a-chat"); ready(); assertEquals("a-chat", workspace.state.value.conversation?.id)
        workspace.enable("b"); assertNull(workspace.state.value.conversation); assertTrue(workspace.state.value.items.isEmpty())
        ready(); assertEquals("b-chat", workspace.state.value.items.single().id)
        workspace.disable(); assertFalse(workspace.state.value.enabled); assertNull(workspace.state.value.conversation)
    }
    @Test fun localProviderIsAppendedAndRunsOnRemoteOnlyWhileAllowed() = runBlocking {
        val wire = Wire("remote")
        val provider = ModelProvider(id = "p", name = "Local", type = ModelApiType.OPENAI,
            baseUrl = "https://example.com/v1",
            auth = Authorization(AuthMethod.NONE),
            config = ProviderConfiguration(modelIds = listOf("m")))
        val workspace = RemoteWorkspace(
            { ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) },
            this, resolveLocalProvider = { provider })
        workspace.enable("server")
        withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertEquals(listOf("p"), workspace.state.value.providers.map { it.id })
        workspace.setLocalProviders("other", listOf(provider))
        assertEquals(listOf("p"), workspace.state.value.providers.map { it.id })
        workspace.setLocalProviders("server", listOf(provider))
        assertEquals(listOf("p", "local:p"), workspace.state.value.providers.map { it.id })
        workspace.selectModel("local:p", "m")
        assertTrue(workspace.send("hello", emptyList()))
        withTimeout(5000) { while (workspace.state.value.busy || workspace.state.value.running) yield() }
        assertTrue(wire.configureSource.contains("mobile_ephemeral"))
        assertEquals(1, wire.operations.count { it == "session.run" })
        workspace.setLocalProviders("server", emptyList())
        assertEquals(listOf("p"), workspace.state.value.providers.map { it.id })
        workspace.selectModel("local:p", "m")
        assertNotEquals("local:p", workspace.state.value.providerId)
        workspace.disable()
    }

    @Test fun offlineRemainsRemoteAndNeverSends() = runBlocking {
        val wire = Wire("offline", true)
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.state.value.enabled); assertTrue(workspace.state.value.error)
        assertFalse(workspace.send("hello", emptyList())); assertTrue(wire.operations.isEmpty())
    }

    @Test fun sendsOnlyToSelectedRemoteAndReconcilesCompletion() = runBlocking {
        val wire = Wire("remote")
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.send("hello", emptyList()))
        withTimeout(5000) { while ("session.get" !in wire.operations || workspace.state.value.running) yield() }
        assertTrue(wire.configureSource.contains("stored"))
        assertEquals(1, wire.operations.count { it == "session.run" })
        assertNotNull(workspace.state.value.conversation)
        assertFalse(workspace.state.value.error)
        workspace.disable()
    }

    @Test fun subsequentRunContinuesFromLastEventCursorAfterServerEvictsOldEvents() = runBlocking {
        val wire = Wire("remote").apply { evictPriorRunEvents = true }
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        workspace.select("remote-chat"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.send("first", emptyList()))
        withTimeout(5000) { while (wire.operations.count { it == "session.get" } < 1 || workspace.state.value.running) yield() }
        assertTrue(workspace.send("second", emptyList()))
        withTimeout(5000) { while (wire.operations.count { it == "session.get" } < 2 || workspace.state.value.running) yield() }
        assertEquals(listOf(0L, 2L), wire.polledCursors.map { it.second })
        assertEquals(2, wire.operations.count { it == "session.run" })
        assertFalse(workspace.state.value.error)
        workspace.disable()
    }

    @Test fun aNewAndroidWorkspaceUsesCurrentLiveServerCursorAfterOldEventsWereEvicted() = runBlocking {
        val wire = Wire("remote").apply { evictPriorRunEvents = true }
        fun workspace() = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        val first = workspace()
        first.enable("server"); withTimeout(5000) { while (first.state.value.busy) yield() }
        first.select("remote-chat"); withTimeout(5000) { while (first.state.value.busy) yield() }
        assertTrue(first.send("before reconnection", emptyList()))
        withTimeout(5000) { while (wire.operations.count { it == "session.get" } < 1 || first.state.value.running) yield() }
        first.disable()
        // Simulate a new Android process with no retained cursor; server session stays alive.
        val second = workspace()
        second.enable("server"); withTimeout(5000) { while (second.state.value.busy) yield() }
        second.select("remote-chat"); withTimeout(5000) { while (second.state.value.busy) yield() }
        assertTrue(second.send("after reconnection", emptyList()))
        withTimeout(5000) { while (wire.operations.count { it == "session.get" } < 2 || second.state.value.running) yield() }
        assertEquals(2L, wire.polledCursors.last().second)
        assertFalse(second.state.value.error)
        assertEquals(2, wire.operations.count { it == "session.run" })
        second.disable()
    }

    @Test fun eventCursorIsScopedToSessionAndClearedOnReconnect() = runBlocking {
        val wire = Wire("remote").apply { evictPriorRunEvents = true }
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        workspace.select("remote-chat"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.send("first", emptyList()))
        withTimeout(5000) { while (wire.operations.count { it == "session.get" } < 1 || workspace.state.value.running) yield() }
        workspace.newChat()
        assertTrue(workspace.send("different chat", emptyList()))
        withTimeout(5000) { while (wire.operations.count { it == "session.get" } < 2 || workspace.state.value.running) yield() }
        assertEquals(0L, wire.polledCursors.last().second)
        workspace.select("remote-chat"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.send("again", emptyList()))
        withTimeout(5000) { while (wire.operations.count { it == "session.get" } < 3 || workspace.state.value.running) yield() }
        assertEquals(2L, wire.polledCursors.last().second)
        workspace.disable()
        wire.restartServerSessions() // Simulate daemon restart: event sequence resets to zero.
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        // The old cursor must never be reused against a new in-memory session.
        workspace.select("remote-chat"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.send("after reconnect", emptyList()))
        withTimeout(5000) { while (wire.polledCursors.size < 4) yield() }
        assertEquals(0L, wire.polledCursors.last().second)
        workspace.disable()
    }

    @Test fun eventGapReloadsAuthoritativeSnapshotWithoutReplayingSend() = runBlocking {
        val wire = Wire("remote"); wire.gap = true
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.send("hello", emptyList()))
        withTimeout(5000) { while (!workspace.state.value.error) yield() }
        assertTrue("session.get" in wire.operations)
        assertEquals(1, wire.operations.count { it == "session.run" })
        assertFalse(workspace.send("retry", emptyList()))
        workspace.disable()
    }

    @Test fun foreignConversationEventReloadsWithoutDisplayingItOrReplaying() = runBlocking {
        val wire = Wire("remote"); wire.foreignSnapshot = true
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.send("hello", emptyList()))
        withTimeout(5000) { while (!workspace.state.value.error) yield() }
        assertNotEquals("another-chat", workspace.state.value.conversation?.id)
        assertEquals(1, wire.operations.count { it == "session.run" })
        assertTrue("session.get" in wire.operations)
        workspace.disable()
    }

    @Test fun editingUsesVisibleRevisionAndPreservesUnknownFields() = runBlocking {
        val wire = Wire("remote"); wire.withMessage = true
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        workspace.select("remote-chat"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.editMessage("msg", "new", emptyList()))
        withTimeout(5000) { while (workspace.state.value.snapshot?.revision != 2L) yield() }
        assertTrue(wire.saved.contains("future_root")); assertTrue(wire.saved.contains("future_version"))
        assertTrue(wire.saved.contains("\"expected_revision\":1")); assertTrue(wire.saved.contains("\"content\":\"new\""))
        workspace.disable()
    }

    @Test fun openingChatRestoresServerModelAndRejectsUnavailableModel() = runBlocking {
        val wire = Wire("remote")
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        workspace.select("remote-chat"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertEquals("p", workspace.state.value.providerId)
        assertEquals("m", workspace.state.value.modelId)
        wire.storedModel = "different-model"
        workspace.select("remote-chat"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.state.value.modelId.isEmpty())
        assertFalse(workspace.send("must-not-use-default", emptyList()))
        workspace.disable()
    }

    @Test fun revisionConflictDoesNotDisableRemoteChat() = runBlocking {
        val wire = Wire("remote").apply { conflictOnSave = true }
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        workspace.select("remote-chat"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        workspace.rename("remote-chat", "Edited")
        withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertFalse(workspace.state.value.error)
        assertEquals(org.starfall.generative.features.home.chat.logic.remote.RemoteActionFailure.REVISION_CONFLICT,
            workspace.state.value.actionFailure)
        assertTrue(workspace.send("new message", emptyList()))
        workspace.disable()
    }

    @Test fun lastSessionRestoresForSameServerWithoutCrossServerReuse() = runBlocking {
        val memory = mutableMapOf<String, String?>()
        val factory: suspend (String) -> ServerSideSessions = { id -> ServerSideSessions(ServerSideClient(Wire(id), PairedIdentity("device", "secret", id))) }
        fun workspace() = RemoteWorkspace(factory, this, { id -> memory[id] }, { id, session -> memory[id] = session })
        val first = workspace(); first.enable("a"); withTimeout(5000) { while (first.state.value.busy) yield() }
        first.select("a-chat"); withTimeout(5000) { while (first.state.value.busy) yield() }
        assertEquals("a-chat", memory["a"]); first.disable()
        val restored = workspace(); restored.enable("a"); withTimeout(5000) { while (restored.state.value.busy) yield() }
        assertEquals("a-chat", restored.state.value.conversation?.id)
        restored.enable("b"); withTimeout(5000) { while (restored.state.value.busy) yield() }
        assertNull(restored.state.value.conversation); restored.disable()
    }

    @Test fun activationRestoresWithoutExposingLocalAndExplicitDisableWins() = runBlocking {
        val gate = CompletableDeferred<String?>()
        var persisted: String? = "server"
        val workspace = RemoteWorkspace({ id -> ServerSideSessions(ServerSideClient(Wire(id), PairedIdentity("device", "secret", id))) }, this,
            loadActivation = { gate.await() }, saveActivation = { persisted = it })
        assertTrue(workspace.state.value.restoring)
        workspace.disable(); gate.complete("server")
        withTimeout(5000) { while (persisted != null) yield() }
        assertFalse(workspace.state.value.enabled); assertFalse(workspace.state.value.restoring)
    }
    @Test fun savedActivationRestoresRemoteSession() = runBlocking {
        var persisted: String? = "a"
        val workspace = RemoteWorkspace({ id -> ServerSideSessions(ServerSideClient(Wire(id), PairedIdentity("device", "secret", id))) }, this,
            loadLastSession = { "a-chat" }, loadActivation = { persisted }, saveActivation = { persisted = it })
        withTimeout(5000) { while (workspace.state.value.restoring || workspace.state.value.busy) yield() }
        assertTrue(workspace.state.value.enabled); assertEquals("a-chat", workspace.state.value.conversation?.id)
        workspace.disable()
    }

    @Test fun failedActivationPersistenceDoesNotSendOrFallBack() = runBlocking {
        val wire = Wire("a")
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "server"))) }, this,
            saveActivation = { throw java.io.IOException("storage unavailable") })
        workspace.enable("a"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.state.value.enabled); assertTrue(workspace.state.value.error)
        assertFalse(workspace.send("hello", emptyList())); assertTrue(wire.operations.isEmpty())
    }

    @Test fun inaccessibleLastConversationKeepsWorkspaceConnected() = runBlocking {
        val wire = Wire("a").apply { unavailableConversation = true }
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "a"))) }, this,
            loadLastSession = { "a-chat" })
        workspace.enable("a")
        withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertFalse(workspace.state.value.error)
        assertNull(workspace.state.value.conversation)
        assertEquals("p", workspace.state.value.providerId)
        assertEquals(RemoteActionFailure.CONVERSATION_LOAD_FAILED, workspace.state.value.actionFailure)

        workspace.select("a-chat")
        withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertFalse(workspace.state.value.error)
        assertEquals(RemoteActionFailure.CONVERSATION_LOAD_FAILED, workspace.state.value.actionFailure)
        workspace.disable()
    }

    @Test fun catalogFailureAfterHandshakeIsReportedAsChatLoadFailure() = runBlocking {
        val wire = Wire("a").apply { unavailableCatalog = true }
        val workspace = RemoteWorkspace({ ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "a"))) }, this)
        workspace.enable("a")
        withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertEquals(RemoteConnectionFailure.CHAT_LOAD_FAILED, workspace.state.value.connectionFailure)
        workspace.disable()
    }

    @Test fun newChatCannotInterruptRemoteActivation() = runBlocking {
        val opening = CompletableDeferred<Unit>()
        val wire = Wire("a")
        val workspace = RemoteWorkspace({
            opening.await()
            ServerSideSessions(ServerSideClient(wire, PairedIdentity("device", "secret", "a")))
        }, this)
        workspace.enable("a")
        workspace.newChat()
        assertTrue(workspace.state.value.busy)
        opening.complete(Unit)
        withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertFalse(workspace.state.value.error)
        assertEquals("p", workspace.state.value.providerId)
        workspace.disable()
    }
}
