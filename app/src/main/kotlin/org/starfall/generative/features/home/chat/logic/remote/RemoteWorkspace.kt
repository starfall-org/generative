package org.starfall.generative.features.home.chat.logic.remote

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.starfall.generative.database.repositories.RemoteCacheScope
import org.starfall.generative.database.repositories.RemoteConversationCache
import org.starfall.generative.database.repositories.ServerConnectionRepository
import org.starfall.generative.integrations.serverside.*
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.api.ModelApiType

/** Remote state never writes to the local conversation repository. */
data class RemoteWorkspaceState(
    val enabled: Boolean = false, val restoring: Boolean = false, val connectionId: String? = null,
    val items: List<ConversationListItem> = emptyList(), val revisions: Map<String, Long> = emptyMap(), val snapshot: ServerSnapshot? = null,
    val conversation: Conversation? = null, val providers: List<ModelProvider> = emptyList(),
    val providerId: String = "", val modelId: String = "", val keyId: String? = null,
    val pendingKeys: List<ServerProviderKey> = emptyList(), val busy: Boolean = false,
    val running: Boolean = false, val error: Boolean = false,
)

class RemoteWorkspace(private val sessionFactory: suspend (String) -> ServerSideSessions, private val scope: CoroutineScope,
    private val loadLastSession: suspend (String) -> String? = { null },
    private val saveLastSession: suspend (String, String?) -> Unit = { _, _ -> },
    private val loadActivation: (suspend () -> String?)? = null,
    private val saveActivation: suspend (String?) -> Unit = {},
    private val cache: RemoteConversationCache? = null,
    private val cacheScope: suspend (String) -> RemoteCacheScope? = { null }) {
    constructor(repository: ServerConnectionRepository, scope: CoroutineScope,
        loadLastSession: suspend (String) -> String? = { null }, saveLastSession: suspend (String, String?) -> Unit = { _, _ -> },
        loadActivation: (suspend () -> String?)? = null, saveActivation: suspend (String?) -> Unit = {},
        cache: RemoteConversationCache? = null) :
        this({ id -> ServerSideSessions(repository.client(id)) }, scope, loadLastSession, saveLastSession, loadActivation, saveActivation,
            cache, repository::cacheScope)
    private val mutable = MutableStateFlow(RemoteWorkspaceState(restoring = loadActivation != null))
    val state: StateFlow<RemoteWorkspaceState> = mutable.asStateFlow()
    private var generation = 0L
    private var sessions: ServerSideSessions? = null
    private var stream: Job? = null
    private var requestId: String? = null
    private var providerKeys = emptyMap<String, List<ServerProviderKey>>()
    private var pendingSelection: Pair<String, String>? = null
    private val lastSession = mutableMapOf<String, String?>()
    // Event sequence numbers belong to the active server session, not the durable chat.
    // Keep cursors only while this connection is active; never restore them after reconnect.
    private val eventCursors = mutableMapOf<String, Long>()
    private var activeCacheScope: RemoteCacheScope? = null
    private suspend fun <T> cacheOrNull(action: suspend () -> T): T? = try { action() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }
    private suspend fun cached(id: String, owner: RemoteCacheScope?): ServerSnapshot? =
        if (owner == null || cache == null) null else cacheOrNull { cache.snapshot(owner, id) }
    private suspend fun store(snapshot: ServerSnapshot, owner: RemoteCacheScope?) {
        if (owner != null && cache != null) cacheOrNull { cache.save(owner, snapshot) }
    }
    private suspend fun storeList(items: List<SessionListItem>, owner: RemoteCacheScope?) {
        if (owner != null && cache != null) cacheOrNull {
            cache.saveList(owner, items)
            cache.prune(owner, items.mapTo(mutableSetOf()) { it.id })
        }
    }
    private val activationWrites = Mutex()
    private var activationError = false
    init {
        if (loadActivation != null) scope.launch {
            try {
                val id = loadActivation()
                if (generation != 0L) return@launch
                if (id == null) mutable.value = RemoteWorkspaceState() else enable(id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == 0L) mutable.value = RemoteWorkspaceState(enabled = true, error = true) }
        }
    }
    private fun persistActivation(id: String?, epoch: Long): Job = scope.launch {
            try { activationWrites.withLock { if (epoch == generation) saveActivation(id) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (epoch == generation) { activationError = true; mutable.update { it.copy(error = true) } } }
    }

    fun disable() {
        generation++; stream?.cancel(); stream = null; sessions = null; requestId = null; eventCursors.clear(); activeCacheScope = null; providerKeys = emptyMap(); pendingSelection = null
        mutable.value = RemoteWorkspaceState()
        persistActivation(null, generation)
    }
    fun profileRemoved() {
        generation++; stream?.cancel(); sessions = null; requestId = null; eventCursors.clear(); activeCacheScope = null; providerKeys = emptyMap(); pendingSelection = null
        mutable.value = RemoteWorkspaceState(enabled = true, error = true)
    }
    fun enable(id: String) {
        generation++; val epoch = generation
        stream?.cancel(); sessions = null; requestId = null; eventCursors.clear(); activeCacheScope = null; providerKeys = emptyMap(); pendingSelection = null
        activationError = false
        mutable.value = RemoteWorkspaceState(enabled = true, connectionId = id, busy = true)
        val activation = persistActivation(id, epoch)
        scope.launch {
            try {
                activation.join()
                if (epoch != generation) return@launch
                if (activationError) { mutable.update { it.copy(busy = false, error = true) }; return@launch }
                // Private, encrypted cache provides an immediate read-only view while
                // the server is checked; the server list/revision is authoritative.
                val owner = cacheOrNull { cacheScope(id) }
                val remembered = if (lastSession.containsKey(id)) lastSession[id] else loadLastSession(id)
                val localList = if (owner == null || cache == null) null else cacheOrNull { cache.list(owner) }
                val initial = remembered?.let { previous ->
                    localList?.firstOrNull { it.id == previous && it.deletedAt == null }
                        ?.let { row -> cached(previous, owner)?.takeIf { it.revision == row.revision } }
                }
                if (epoch != generation) return@launch
                activeCacheScope = owner
                if (localList != null) mutable.update { it.copy(
                    items = localList.filter { row -> row.deletedAt == null }.map { row -> row.ui() },
                    revisions = localList.filter { row -> row.deletedAt == null }.associate { row -> row.id to row.revision },
                    snapshot = initial, conversation = initial?.ui()) }

                val api = sessionFactory(id)
                api.verifyCapabilities()
                val catalog = api.catalog(); require(catalog.version == 1)
                val providers = catalog.providers.mapNotNull { p ->
                    val type = when (p.type) { "openai" -> ModelApiType.OPENAI; "openai_responses" -> ModelApiType.OPENAI_RESPONSES; "anthropic" -> ModelApiType.ANTHROPIC; "google" -> ModelApiType.GOOGLE; else -> return@mapNotNull null }
                    val models = p.models.filter { it.textGeneration && (it.availability == "available" || it.availability == "key_selection_required") }
                    if (models.isEmpty()) return@mapNotNull null
                    ModelProvider(id = p.id, name = p.name, type = type, baseUrl = "", config = ProviderConfiguration(
                        modelIds = models.map { it.id }, modelConfigs = models.associate { it.id to ModelConfiguration(displayName = it.name, supportsVision = false, supportsThinking = false, supportsToolCalls = false) }))
                }
                val list = api.list().filter { it.deletedAt == null }
                val prior = remembered?.takeIf { previous -> list.any { it.id == previous } }
                val hint = prior?.let { previous ->
                    (initial?.takeIf { it.sessionId == previous } ?: cached(previous, owner))
                        ?.takeIf { item -> list.any { it.id == previous && it.revision == item.revision } }
                }
                val opened = prior?.let { api.open(it, hint) }
                if (epoch != generation) return@launch
                // Complete the cache checkpoint before declaring activation ready,
                // so an immediate app close/reconnect cannot lose the fetched chat.
                storeList(list, owner)
                if (opened != null) store(opened, owner)
                if (epoch != generation) return@launch
                sessions = api
                providerKeys = catalog.providers.filter { it.keySelectionRequired }.associate { p -> p.id to p.keys.filter { it.availability == "available" } }
                val provider = providers.firstOrNull { it.id !in providerKeys }; val model = provider?.config?.modelIds?.firstOrNull().orEmpty()
                mutable.value = RemoteWorkspaceState(enabled = true, connectionId = id, items = list.map { it.ui() }, revisions = list.associate { it.id to it.revision }, providers = providers,
                    providerId = provider?.id.orEmpty(), modelId = model, snapshot = opened,
                    conversation = opened?.ui(), busy = false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (epoch == generation) mutable.update { it.copy(busy = false, error = true) } }
        }
    }
    fun selectModel(provider: String, model: String) {
        if (!mutable.value.providers.any { it.id == provider && it.config.modelIds?.contains(model) == true }) return
        val keys = providerKeys[provider]
        if (keys != null) {
            if (keys.isEmpty()) return
            pendingSelection = provider to model
            mutable.update { it.copy(pendingKeys = keys) }
        } else {
            pendingSelection = null
            mutable.update { it.copy(providerId = provider, modelId = model, keyId = null, pendingKeys = emptyList()) }
        }
    }
    fun selectKey(id: String) {
        val selection = pendingSelection ?: return
        if (mutable.value.pendingKeys.none { it.id == id }) return
        pendingSelection = null
        mutable.update { it.copy(providerId = selection.first, modelId = selection.second, keyId = id, pendingKeys = emptyList()) }
    }
    fun dismissKeySelection() { pendingSelection = null; mutable.update { it.copy(pendingKeys = emptyList()) } }
    fun select(id: String) {
        val connection = mutable.value.connectionId ?: return
        val owner = activeCacheScope
        val api = sessions
        if (api == null && owner == null) return
        val expected = mutable.value.revisions[id] ?: return
        val epoch = ++generation
        stream?.cancel(); requestId = null
        mutable.update { it.copy(snapshot = null, conversation = null, busy = true, running = false, error = api == null) }
        scope.launch { try {
            val hint = cached(id, owner)?.takeIf { it.revision == expected }
            if (epoch != generation) return@launch
            if (hint != null) mutable.update { it.copy(snapshot = hint, conversation = hint.ui()) }
            if (api == null) {
                // Offline browsing stays read-only, but remembers the last viewed chat.
                lastSession[connection] = id
                cacheOrNull { saveLastSession(connection, id) }
                if (epoch == generation) mutable.update { it.copy(busy = false, error = true) }
                return@launch
            }
            val snap = api.open(id, hint)
            if (epoch != generation) return@launch
            lastSession[connection] = id
            saveLastSession(connection, id)
            if (epoch != generation) return@launch
            store(snap, owner)
            if (epoch == generation) mutable.update { it.copy(snapshot = snap, conversation = snap.ui(), busy = false, error = false) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { if (epoch == generation) mutable.update { it.copy(busy = false, error = true) } } }
    }
    fun newChat() {
        generation++
        stream?.cancel(); requestId = null
        mutable.value.connectionId?.let { connection ->
            lastSession[connection] = null
            val epoch = generation
            scope.launch {
                try { saveLastSession(connection, null) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (epoch == generation) mutable.update { it.copy(error = true) } }
            }
        }
        mutable.update { it.copy(snapshot = null, conversation = null, running = false, busy = false, error = false) }
    }
    fun send(text: String, files: List<String>): Boolean {
        val current = mutable.value; val api = sessions ?: return false
        if (!current.enabled || current.busy || current.running || current.error || text.isBlank() || files.isNotEmpty() || current.modelId.isEmpty()) return false
        // The server currently runs only whole snapshots. Keep the draft if the
        // existing history and new turn cannot safely fit that bounded envelope.
        val size = current.snapshot?.conversation?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0
        if (size.toLong() + text.toByteArray(Charsets.UTF_8).size + 16 * 1024 > 3L * 1024 * 1024) return false
        val epoch = generation; val id = current.snapshot?.sessionId ?: UUID.randomUUID().toString(); val request = UUID.randomUUID().toString()
        requestId = request; mutable.update { it.copy(busy = true, error = false) }
        stream = scope.launch {
            try {
                // Reopen to obtain the server's *current* in-memory event cursor.
                // A retained cursor may refer to an expired/restarted session.
                val snap = api.open(id, current.snapshot)
                val startingCursor = snap.eventSeq ?: eventCursors[id] ?: 0L
                api.configure(id, current.providerId, current.modelId, current.keyId)
                if (epoch != generation) return@launch
                lastSession[current.connectionId!!] = id
                saveLastSession(current.connectionId, id)
                if (epoch != generation) return@launch
                mutable.update { it.copy(snapshot = snap, conversation = snap.ui(), running = true, busy = false) }
                // A failed run acknowledgement is ambiguous and is never replayed.
                api.run(id, request, text)
                var delta = ""
                api.events(id, startingCursor).collect { event ->
                    if (epoch != generation) return@collect
                    // Advance on every event, including those from another request. The
                    // server expires old events, so restarting at zero would create a gap.
                    eventCursors[id] = event.seq
                    if (event.requestId != request) return@collect
                    when (event.kind) {
                        "delta" -> { delta += event.data?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty(); val base = mutable.value.snapshot; if (base != null) mutable.update { it.copy(conversation = base.ui(delta)) } }
                        "conversation" -> {
                            val data = event.data?.jsonObject ?: return@collect
                            val revision = data.getValue("revision").jsonPrimitive.long
                            val conversation = data.getValue("conversation").jsonObject
                            if (revision <= 0 || conversation["id"]?.jsonPrimitive?.content != id) {
                                throw ServerSideProtocolException("Invalid conversation event")
                            }
                            if (revision > (mutable.value.snapshot?.revision ?: 0)) {
                                val update = ServerSnapshot(id, revision, conversation)
                                delta = ""; mutable.update { it.copy(snapshot = update, conversation = update.ui()) }
                            }
                        }
                        "completed", "error" -> {
                            val updated = api.get(id, mutable.value.snapshot?.takeIf { it.sessionId == id })
                            val list = api.list().filter { it.deletedAt == null }
                            if (epoch == generation) {
                                mutable.update { it.copy(snapshot = updated, conversation = updated.ui(), items = list.map { row -> row.ui() }, revisions = list.associate { row -> row.id to row.revision }, running = false, error = event.kind == "error") }
                                store(updated, activeCacheScope)
                                storeList(list, activeCacheScope)
                            }
                            throw FinishedStream()
                        }
                    }
                }
            } catch (_: FinishedStream) { }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // Reload once for authoritative state, without resending the prompt.
                val recovered = runCatching { api.get(id, mutable.value.snapshot?.takeIf { it.sessionId == id }) }.getOrNull()
                if (epoch == generation) {
                    mutable.update { it.copy(snapshot = recovered ?: it.snapshot, conversation = recovered?.ui() ?: it.conversation, busy = false, running = false, error = true) }
                    if (recovered != null) store(recovered, activeCacheScope)
                }
            } finally { if (epoch == generation) requestId = null }
        }
        return true
    }
    fun stop() { val api = sessions ?: return; val id = mutable.value.snapshot?.sessionId ?: return; val request = requestId ?: return
        scope.launch { runCatching { api.cancel(id, request) } }
    }
    fun rename(id: String, title: String) = change(id) { snap -> JsonObject(snap.conversation + ("title" to JsonPrimitive(title))) }
    fun editMessage(id: String, content: String, files: List<String>): Boolean {
        val snapshot = mutable.value.snapshot ?: return false
        if (files.isNotEmpty() || mutable.value.running || mutable.value.busy) return false
        change(snapshot.sessionId) { snap ->
            val messages = snap.conversation.getValue("messages") as JsonArray
            var found = false
            val edited = messages.map { element ->
                val message = element.jsonObject
                if (message["id"]?.jsonPrimitive?.content != id) element else {
                    found = true
                    val versions = message.getValue("versions") as JsonArray
                    val active = message["active_version_index"]?.jsonPrimitive?.intOrNull ?: 0
                    require(active in versions.indices)
                    val changed = versions.mapIndexed { index, value -> if (index == active) JsonObject(value.jsonObject + ("content" to JsonPrimitive(content))) else value }
                    JsonObject(message + ("versions" to JsonArray(changed)))
                }
            }
            require(found)
            JsonObject(snap.conversation + ("messages" to JsonArray(edited)))
        }
        return true
    }
    fun deleteMessage(id: String) {
        val snapshot = mutable.value.snapshot ?: return
        if (mutable.value.running || mutable.value.busy) return
        change(snapshot.sessionId) { snap ->
            val messages = snap.conversation.getValue("messages") as JsonArray
            val remaining = messages.filter { it.jsonObject["id"]?.jsonPrimitive?.content != id }
            require(remaining.size != messages.size)
            JsonObject(snap.conversation + ("messages" to JsonArray(remaining)))
        }
    }
    private fun expectedRevision(id: String): Long? = mutable.value.let { state ->
        if (state.snapshot?.sessionId == id) state.snapshot.revision else state.revisions[id]
    }
    private fun change(id: String, transform: (ServerSnapshot) -> JsonObject) {
        val api = sessions ?: return; val epoch = generation; val expected = expectedRevision(id) ?: return
        val owner = activeCacheScope
        scope.launch { try { val snap = api.open(id, cached(id, owner)); check(snap.revision == expected)
            val edited = transform(snap)
            val previous = snap.conversation["updated_at"]?.jsonPrimitive?.long ?: 0
            val changed = JsonObject(edited + ("updated_at" to JsonPrimitive(maxOf(previous, System.currentTimeMillis()))))
            val updated = api.save(id, expected, UUID.randomUUID().toString(), changed); val list = api.list().filter { it.deletedAt == null }
            if (epoch == generation) {
                mutable.update { it.copy(items = list.map { row -> row.ui() }, revisions = list.associate { row -> row.id to row.revision }, snapshot = if (it.snapshot?.sessionId == id) updated else it.snapshot, conversation = if (it.snapshot?.sessionId == id) updated.ui() else it.conversation) }
                store(updated, owner)
                storeList(list, owner)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { if (epoch == generation) mutable.update { it.copy(error = true) } } }
    }
    fun delete(id: String) {
        val api = sessions ?: return; val epoch = generation; val expected = expectedRevision(id) ?: return
        val owner = activeCacheScope
        scope.launch { try { api.delete(id, expected, UUID.randomUUID().toString()); val list = api.list().filter { it.deletedAt == null }
            if (epoch == generation) {
                eventCursors.remove(id)
                mutable.update { if (it.snapshot?.sessionId == id) it.copy(items = list.map { row -> row.ui() }, revisions = list.associate { row -> row.id to row.revision }, snapshot = null, conversation = null) else it.copy(items = list.map { row -> row.ui() }, revisions = list.associate { row -> row.id to row.revision }) }
                if (owner != null && cache != null) cacheOrNull { cache.remove(owner, id) }
                storeList(list, owner)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { if (epoch == generation) mutable.update { it.copy(error = true) } } }
    }
    private class FinishedStream : Exception()
}

private fun SessionListItem.ui() = ConversationListItem(id, title, createdAt, updatedAt, tokenCount?.toInt(), providerId, modelId, profileId, reasoningEffort, hasMessages, hasSummary)
private fun ServerSnapshot.ui(delta: String = ""): Conversation {
    val conversation = ServerSideCodec.json.decodeFromString<Conversation>(conversation.toString())
    if (delta.isEmpty() || conversation.messages.isEmpty()) return conversation.copy(structuralRevision = revision)
    val index = conversation.messages.indexOfLast { it.role == org.starfall.generative.shared.model.ChatRole.MODEL }
    if (index < 0) return conversation
    val messages = conversation.messages.toMutableList(); val message = messages[index]
    val versions = message.versions.toMutableList(); if (versions.isEmpty()) return conversation
    val active = message.activeVersionIndex.coerceIn(0, versions.lastIndex)
    versions[active] = versions[active].copy(content = versions[active].content + delta)
    messages[index] = message.copy(versions = versions)
    return conversation.copy(messages = messages, structuralRevision = revision)
}
