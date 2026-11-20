package org.starfall.generative.features.home.chat.logic.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.starfall.generative.integrations.opencode.OpenCodeApi
import org.starfall.generative.integrations.opencode.OpenCodeChat
import org.starfall.generative.integrations.opencode.OpenCodeReplyEvents
import org.starfall.generative.integrations.opencode.eventType
import org.starfall.generative.integrations.opencode.listItem
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage

/** Owns the active OpenCode connection. All jobs are cancelled when the runtime changes. */
internal class OpenCodeWorkspace(
    private val id: String,
    parentScope: CoroutineScope,
    private val state: MutableStateFlow<RemoteWorkspaceState>,
    private val loadLastSession: suspend (String) -> String?,
    private val saveLastSession: suspend (String, String?) -> Unit
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var closed = false
    private lateinit var chat: OpenCodeChat
    private var operation: Job? = null
    private var interrupt: Job? = null
    private var pendingDeletes = 0
    private var runningId: String? = null

    fun close() {
        closed = true
        scope.cancel()
    }

    suspend fun connect(api: OpenCodeApi) {
        chat = OpenCodeChat(api, api.defaultDirectory())
        val providers = chat.providers()
        val items = chat.list()
        val remembered = loadLastSession(id)?.takeIf { previous -> items.any { it.id == previous } }
        state.update { it.copy(items = items, providers = providers,
            providerId = providers.firstOrNull()?.id.orEmpty(),
            modelId = providers.firstOrNull()?.config?.modelIds?.firstOrNull().orEmpty(),
            busy = remembered != null) }
        if (remembered != null) select(remembered, restoring = true)
    }

    fun select(sessionId: String, restoring: Boolean = false) {
        if (!::chat.isInitialized || (state.value.busy && !restoring) || state.value.running ||
            state.value.items.none { it.id == sessionId }) return
        perform(RemoteActionFailure.CONVERSATION_LOAD_FAILED) {
            val conversation = chat.open(sessionId)
            saveLastSession(id, sessionId)
            state.update { current ->
                val hasModel = conversation.providerId.isNotBlank() && conversation.modelId.isNotBlank()
                val available = current.providers.any {
                    it.id == conversation.providerId && it.config.modelIds?.contains(conversation.modelId) == true
                }
                current.copy(conversation = conversation,
                    providerId = if (!hasModel) current.providerId else conversation.providerId.takeIf { available }.orEmpty(),
                    modelId = if (!hasModel) current.modelId else conversation.modelId.takeIf { available }.orEmpty())
            }
        }
    }

    fun newChat() {
        if (!::chat.isInitialized || state.value.busy || state.value.running) return
        perform {
            saveLastSession(id, null)
            state.update { it.copy(conversation = null) }
        }
    }

    fun selectModel(provider: String, model: String) {
        state.update { current ->
            if (current.busy || current.running || current.providers.none {
                    it.id == provider && it.config.modelIds?.contains(model) == true
                }) current
            else current.copy(providerId = provider, modelId = model, actionFailure = null)
        }
    }

    fun send(text: String, files: List<String>): Boolean {
        val current = state.value
        if (!::chat.isInitialized || current.busy || current.running || current.error || files.isNotEmpty() ||
            text.isBlank() || text.toByteArray(Charsets.UTF_8).size > current.capabilities.maxRunContentBytes ||
            current.providerId.isBlank() || current.modelId.isBlank()) return false
        perform(RemoteActionFailure.GENERATION_FAILED) {
            val providerId = current.providerId
            val modelId = current.modelId
            val existing = current.conversation
            val session = if (existing == null) {
                val created = try {
                    chat.create(providerId, modelId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state.update { it.copy(actionFailure = RemoteActionFailure.CONVERSATION_CREATE_FAILED) }
                    return@perform
                }
                publish(created)
                // Remembering the selection is optional. A failed local preference write
                // must not prevent the prompt from reaching an already created session.
                try { saveLastSession(id, created.id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The server already owns the new session. */ }
                created
            } else {
                if (existing.providerId != providerId || existing.modelId != modelId) {
                    chat.api.setModel(existing.id, providerId, modelId)
                }
                existing
            }
            streamTurn(session, providerId, modelId, text)
        }
        return true
    }

    private suspend fun streamTurn(session: Conversation, providerId: String, modelId: String, text: String) = coroutineScope {
        val ready = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<OpenCodeReplyEvents>()
        val events = launch {
            try {
                chat.api.events().collect { event ->
                    if (event.eventType() == "server.connected") {
                        ready.complete(Unit)
                    } else {
                        val message = reply.await().message(event) ?: return@collect
                        state.update { current ->
                            val active = current.conversation?.takeIf { it.id == session.id } ?: return@update current
                            val existing = active.messages.indexOfFirst { it.id == message.id }
                            val messages = if (existing < 0) active.messages + message
                            else active.messages.toMutableList().apply { this[existing] = message }
                            current.copy(conversation = active.copy(
                                messages = messages,
                                structuralRevision = active.structuralRevision + if (existing < 0) 1 else 0
                            ))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // History polling remains authoritative when the event connection is unavailable.
            } finally {
                ready.complete(Unit)
            }
        }
        try {
            withTimeoutOrNull(1_000) { ready.await() }
            val prompt = chat.api.prompt(session.id, text)
            val submitted = StoredMessage(prompt, ChatRole.USER, listOf(MessageVersion(content = text)))
            runningId = session.id
            state.update { it.copy(running = true) }
            publish(session.copy(
                messages = session.messages + submitted, providerId = providerId, modelId = modelId,
                structuralRevision = session.structuralRevision + 1
            ))
            reply.complete(OpenCodeReplyEvents(session.id, prompt, providerId, modelId))

            val completed = withTimeoutOrNull(10 * 60 * 1000L) {
                while (true) {
                    val turn = chat.readTurn(session.id, prompt)
                    if (turn.outcome != null) return@withTimeoutOrNull turn
                    if (turn.messages.any { it.id == prompt }) {
                        state.update { current ->
                            val active = current.conversation?.takeIf { it.id == session.id } ?: return@update current
                            val visibleById = active.messages.associateBy { it.id }
                            val seen = turn.messages.mapTo(hashSetOf()) { it.id }
                            val messages = turn.messages.map { stored ->
                                val visible = visibleById[stored.id]
                                if (stored.role == ChatRole.MODEL && visible != null) {
                                    mergeStreamingReply(stored, visible)
                                } else stored
                            } + active.messages.filter { it.id !in seen }
                            current.copy(conversation = active.copy(
                                messages = messages,
                                structuralRevision = active.structuralRevision +
                                    if (messages.map { it.id } == active.messages.map { it.id }) 0 else 1
                            ))
                        }
                    }
                    delay(1_000)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            events.cancelAndJoin()
            if (completed != null) {
                // The terminal history already contains this turn. Read metadata separately
                // so a lagging second history response cannot erase the completed reply.
                val active = state.value.conversation ?: session
                publish(active.copy(messages = completed.messages, structuralRevision = active.structuralRevision + 1))
                val metadata = try { chat.session(session.id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                if (metadata != null) {
                    publish(metadata.copy(messages = completed.messages, structuralRevision = active.structuralRevision + 1))
                }
            }
            if (completed == null || completed.outcome == "failed") {
                state.update { it.copy(actionFailure = RemoteActionFailure.GENERATION_FAILED) }
            }
        } finally {
            events.cancel()
            runningId = null
            interrupt?.cancel()
        }
    }

    private fun mergeStreamingReply(stored: StoredMessage, visible: StoredMessage): StoredMessage {
        val durable = stored.activeVersion
        val live = visible.activeVersion
        val liveTools = live.toolActivity.associateBy { it.id }
        val durableIds = durable.toolActivity.mapTo(hashSetOf()) { it.id }
        val tools = durable.toolActivity.map { tool ->
            liveTools[tool.id]?.takeIf { tool.status == "running" } ?: tool
        } + live.toolActivity.filter { it.id !in durableIds }
        val reasoning = live.reasoningContent?.takeIf {
            it.startsWith(durable.reasoningContent.orEmpty())
        } ?: durable.reasoningContent
        return stored.copy(versions = listOf(durable.copy(
            content = live.content.takeIf { it.startsWith(durable.content) } ?: durable.content,
            reasoningContent = reasoning,
            reasoningContentOffset = if (durable.reasoningContent == null) live.reasoningContentOffset
                else durable.reasoningContentOffset,
            toolActivity = tools
        )))
    }

    fun stop() {
        val sessionId = runningId ?: return
        if (interrupt?.isActive == true) return
        interrupt = scope.launch {
            try { chat.api.interrupt(sessionId) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.update { it.copy(actionFailure = RemoteActionFailure.OPERATION_FAILED) } }
        }
    }

    fun rename(sessionId: String, title: String) {
        if (!::chat.isInitialized || state.value.busy || state.value.running || title.isBlank()) return
        perform {
            chat.api.rename(sessionId, title)
            state.update { current ->
                current.copy(
                    items = current.items.map { if (it.id == sessionId) it.copy(title = title) else it },
                    conversation = current.conversation?.let { if (it.id == sessionId) it.copy(title = title) else it }
                )
            }
        }
    }

    fun delete(sessionId: String) {
        if (!::chat.isInitialized || state.value.running || (state.value.busy && pendingDeletes == 0)) return
        // Queue drawer multi-selection deletions in the order they were requested.
        val previous = operation
        pendingDeletes++
        state.update { it.copy(busy = true, actionFailure = null) }
        operation = scope.launch {
            previous?.join()
            try {
                chat.api.delete(sessionId)
                val active = state.value.conversation?.id == sessionId
                state.update { it.copy(items = it.items.filterNot { row -> row.id == sessionId },
                    conversation = it.conversation?.takeUnless { conversation -> conversation.id == sessionId }) }
                if (active) saveLastSession(id, null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.update { it.copy(actionFailure = RemoteActionFailure.OPERATION_FAILED) } }
            finally {
                pendingDeletes--
                if (!closed) state.update { it.copy(busy = pendingDeletes > 0) }
            }
        }
    }

    private fun publish(conversation: Conversation) {
        state.update { current ->
            current.copy(conversation = conversation, items =
                (listOf(conversation.listItem()) + current.items.filterNot { it.id == conversation.id })
                    .sortedByDescending { it.updatedAt })
        }
    }

    private fun perform(failure: RemoteActionFailure = RemoteActionFailure.OPERATION_FAILED, action: suspend () -> Unit) {
        state.update { it.copy(busy = true, actionFailure = null) }
        operation = scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.update { it.copy(actionFailure = failure) } }
            finally { if (!closed) state.update { it.copy(busy = false, running = false) } }
        }
    }
}
