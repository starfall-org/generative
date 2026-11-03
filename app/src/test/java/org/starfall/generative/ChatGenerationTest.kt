package org.starfall.generative

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.*
import org.starfall.generative.features.providers.shared.ui.*
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.features.home.chat.logic.ChatGeneration
import org.starfall.generative.features.home.chat.model.ChatStreamStatus
import org.starfall.generative.features.home.chat.logic.prepareRegeneration
import org.starfall.generative.features.home.chat.logic.updateResponse

class ChatGenerationTest {
    private suspend fun ChatGeneration.awaitCompletion() {
        withTimeout(5_000) { busy.first { !it } }
        // UI generation and durability are deliberately independent now.
        withTimeout(5_000) { awaitPersistence() }
    }

    @Test fun regenerationAndStreamingPreserveOriginalAndNewModelNames() {
        val original = conversation().copy(messages = conversation().messages.map { message ->
            if (message.role != ChatRole.MODEL) message else message.copy(versions = listOf(
                message.activeVersion.copy(modelId = "old", modelDisplayName = "Old model")
            ))
        })
        val regenerated = prepareRegeneration(original, "a1", MessageVersion(
            providerId = "new-provider", modelId = "new", modelDisplayName = "New model"))!!
        val streamed = updateResponse(regenerated, "a1", "A new answer")
        assertEquals("Old model", streamed.messages.last().versions.first().modelDisplayName)
        assertEquals("New model", streamed.messages.last().activeVersion.modelDisplayName)
        assertEquals("new-provider", streamed.messages.last().activeVersion.providerId)
    }
    private fun message(id: String, role: ChatRole, content: String) =
        StoredMessage(id, role, listOf(MessageVersion(content = content)))
    private fun conversation() = Conversation("a", "Chat A", 1, 1, messages = listOf(
        message("u1", ChatRole.USER, "First question"),
        message("a1", ChatRole.MODEL, "")
    ))

    @Test fun temporaryGenerationNeverCallsPersistenceBoundary() = runBlocking {
        var writes = 0
        var visible: Conversation? = null
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = { writes++ }, publish = { visible = it }
        )
        runner.start(conversation().copy(isTemporary = true), "a1", flowOf("Temporary answer"))
        runner.awaitCompletion()
        assertEquals(0, writes)
        assertEquals("Temporary answer", visible!!.messages.last().content)
    }

    @Test fun navigationDoesNotRedirectOrLoseStream() = runBlocking {
        var selected = conversation()
        val saved = mutableMapOf<String, Conversation>()
        val next = CompletableDeferred<Unit>()
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            { saved[it.id] = it }, { if (selected.id == it.id) selected = it })
        runner.start(selected, "a1", flow { emit("first"); next.await(); emit(" second") })
        assertEquals("a", selected.id)
        assertEquals("", selected.messages.last().content)
        selected = conversation().copy(id = "b", title = "Chat B")
        next.complete(Unit)
        runner.awaitCompletion()
        assertEquals("b", selected.id)
        assertEquals("", selected.messages.last().content)
        assertEquals("first second", saved["a"]!!.messages.last().content)
        assertFalse(runner.busy.value)
    }

    @Test fun liveTextToolsUsageAndMetadataStayInMemoryUntilCompletion() = runBlocking {
        val writes = mutableListOf<Conversation>()
        val published = mutableListOf<Conversation>()
        val streamStates = mutableListOf<org.starfall.generative.features.home.chat.model.ActiveChatStreamState>()
        val reachedTool = CompletableDeferred<Unit>()
        val finishResponse = CompletableDeferred<Unit>()
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = { writes += it },
            publish = { published += it },
            publishStream = { _, state -> state?.let(streamStates::add) }
        )

        runner.startEvents(conversation(), "a1", flow {
            emit(GenerationEvent.Text("First"))
            emit(GenerationEvent.Reasoning("Thinking"))
            emit(GenerationEvent.Usage(TokenUsage(100, 10)))
            emit(GenerationEvent.Tool(ToolActivity("tool", "search", status = "running")))
            reachedTool.complete(Unit)
            finishResponse.await()
            emit(GenerationEvent.Tool(ToolActivity("tool", "search", status = "success")))
            emit(GenerationEvent.Text(" second"))
        }, usageContextKey = "test-context")

        reachedTool.await()
        assertTrue("No initial, timed, tool or usage database writes", writes.isEmpty())
        assertTrue(runner.busy.value)
        assertEquals("First", streamStates.last().content.materialize())
        assertEquals("Thinking", streamStates.last().reasoning.materialize())
        assertEquals("First", published.last().messages.last().content)
        assertEquals("running", published.last().messages.last().activeVersion.toolActivity.single().status)
        assertNotNull(published.last().messages.last().activeVersion.contextUsage)
        runner.updateConversation { it.copy(title = "Edited live") }
        assertTrue("Metadata changes must not write an unfinished block", writes.isEmpty())

        finishResponse.complete(Unit)
        runner.awaitCompletion()
        assertEquals(1, writes.size)
        assertEquals("Edited live", writes.single().title)
        assertEquals("First second", writes.single().messages.last().content)
        assertEquals("success", writes.single().messages.last().activeVersion.toolActivity.single().status)
        assertNotNull(writes.single().messages.last().activeVersion.contextUsage)
    }

    @Test fun completedToolTurnSavesOnceBeforeNextAssistantBlockFinishes() = runBlocking {
        val writes = mutableListOf<Conversation>()
        val firstBlockSaved = CompletableDeferred<Unit>()
        val nextBlockStarted = CompletableDeferred<Unit>()
        val releaseNextBlock = CompletableDeferred<Unit>()
        val runner = ChatGeneration(
            CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            { writes += it; if (writes.size == 1) firstBlockSaved.complete(Unit) }, {}
        )
        runner.startEvents(conversation(), "a1", flow {
            emit(GenerationEvent.Text("Completed first block"))
            emit(GenerationEvent.UserMessage(message("u2", ChatRole.USER, "Follow up")))
            emit(GenerationEvent.Text("Unfinished second block"))
            nextBlockStarted.complete(Unit)
            releaseNextBlock.await()
            emit(GenerationEvent.Text(" and done"))
        })
        nextBlockStarted.await()
        withTimeout(5_000) { firstBlockSaved.await() }
        assertEquals(1, writes.size)
        assertEquals("Completed first block", writes.single().messages[1].content)
        assertEquals(listOf("u1", "a1", "u2"), writes.single().messages.map { it.id })
        assertEquals("The next AI response is not written before it finishes", 3, writes.single().messages.size)
        releaseNextBlock.complete(Unit)
        runner.awaitCompletion()
        assertEquals(2, writes.size)
        assertEquals("Unfinished second block and done", writes.last().messages.last().content)
    }

    @Test fun titleChangesDuringGenerationSurviveTheFinalResponseCommit() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val continueResponse = CompletableDeferred<Unit>()
        var saved: Conversation? = null
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = { saved = it },
            publish = {}
        )

        runner.start(conversation(), "a1", flow {
            emit("partial")
            requestStarted.complete(Unit)
            continueResponse.await()
            emit("\nanswer")
        })
        requestStarted.await()
        runner.updateConversation { it.copy(title = "Renamed while streaming") }
        continueResponse.complete(Unit)
        runner.awaitCompletion()

        assertEquals("Renamed while streaming", saved!!.title)
        assertEquals("partial\nanswer", saved.messages.last().content)
    }

    @Test fun streamCommitsAllAvailableCharactersInOneRenderWindow() = runBlocking {
        val publishedConversations = mutableListOf<Conversation>()
        val streamStates = mutableListOf<org.starfall.generative.features.home.chat.model.ActiveChatStreamState>()
        val visibleBatch = CompletableDeferred<Unit>()
        val waits = mutableListOf<Long>()
        var now = 0L
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = {},
            publish = { publishedConversations += it },
            publishStream = { _, state -> state?.let {
                streamStates += it
                if (it.status == ChatStreamStatus.STREAMING && it.content.length > 0L) visibleBatch.complete(Unit)
            } },
            streamCommitIntervalMs = 50L,
            monotonicTimeMillis = { now },
            waitForStreamInterval = { duration ->
                waits += duration
                now += duration
            }
        )
        val output = "a".repeat(30) + "b".repeat(30) + "c".repeat(60)

        runner.start(conversation(), "a1", flow {
            emit(output)
            withTimeout(5_000) { visibleBatch.await() }
        })
        runner.awaitCompletion()

        assertEquals("ordinary stream batches do not publish Conversation per chunk", 2, publishedConversations.size)
        assertEquals("initial state, one render-window commit, and terminal lifecycle state", 3, streamStates.size)
        assertEquals(120, streamStates[1].lastCommitMetrics?.characterCount)
        assertEquals(120, streamStates[1].content.length.toInt())
        assertEquals(0, streamStates[1].lastCommitMetrics?.queuedCharacters)
        assertEquals(1L, streamStates[1].cumulativeMetrics.batchCount)
        assertEquals(120L, streamStates[1].cumulativeMetrics.committedCharacters)
        assertTrue(waits.isEmpty())
        assertTrue(streamStates[2].revision > streamStates[1].revision)
        assertEquals(ChatStreamStatus.COMPLETED, streamStates.last().status)
        assertEquals(output, streamStates.last().content.materialize())
    }

    @Test fun shortChunkIsRenderedImmediatelyWithoutACharacterLimit() = runBlocking {
        val streamStates = mutableListOf<org.starfall.generative.features.home.chat.model.ActiveChatStreamState>()
        var now = 0L
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = {},
            publish = {},
            publishStream = { _, state -> state?.let(streamStates::add) },
            streamCommitIntervalMs = 50L,
            monotonicTimeMillis = { now },
            waitForStreamInterval = { duration -> now += duration }
        )

        runner.start(conversation(), "a1", flow {
            emit("short text")
            awaitCancellation()
        })

        assertEquals(2, streamStates.size)
        assertEquals("short text", streamStates.last().content.materialize())
        assertEquals(10, streamStates.last().lastCommitMetrics?.characterCount)
        assertFalse(streamStates.last().lastCommitMetrics?.terminal == true)
        assertEquals(ChatStreamStatus.STREAMING, streamStates.last().status)
        runner.stopAndJoin()
    }

    @Test fun stopFreezesTheLastPublishedChunkWithoutATerminalRerender() = runBlocking {
        val published = mutableListOf<org.starfall.generative.features.home.chat.model.ActiveChatStreamState>()
        val visibleChunkPublished = CompletableDeferred<Unit>()
        val queuedChunkAccepted = CompletableDeferred<Unit>()
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = {},
            publish = {},
            publishStream = { _, state ->
                state?.let {
                    published += it
                    if (it.content.materialize() == "visible") visibleChunkPublished.complete(Unit)
                }
            },
            streamCommitIntervalMs = 50L,
            monotonicTimeMillis = { 0L },
            waitForStreamInterval = { awaitCancellation() }
        )

        runner.start(conversation(), "a1", flow {
            emit("visible")
            visibleChunkPublished.await()
            emit(" queued")
            queuedChunkAccepted.complete(Unit)
            awaitCancellation()
        })
        visibleChunkPublished.await()
        queuedChunkAccepted.await()

        assertEquals("visible", published.last().content.materialize())
        val publishedCountAtStop = published.size
        runner.stop()
        runner.stopAndJoin()

        assertEquals("Stop must not publish a terminal stream revision", publishedCountAtStop, published.size)
        assertEquals("visible", published.last().content.materialize())
        assertEquals(ChatStreamStatus.STREAMING, published.last().status)
    }

    @Test fun stoppingBeforeAnyResponseRemovesTheEmptyAssistantFromRamAndStorage() = runBlocking {
        var published: Conversation? = null
        var saved: Conversation? = null
        val started = CompletableDeferred<Unit>()
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = { saved = it }, publish = { published = it }
        )
        runner.start(conversation(), "a1", flow {
            started.complete(Unit)
            awaitCancellation()
        })
        started.await()
        runner.stop()
        runner.stopAndJoin()
        assertEquals(listOf("u1"), published!!.messages.map { it.id })
        assertEquals(listOf("u1"), saved!!.messages.map { it.id })
    }

    @Test fun aHiddenConnectToolDoesNotCreateAnEmptyStoppedReply() = runBlocking {
        var published: Conversation? = null
        var saved: Conversation? = null
        val toolReceived = CompletableDeferred<Unit>()
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = { saved = it }, publish = { published = it }
        )
        runner.startEvents(conversation(), "a1", flow {
            emit(GenerationEvent.Tool(ToolActivity("connect", "server: connect", status = "success")))
            toolReceived.complete(Unit)
            awaitCancellation()
        })
        toolReceived.await()
        runner.stop()
        runner.stopAndJoin()
        assertEquals(listOf("u1"), published!!.messages.map { it.id })
        assertEquals(listOf("u1"), saved!!.messages.map { it.id })
    }

    @Test fun stoppingWithReasoningOrAToolKeepsTheActualAssistantResponse() = runBlocking {
        for (event in listOf(
            GenerationEvent.Reasoning("Partially thought"),
            GenerationEvent.Tool(ToolActivity("search", "web_search", status = "success"))
        )) {
            var published: Conversation? = null
            var saved: Conversation? = null
            val received = CompletableDeferred<Unit>()
            val runner = ChatGeneration(
                scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
                save = { saved = it }, publish = { published = it }
            )
            runner.startEvents(conversation(), "a1", flow {
                emit(event)
                received.complete(Unit)
                awaitCancellation()
            })
            received.await()
            runner.stop()
            runner.stopAndJoin()
            assertEquals(listOf("u1", "a1"), published!!.messages.map { it.id })
            assertEquals(listOf("u1", "a1"), saved!!.messages.map { it.id })
        }
    }

    @Test fun stoppingPersistsExactlyTheFrozenVisibleSnapshotAndDropsQueuedText() = runBlocking {
        var saved: Conversation? = null
        val visibleChunkPublished = CompletableDeferred<Unit>()
        val queuedChunkAccepted = CompletableDeferred<Unit>()
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = { saved = it },
            publish = {},
            publishStream = { _, state ->
                if (state?.content?.materialize() == "visible") visibleChunkPublished.complete(Unit)
            },
            streamCommitIntervalMs = 50L,
            monotonicTimeMillis = { 0L },
            waitForStreamInterval = { awaitCancellation() }
        )

        runner.start(conversation(), "a1", flow {
            emit("visible")
            visibleChunkPublished.await()
            emit(" queued but not yet published")
            queuedChunkAccepted.complete(Unit)
            awaitCancellation()
        })
        visibleChunkPublished.await()
        queuedChunkAccepted.await()

        runner.stop()
        runner.stopAndJoin()

        assertEquals("visible", saved!!.messages.last().activeVersion.content)
        assertTrue(saved.messages.last().activeVersion.generationInterrupted)
    }

    @Test fun terminalFlushPublishesFinalShortCharacterChunkOnce() = runBlocking {
        val streamStates = mutableListOf<org.starfall.generative.features.home.chat.model.ActiveChatStreamState>()
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = {},
            publish = {},
            publishStream = { _, state -> state?.let(streamStates::add) },
            streamCommitIntervalMs = 50L,
            waitForStreamInterval = { awaitCancellation() }
        )

        runner.start(conversation(), "a1", flow {
            emit("short stream text")
            awaitCancellation()
        })
        assertEquals("the short chunk is visible before terminal completion", 2, streamStates.size)

        runner.stopAndJoin()

        assertEquals(3, streamStates.size)
        assertEquals("short stream text", streamStates[1].content.materialize())
        assertEquals(17, streamStates[1].lastCommitMetrics?.characterCount)
        assertFalse(streamStates[1].lastCommitMetrics?.terminal == true)
        assertEquals(ChatStreamStatus.CANCELLED, streamStates.last().status)
    }

    @Test fun busyRemainsSetWhileWaitingForTheFirstResponseEvent() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val responseReady = CompletableDeferred<Unit>()
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined), {}, {})

        runner.start(conversation(), "a1", flow {
            requestStarted.complete(Unit)
            responseReady.await()
            emit("first response")
        })

        requestStarted.await()
        assertTrue(runner.busy.value)

        responseReady.complete(Unit)
        runner.stopAndJoin()
        assertFalse(runner.busy.value)
    }

    @Test fun rapidSendIsRejectedAndCancellationSavesPartialText() = runBlocking {
        var saved: Conversation? = null
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined), { saved = it }, {})
        assertTrue(runner.start(conversation(), "a1", flow { emit("partial"); awaitCancellation() }))
        assertFalse(runner.start(conversation().copy(id = "b"), "a1", flowOf("wrong")))
        runner.stopAndJoin()
        assertEquals("a", saved!!.id)
        assertEquals("partial", saved.messages.last().content)
        assertNull(runner.error.value)
        assertFalse(runner.busy.value)
        assertTrue(runner.start(conversation().copy(id = "b"), "a1", flowOf("next")))
        runner.awaitCompletion()
        assertEquals("next", saved.messages.last().content)
    }

    @Test fun stuckMidTurnRoomWriteDoesNotStopFollowingStreamOrCancellation() = runBlocking {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val unblockRoom = CompletableDeferred<Unit>()
        val secondResponseReached = CompletableDeferred<Unit>()
        val saved = mutableListOf<Conversation>()
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined), { completed ->
            if (saved.isEmpty()) {
                firstWriteStarted.complete(Unit)
                unblockRoom.await()
            }
            saved += completed
        }, {})

        assertTrue(runner.startEvents(conversation(), "a1", flow {
            emit(GenerationEvent.Text("First tool block"))
            emit(GenerationEvent.UserMessage(message("u2", ChatRole.USER, "Continue")))
            emit(GenerationEvent.Text("Second tool block"))
            secondResponseReached.complete(Unit)
            awaitCancellation()
        }))
        withTimeout(5_000) { firstWriteStarted.await() }
        // The next response must be able to stream while Room has not finished its first write.
        withTimeout(5_000) { secondResponseReached.await() }
        assertTrue(runner.busy.value)
        runner.stop()
        withTimeout(5_000) { runner.busy.first { !it } }
        assertFalse(runner.busy.value)
        assertTrue(saved.isEmpty())

        unblockRoom.complete(Unit)
        withTimeout(5_000) { runner.awaitPersistence() }
        assertEquals(2, saved.size)
        assertEquals("First tool block", saved[0].messages[1].content)
        assertEquals("Second tool block", saved[1].messages.last().content)
    }

    @Test fun stopDoesNotWaitForBrokenDatabaseAndLaterSaveKeepsOrder() = runBlocking {
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        var saves = 0
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined), {
            if (++saves == 1) { saving.complete(Unit); release.await() }
            persisted += it.messages.last().activeVersion.content
        }, {})
        runner.start(conversation(), "a1", flow { emit("partial"); awaitCancellation() })
        runner.stop()
        withTimeout(5_000) { runner.busy.first { !it } }
        // Even while the save is still waiting on a deadlocked/damaged Room transaction,
        // the UI has stopped and the next generation can start purely in memory.
        saving.await()
        assertFalse(runner.busy.value)
        assertTrue(runner.start(conversation(), "a1", flowOf("next answer")))
        withTimeout(5_000) { runner.busy.first { !it } }
        assertEquals("The second save must not overtake the blocked first save", 1, saves)
        release.complete(Unit)
        withTimeout(5_000) { runner.awaitPersistence() }
        assertEquals(listOf("partial", "next answer"), persisted)
        assertFalse(runner.busy.value)
    }

    @Test fun deletingAfterStopCannotResurrectConversation() = runBlocking {
        val saved = mutableMapOf<String, Conversation>()
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined), { saved[it.id] = it }, {})
        runner.start(conversation(), "a1", flow { emit("partial"); awaitCancellation() })
        runner.stopAndJoin()
        saved.remove("a")
        yield()
        assertTrue(saved.isEmpty())
    }

    @Test fun regenerationTargetsSelectedAnswerAndPreservesVersions() {
        val original = conversation().copy(messages = listOf(
            message("u1", ChatRole.USER, "First question"), message("a1", ChatRole.MODEL, "Old answer"),
            message("u2", ChatRole.USER, "Second question"), message("a2", ChatRole.MODEL, "Last answer")
        ))
        val regenerated = prepareRegeneration(original, "a1")!!
        assertEquals(listOf("u1", "a1"), regenerated.messages.map { it.id })
        assertEquals("tail truncation advances the structure epoch", 1L, regenerated.structuralRevision)
        assertEquals("First question", regenerated.messages.dropLast(1).single().content)
        val updated = updateResponse(regenerated, "a1", "New answer")
        assertEquals("Old answer", updated.messages.last().versions.first().content)
        assertEquals("New answer", updated.messages.last().content)
        assertEquals(1, updated.messages.last().activeVersionIndex)
        assertNull(prepareRegeneration(original, "missing"))
        assertNull(prepareRegeneration(original, "u1"))
    }

    @Test fun regenerationReplacesAnErrorOnlyAnswerInsteadOfAddingAVersion() {
        val original = conversation().copy(messages = listOf(
            message("u1", ChatRole.USER, "First question"),
            StoredMessage("a1", ChatRole.MODEL, listOf(MessageVersion(generationError = "offline")))
        ))
        val replacement = MessageVersion(content = "new", providerId = "provider", modelId = "model")

        val regenerated = prepareRegeneration(original, "a1", replacement)!!

        assertEquals(1, regenerated.messages.last().versions.size)
        assertEquals(replacement, regenerated.messages.last().activeVersion)
        assertEquals(0, regenerated.messages.last().activeVersionIndex)
    }

    @Test fun emptyAssistantResponsesAreNotPersistedButErrorOnlyResponsesAre() = runBlocking {
        var saved: Conversation? = null
        val runner = ChatGeneration(
            CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            { saved = it },
            {}
        )

        runner.start(conversation(), "a1", emptyFlow())
        runner.awaitCompletion()
        assertEquals(listOf("u1"), saved!!.messages.map { it.id })

        runner.start(conversation(), "a1", flow { error("offline") })
        runner.awaitCompletion()
        assertEquals(listOf("u1", "a1"), saved.messages.map { it.id })
        assertEquals("offline", saved.messages.last().activeVersion.generationError)
    }

    @Test fun reasoningSurvivesStopAndCompletion() {
        val partial = updateResponse(conversation(), "a1", "<think>Working")
        assertEquals("Working", partial.messages.last().reasoningContent)
        assertEquals("", partial.messages.last().content)
        val complete = updateResponse(partial, "a1", "<think>Working</think>\nAnswer")
        assertEquals("Working", complete.messages.last().reasoningContent)
        assertEquals("Answer", complete.messages.last().content)
    }

    @Test fun finalRamPublicationPrecedesStreamReleaseAndBusyReset() = runBlocking {
        val events = mutableListOf<String>()
        lateinit var runner: ChatGeneration
        runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = {},
            publish = { snapshot ->
                if (snapshot.messages.last().content == "Complete response" &&
                    snapshot.messages.last().activeVersion.processingFinishedAt != null) {
                    assertTrue(runner.busy.value)
                    events += "final"
                }
            },
            publishStream = { _, state ->
                if (state == null) {
                    assertTrue("Stream release cannot precede the final RAM message", "final" in events)
                    assertTrue("Busy must still be true when the stream is released", runner.busy.value)
                    events += "cleared"
                }
            }
        )
        runner.start(conversation(), "a1", flowOf("Complete response"))
        runner.awaitCompletion()
        assertEquals(listOf("final", "cleared"), events)
        assertFalse(runner.busy.value)
    }

    @Test fun consecutiveGenerationsUseFreshIdsAndDoNotReuseThePreviousDisplaySnapshot() = runBlocking {
        val states = mutableListOf<org.starfall.generative.features.home.chat.model.ActiveChatStreamState>()
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = {},
            publish = {},
            publishStream = { _, state -> state?.let(states::add) }
        )

        runner.start(conversation(), "a1", flowOf("first response"))
        runner.awaitCompletion()
        val firstGenerationId = states.last().generationId
        runner.start(conversation(), "a1", flowOf("second response"))
        runner.awaitCompletion()

        val secondGenerationStates = states.filter { it.generationId != firstGenerationId }
        assertTrue(secondGenerationStates.isNotEmpty())
        assertTrue(secondGenerationStates.first().generationId > firstGenerationId)
        assertEquals("new generation begins empty", "", secondGenerationStates.first().content.materialize())
        assertEquals("second response", secondGenerationStates.last().content.materialize())
        assertFalse(secondGenerationStates.any { it.content.materialize().contains("first response") })
    }

    @Test fun toolTurnResetsTheActiveStreamIdentityAndPublishesTerminalLifecycle() = runBlocking {
        val states = mutableListOf<org.starfall.generative.features.home.chat.model.ActiveChatStreamState>()
        val clearedConversationIds = mutableListOf<String>()
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = {},
            publish = {},
            publishStream = { id, state ->
                if (state == null) clearedConversationIds += id else states += state
            }
        )
        val nextUser = message("u2", ChatRole.USER, "Continue")

        runner.startEvents(conversation(), "a1", flow {
            emit(GenerationEvent.Text("First answer\n"))
            emit(GenerationEvent.UserMessage(nextUser))
            emit(GenerationEvent.Text("Second answer"))
        })
        runner.awaitCompletion()

        val generationIds = states.map { it.generationId }.distinct()
        val secondMessageId = states.first { it.messageId != "a1" }.messageId
        val newMessageInitialState = states.first { it.messageId == secondMessageId }
        assertEquals(1, generationIds.size)
        assertTrue(generationIds.single() > 0L)
        assertEquals("First answer\n", states.last { it.messageId == "a1" }.content.materialize())
        assertEquals("a new assistant message starts with an empty display", "", newMessageInitialState.content.materialize())
        assertEquals("Second answer", states.last { it.messageId == secondMessageId }.content.materialize())
        assertEquals(ChatStreamStatus.COMPLETED, states.last().status)
        assertEquals(listOf("a"), clearedConversationIds)
    }

    @Test fun pendingUserMessageSplitsTheToolTurnBeforeTheNextAnswer() = runBlocking {
        var saved: Conversation? = null
        val runner = ChatGeneration(
            CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            { saved = it },
            {}
        )
        val pending = message("u2", ChatRole.USER, "Continue with this")
        runner.startEvents(conversation(), "a1", flow {
            emit(GenerationEvent.Tool(ToolActivity("tool-1", "search", status = "success")))
            emit(GenerationEvent.UserMessage(pending))
            emit(GenerationEvent.Text("New answer"))
        })
        runner.awaitCompletion()

        assertEquals(listOf("u1", "a1", "u2"), saved!!.messages.dropLast(1).map { it.id })
        assertEquals(ChatRole.USER, saved.messages[2].role)
        assertEquals("New answer", saved.messages.last().content)
        assertEquals(1L, saved.structuralRevision)
        assertTrue(saved.messages[1].activeVersion.processingFinishedAt != null)
    }

    @Test fun failureRetainsPartialResponseAndReleasesBusyState() = runBlocking {
        var saved: Conversation? = null
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined), { saved = it }, {})
        runner.start(conversation(), "a1", flow { emit("partial"); error("offline") })
        runner.awaitCompletion()
        assertTrue(saved!!.messages.last().content.startsWith("partial"))
        assertEquals("offline", saved.messages.last().activeVersion.generationError)
        assertEquals("offline", runner.error.value)
        assertFalse(runner.busy.value)
    }

    @Test fun abruptlyClosedReasoningStreamKeepsThinkingAndShowsErrorBlock() = runBlocking {
        var saved: Conversation? = null
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            { saved = it }, {})
        runner.startEvents(conversation(), "a1", flow {
            emit(GenerationEvent.Reasoning("Partially thought through"))
            throw IllegalStateException(
                org.starfall.generative.shared.model.generation.UNEXPECTED_STREAM_END)
        })
        runner.awaitCompletion()
        val finalVersion = requireNotNull(saved).messages.last().activeVersion
        assertEquals("Partially thought through", finalVersion.reasoningContent)
        assertEquals(org.starfall.generative.shared.model.generation.UNEXPECTED_STREAM_END,
            finalVersion.generationError)
        assertFalse(finalVersion.generationInterrupted)
        assertEquals(org.starfall.generative.shared.model.generation.UNEXPECTED_STREAM_END,
            runner.error.value)
        assertFalse(runner.busy.value)
    }

    @Test fun failurePublishesTerminalStreamStatusEvenWhenNoTextWasCommitted() = runBlocking {
        val states = mutableListOf<org.starfall.generative.features.home.chat.model.ActiveChatStreamState>()
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = {},
            publish = {},
            publishStream = { _, state -> state?.let(states::add) }
        )

        runner.start(conversation(), "a1", flow { error("offline") })
        runner.awaitCompletion()

        assertEquals(2, states.size)
        assertEquals(ChatStreamStatus.FAILED, states.last().status)
        assertEquals(0L, states.last().cumulativeMetrics.batchCount)
    }

    @Test fun failureFlushesTheFinalPartialLineIntoStreamState() = runBlocking {
        val states = mutableListOf<org.starfall.generative.features.home.chat.model.ActiveChatStreamState>()
        val runner = ChatGeneration(
            scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = {},
            publish = {},
            publishStream = { _, state -> state?.let(states::add) }
        )

        runner.start(conversation(), "a1", flow {
            emit("partial line before failure")
            error("offline")
        })
        runner.awaitCompletion()

        assertEquals("partial line before failure", states.last().content.materialize())
        assertEquals(ChatStreamStatus.FAILED, states.last().status)
        assertTrue(states.last().lastCommitMetrics?.terminal == true)
    }

    @Test fun clearingOwnerScopePersistsPartialResponseAndReleasesGeneration() = runBlocking {
        val owner = Job(coroutineContext[Job])
        var saved: Conversation? = null
        val runner = ChatGeneration(
            CoroutineScope(owner + Dispatchers.Unconfined),
            { saved = it },
            {}
        )
        runner.start(conversation(), "a1", flow {
            emit("partial before Activity finishes")
            awaitCancellation()
        })

        owner.cancelAndJoin()
        runner.awaitPersistence()

        assertEquals("partial before Activity finishes", saved!!.messages.last().content)
        assertFalse(runner.busy.value)
        assertNull(runner.conversationId.value)
        assertNull(runner.error.value)
    }
    @Test fun lateStopDuringFinalPublicationCannotVetoTheOnlySave() = runBlocking {
        var saved: Conversation? = null
        var saves = 0
        lateinit var runner: ChatGeneration
        runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = { saved = it; saves++ },
            publish = { current ->
                if (current.messages.lastOrNull()?.activeVersion?.processingFinishedAt != null) runner.stop()
            })
        runner.start(conversation(), "a1", flowOf("The final response"))
        runner.awaitCompletion()
        assertEquals("A late Stop cannot skip final persistence", 1, saves)
        assertEquals("The final response", saved!!.messages.last().content)
        assertNull(runner.snapshot)
    }

    @Test fun failedFinalSaveRetainsTheCompleteSnapshotAndRunsOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        var saveThread: Thread? = null
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            save = { saveThread = Thread.currentThread(); throw java.io.IOException("disk unavailable") },
            publish = {})
        runner.start(conversation(), "a1", flowOf("Do not lose this answer"))
        runner.awaitCompletion()
        assertNotSame(caller, saveThread)
        assertEquals("Do not lose this answer", runner.snapshot!!.messages.last().content)
        assertTrue(runner.error.value!!.startsWith("Could not save conversation:"))
        assertFalse(runner.busy.value)
        runner.discardFinalSnapshot()
        assertNull("An explicit delete can discard retained failed data", runner.snapshot)
    }

    @Test fun alreadyCancelledOwnerStillInstallsTerminalPersistence() = runBlocking {
        val cancelledOwner = Job().also { it.cancel() }
        var saves = 0
        var saved: Conversation? = null
        val runner = ChatGeneration(CoroutineScope(cancelledOwner + Dispatchers.Unconfined),
            save = { saved = it; saves++ }, publish = {})
        runner.start(conversation(), "a1", flowOf("Must not be consumed after cancellation"))
        runner.awaitCompletion()
        assertEquals(1, saves)
        assertEquals("First question", saved!!.messages.first().content)
        assertFalse(runner.busy.value)
    }

}
