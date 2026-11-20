package org.starfall.generative.features.home.chat.logic.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.starfall.generative.integrations.opencode.OpenCodeApi
import org.starfall.generative.integrations.opencode.OpenCodeChat
import org.starfall.generative.integrations.opencode.listItem
import org.starfall.generative.shared.model.Conversation

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
            val session = current.conversation ?: chat.create().also { created ->
                publish(created)
                saveLastSession(id, created.id)
            }
            chat.api.setModel(session.id, current.providerId, current.modelId)
            val prompt = chat.api.prompt(session.id, text)
            runningId = session.id
            state.update { it.copy(running = true) }
            try {
                val outcome = withTimeoutOrNull(10 * 60 * 1000L) {
                    var result: String?
                    do {
                        val turn = chat.readTurn(session.id, prompt)
                        state.update { it.copy(conversation = session.copy(messages = turn.messages,
                            providerId = current.providerId, modelId = current.modelId)) }
                        result = turn.outcome
                        if (result == null) delay(1_000)
                    } while (result == null)
                    result
                }
                publish(chat.open(session.id))
                if (outcome == null || outcome == "failed") {
                    state.update { it.copy(actionFailure = RemoteActionFailure.GENERATION_FAILED) }
                }
            } finally {
                runningId = null
                interrupt?.cancel()
            }
        }
        return true
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
