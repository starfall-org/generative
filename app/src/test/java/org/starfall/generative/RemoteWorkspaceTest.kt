package org.starfall.generative

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.features.home.chat.logic.remote.RemoteWorkspace
import org.starfall.generative.features.home.chat.model.RemoteConnectionFailure
import org.starfall.generative.features.home.chat.model.RemoteActionFailure
import org.starfall.generative.features.home.chat.logic.remote.remoteConnectionFailure
import org.starfall.generative.integrations.generativeserver.*
import org.starfall.generative.integrations.remote.RemoteExecStream

class RemoteWorkspaceTest {
    private class Wire(private val owner: String, private val fail: Boolean = false) : GenerativeServerWire {
        val operations = java.util.concurrent.CopyOnWriteArrayList<String>()
        var runRequest = ""
        var configureSource = ""
        var gap = false
        @Volatile var holdRunEvents = false
        @Volatile var typedOutput = false
        private val toolOutput = """{"id":"call","name":"lookup","status":"success","response":"found",
            "contentOffset":0,"reasoningOffset":8}"""
        private fun typedConversation(id: String, message: String) =
            """{"id":"$id","title":"$owner","created_at":1,"updated_at":2,
                "messages":[{"id":"out","role":"model","versions":[$message]}],"provider_id":"p","model_id":"m"}"""
        private fun typedFinal(id: String) = typedConversation(id,
            """{"content":"Answer","reasoning_content":"Thinking","toolActivity":[$toolOutput]}""")
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
            if (fail) throw GenerativeServerHttpException(401)
            return AccessToken("bearer", 900)
        }
        override fun events(token: AccessToken, sessionId: String, after: Long): List<SessionEvent> {
            polledCursors += sessionId to after
            if (typedOutput && after == 0L) {
                val conversation = typedConversation(sessionId, """{"content":""}""")
                return listOf(
                    SessionEvent(sessionId, 1, runRequest, "conversation",
                        Json.parseToJsonElement("""{"revision":2,"conversation":$conversation}""")),
                    SessionEvent(sessionId, 2, runRequest, "reasoning_delta",
                        Json.parseToJsonElement("""{"text":"Thinking","content_offset":0}""")),
                    SessionEvent(sessionId, 3, runRequest, "tool_activity",
                        Json.parseToJsonElement(toolOutput)),
                    SessionEvent(sessionId, 4, runRequest, "delta",
                        Json.parseToJsonElement("""{"text":"Answer"}"""))
                )
            }
            if (holdRunEvents) return emptyList()
            if (typedOutput && after == 4L) return listOf(SessionEvent(sessionId, 5, runRequest, "completed"))
            if (gap) throw GenerativeServerRpcException(RpcError("event_gap", "gap"))
            if (evictPriorRunEvents) {
                val run = runsPerSession[sessionId] ?: 0
                val start = (run - 1L) * 2
                if (run > 1 && after < start) throw GenerativeServerRpcException(RpcError("event_gap", "old events evicted"))
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
                "session.open", "session.get" -> if (request.op == "session.get" && typedOutput) {
                    """{"session_id":"${request.sessionId}","revision":3,"conversation":${typedFinal(requireNotNull(request.sessionId))}}"""
                } else {
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
        assertEquals(RemoteConnectionFailure.UNAUTHORIZED, classify(GenerativeServerHttpException(401)))
        assertEquals(RemoteConnectionFailure.CERTIFICATE, classify(javax.net.ssl.SSLException("pin mismatch")))
        assertEquals(RemoteConnectionFailure.INCOMPATIBLE, classify(GenerativeServerProtocolException("missing capability")))
        assertEquals(RemoteConnectionFailure.UNAVAILABLE, classify(java.io.IOException("offline")))
    }

    @Test fun switchingServersClearsPreviousHistoryAndDisableRestoresLocalMode() = runBlocking {
        val a = Wire("a"); val b = Wire("b")
        val workspace = RemoteWorkspace({ id -> GenerativeServerSessions(GenerativeServerClient(if (id == "a") a else b, PairedIdentity("device", "secret", id))) }, this)
        suspend fun ready() = withTimeout(5000) { while (workspace.state.value.busy) yield() }
        workspace.enable("a"); ready(); assertEquals("a-chat", workspace.state.value.items.single().id)
        workspace.select("a-chat"); ready(); assertEquals("a-chat", workspace.state.value.conversation?.id)
        workspace.enable("b"); assertNull(workspace.state.value.conversation); assertTrue(workspace.state.value.items.isEmpty())
        ready(); assertEquals("b-chat", workspace.state.value.items.single().id)
        workspace.disable(); assertFalse(workspace.state.value.enabled); assertNull(workspace.state.value.conversation)
    }
    @Test fun newCliChatIsVisibleBeforeCompletionAndSurvivesAStaleSessionList() = runBlocking {
        val wire = Wire("remote").apply { holdRunEvents = true; typedOutput = true }
        val workspace = RemoteWorkspace(
            { GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) },
            this
        )
        workspace.enable("server")
        withTimeout(5_000) { workspace.state.first { !it.busy } }
        workspace.selectModel("p", "m")
        assertTrue(workspace.send("Hello", emptyList()))
        val opened = withTimeout(5_000) { workspace.state.first { it.running } }
        val id = requireNotNull(opened.conversation).id
        assertTrue(opened.items.any { it.id == id })
        val running = withTimeout(5_000) {
            workspace.state.first { it.running && it.conversation?.messages?.lastOrNull()?.content == "Answer" }
        }
        val live = requireNotNull(running.conversation).messages.single().activeVersion
        assertEquals("Thinking", live.reasoningContent)
        assertEquals("found", live.toolActivity.single().response.textBlocking())
        wire.holdRunEvents = false
        val completed = withTimeout(5_000) { workspace.state.first { !it.busy && !it.running } }
        assertTrue(completed.items.any { it.id == id })
        assertFalse(completed.error)
        assertEquals(live, completed.conversation?.messages?.single()?.activeVersion)
        workspace.disable()
    }

    @Test fun localProviderIsAppendedAndRunsOnRemoteOnlyWhileAllowed() = runBlocking {
        val wire = Wire("remote")
        val provider = ModelProvider(id = "p", name = "Local", type = ModelApiType.OPENAI,
            baseUrl = "https://example.com/v1",
            auth = Authorization(AuthMethod.NONE),
            config = ProviderConfiguration(modelIds = listOf("m"), modelConfigs = mapOf(
                "m" to ModelConfiguration(legacySendThinkingContent = false, thinkingLevel = "high")
            )))
        val workspace = RemoteWorkspace(
            { GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) },
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

    @Test fun failedLocalProviderPreparationRejectsTheDraftBeforeAnyChatRequest() = runBlocking {
        val wire = Wire("remote")
        val provider = ModelProvider(id = "p", name = "Local", type = ModelApiType.OPENAI,
            baseUrl = "https://example.com/v1", config = ProviderConfiguration(modelIds = listOf("m")))
        val attempts = listOf(
            RemoteActionFailure.LOCAL_PROVIDER_OAUTH to provider.copy(auth = Authorization(AuthMethod.OAUTH)),
            RemoteActionFailure.LOCAL_PROVIDER_CONNECTION_OPTIONS to provider.copy(
                config = provider.config.copy(headers = mapOf("X-Extra" to "value"))),
            RemoteActionFailure.LOCAL_PROVIDER_MODEL_OPTIONS to provider.copy(
                config = provider.config.copy(modelConfigs = mapOf("m" to ModelConfiguration(topK = 4)))),
            RemoteActionFailure.LOCAL_PROVIDER_CREDENTIALS to null,
        )
        var resolution = CompletableDeferred<ModelProvider>()
        val workspace = RemoteWorkspace(
            { GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) },
            this, resolveLocalProvider = { resolution.await() })
        workspace.enable("server")
        withTimeout(5000) { workspace.state.first { !it.busy } }
        workspace.setLocalProviders("server", listOf(provider))
        workspace.selectModel("local:p", "m")
        val initialOperations = wire.operations.toList()

        for ((reason, resolved) in attempts) {
            resolution = CompletableDeferred()
            val send = async(start = CoroutineStart.UNDISPATCHED) { workspace.send("Keep this draft", emptyList()) }
            assertFalse(send.isCompleted)
            assertEquals(initialOperations, wire.operations)
            if (resolved != null) resolution.complete(resolved)
            else resolution.completeExceptionally(IllegalStateException("Credentials unavailable"))
            assertFalse(withTimeout(5000) { send.await() })
            assertEquals(reason, workspace.state.value.actionFailure)
            assertFalse(workspace.state.value.error)
            assertEquals(initialOperations, wire.operations)
        }
        workspace.disable()
    }

    @Test fun switchingChatsWhileResolvingCredentialsDiscardsThePendingSend() = runBlocking {
        val wire = Wire("remote")
        val provider = ModelProvider(id = "p", name = "Local", type = ModelApiType.OPENAI,
            baseUrl = "https://example.com/v1", config = ProviderConfiguration(modelIds = listOf("m")))
        val resolution = CompletableDeferred<ModelProvider>()
        val workspace = RemoteWorkspace(
            { GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) },
            this, resolveLocalProvider = { resolution.await() })
        workspace.enable("server")
        withTimeout(5000) { workspace.state.first { !it.busy } }
        workspace.setLocalProviders("server", listOf(provider))
        workspace.selectModel("local:p", "m")

        val send = async(start = CoroutineStart.UNDISPATCHED) { workspace.send("Old chat draft", emptyList()) }
        workspace.newChat()
        resolution.complete(provider)
        assertFalse(withTimeout(5000) { send.await() })
        assertFalse(wire.operations.any { it in setOf("session.open", "session.configure", "session.run") })
        assertNull(workspace.state.value.actionFailure)
        workspace.disable()
    }

    @Test fun offlineRemainsRemoteAndNeverSends() = runBlocking {
        val wire = Wire("offline", true)
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.state.value.enabled); assertTrue(workspace.state.value.error)
        assertFalse(workspace.send("hello", emptyList())); assertTrue(wire.operations.isEmpty())
    }

    @Test fun sendsOnlyToSelectedRemoteAndReconcilesCompletion() = runBlocking {
        val wire = Wire("remote")
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.send("hello", emptyList()))
        withTimeout(5000) { while ("session.get" !in wire.operations || workspace.state.value.running) yield() }
        assertTrue(wire.configureSource.contains("stored"))
        assertEquals(1, wire.operations.count { it == "session.run" })
        assertNotNull(workspace.state.value.conversation)
        assertFalse(workspace.state.value.error)
        workspace.disable()
    }

    @Test fun sshChatReleasesIdleChannelsAndReusesOneChannelForEachAction() = runBlocking {
        withTimeout(5_000) {
            val server = Wire("remote")
            val identity = PairedIdentity("device", "secret", "server")
            val token = AccessToken("ssh", 900)
            val released = Channel<Int>(Channel.UNLIMITED)
            val polling = CompletableDeferred<Unit>()
            val finishRun = CompletableDeferred<Unit>()
            val batches = listOf(
                listOf("capabilities.get", "provider.catalog", "session.list"),
                listOf("session.open", "session.open_chunked", "session.read_chunk"),
                listOf("session.open", "session.configure", "session.run", "session.events", "session.get", "session.list"),
            )
            val conversation = """{"id":"remote-chat","title":"Remote","created_at":1,"updated_at":2,"messages":[],"provider_id":"p","model_id":"m"}"""
                .toByteArray()
            val chunk = Base64.getEncoder().encodeToString(conversation)
            var opened = 0
            val transport = GenerativeServerSshTransport({
                val channel = opened++
                val operations = batches[channel]
                val input = PipedInputStream(8192)
                val serverOutput = PipedOutputStream(input)
                val serverInput = PipedInputStream(8192)
                val output = PipedOutputStream(serverInput)
                launch(Dispatchers.IO) {
                    GenerativeServerSshTransport.readFrame(serverInput) // Paired authentication.
                    GenerativeServerSshTransport.writeFrame(serverOutput, """{"type":"paired","protocol":5,"client_id":"device"}""")
                    for (operation in operations) {
                        val request = GenerativeServerCodec.json.decodeFromString<RpcRequest>(
                            GenerativeServerSshTransport.readFrame(serverInput),
                        )
                        assertEquals(operation, request.op)
                        val reply = when {
                            channel == 1 && operation == "session.open" ->
                                RpcReply(request.id, false, error = RpcError("resource_exhausted", "Use chunked download"))
                            operation == "session.open_chunked" -> RpcReply(request.id, true, Json.parseToJsonElement(
                                """{"version":1,"session_id":"remote-chat","revision":1,"size":${conversation.size}}""",
                            ))
                            operation == "session.read_chunk" -> RpcReply(request.id, true, Json.parseToJsonElement(
                                """{"revision":1,"offset":0,"data":"$chunk","done":true}""",
                            ))
                            operation == "session.events" -> {
                                polling.complete(Unit)
                                finishRun.await()
                                val events = server.events(token, requireNotNull(request.sessionId),
                                    requireNotNull(request.data).getValue("after").jsonPrimitive.long)
                                RpcReply(request.id, true, Json.encodeToJsonElement(events))
                            }
                            else -> server.rpc(token, request)
                        }
                        GenerativeServerSshTransport.writeFrame(serverOutput, Json.encodeToString(reply))
                    }
                }
                RemoteExecStream(input, output) {
                    serverOutput.close()
                    input.close()
                    output.close()
                    serverInput.close()
                    released.trySend(channel)
                }
            }, identity)
            val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(transport, identity)) }, this)
            try {
                workspace.enable("server")
                assertEquals(0, released.receive())
                assertTrue(workspace.state.value.enabled)
                assertEquals(1, opened)

                workspace.select("remote-chat")
                assertEquals(1, released.receive())
                withTimeout(1_000) { workspace.state.first { !it.busy } }
                assertEquals("remote-chat", workspace.state.value.conversation?.id)

                assertTrue(workspace.send("Hello", emptyList()))
                polling.await()
                assertTrue(workspace.state.value.running)
                assertTrue(released.tryReceive().isFailure)
                finishRun.complete(Unit)

                assertEquals(2, released.receive())
                assertFalse(workspace.state.value.running)
                assertTrue(workspace.state.value.enabled)
                assertEquals(3, opened)
            } finally {
                workspace.disable()
            }
        }
    }

    @Test fun subsequentRunContinuesFromLastEventCursorAfterServerEvictsOldEvents() = runBlocking {
        val wire = Wire("remote").apply { evictPriorRunEvents = true }
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
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
        fun workspace() = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
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
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
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
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
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
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
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
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
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
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
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
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this)
        workspace.enable("server"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        workspace.select("remote-chat"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        workspace.rename("remote-chat", "Edited")
        withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertFalse(workspace.state.value.error)
        assertEquals(org.starfall.generative.features.home.chat.model.RemoteActionFailure.REVISION_CONFLICT,
            workspace.state.value.actionFailure)
        assertTrue(workspace.send("new message", emptyList()))
        workspace.disable()
    }

    @Test fun lastSessionRestoresForSameServerWithoutCrossServerReuse() = runBlocking {
        val memory = mutableMapOf<String, String?>()
        val factory: suspend (String) -> GenerativeServerSessions = { id -> GenerativeServerSessions(GenerativeServerClient(Wire(id), PairedIdentity("device", "secret", id))) }
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
        val workspace = RemoteWorkspace({ id -> GenerativeServerSessions(GenerativeServerClient(Wire(id), PairedIdentity("device", "secret", id))) }, this,
            loadActivation = { gate.await() }, saveActivation = { persisted = it })
        assertTrue(workspace.state.value.restoring)
        workspace.disable(); gate.complete("server")
        withTimeout(5000) { while (persisted != null) yield() }
        assertFalse(workspace.state.value.enabled); assertFalse(workspace.state.value.restoring)
    }
    @Test fun savedActivationRestoresRemoteSession() = runBlocking {
        var persisted: String? = "a"
        val workspace = RemoteWorkspace({ id -> GenerativeServerSessions(GenerativeServerClient(Wire(id), PairedIdentity("device", "secret", id))) }, this,
            loadLastSession = { "a-chat" }, loadActivation = { persisted }, saveActivation = { persisted = it })
        withTimeout(5000) { while (workspace.state.value.restoring || workspace.state.value.busy) yield() }
        assertTrue(workspace.state.value.enabled); assertEquals("a-chat", workspace.state.value.conversation?.id)
        workspace.disable()
    }

    @Test fun failedActivationPersistenceDoesNotSendOrFallBack() = runBlocking {
        val wire = Wire("a")
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "server"))) }, this,
            saveActivation = { throw java.io.IOException("storage unavailable") })
        workspace.enable("a"); withTimeout(5000) { while (workspace.state.value.busy) yield() }
        assertTrue(workspace.state.value.enabled); assertTrue(workspace.state.value.error)
        assertFalse(workspace.send("hello", emptyList())); assertTrue(wire.operations.isEmpty())
    }

    @Test fun inaccessibleLastConversationKeepsWorkspaceConnected() = runBlocking {
        val wire = Wire("a").apply { unavailableConversation = true }
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "a"))) }, this,
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
        val workspace = RemoteWorkspace({ GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "a"))) }, this)
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
            GenerativeServerSessions(GenerativeServerClient(wire, PairedIdentity("device", "secret", "a")))
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
