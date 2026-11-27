package org.starfall.generative.features.home.chat.logic.remote

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.starfall.generative.features.home.chat.model.RemoteActionFailure
import org.starfall.generative.features.home.chat.model.RemoteConnectionFailure
import org.starfall.generative.features.home.chat.model.RemoteWorkspaceState
import org.starfall.generative.integrations.opencode.OpenCodeApi
import org.starfall.generative.integrations.generativeserver.RemoteCacheScope
import org.starfall.generative.integrations.generativeserver.RemoteConversationCache
import org.starfall.generative.integrations.generativeserver.*
import org.starfall.generative.shared.model.provider.ModelProvider

class RemoteWorkspace(private val sessionFactory: suspend (String) -> GenerativeServerSessions, private val scope: CoroutineScope,
    private val loadLastSession: suspend (String) -> String? = { null },
    private val saveLastSession: suspend (String, String?) -> Unit = { _, _ -> },
    private val loadActivation: (suspend () -> String?)? = null,
    private val saveActivation: suspend (String?) -> Unit = {},
    private val cache: RemoteConversationCache? = null,
    private val cacheScope: suspend (String) -> RemoteCacheScope? = { null },
    private val resolveLocalProvider: suspend (ModelProvider) -> ModelProvider = { it },
    private val openCodeFactory: suspend (String) -> OpenCodeApi? = { null }) {
    private val mutable = MutableStateFlow(RemoteWorkspaceState(restoring = loadActivation != null))
    val state: StateFlow<RemoteWorkspaceState> = mutable.asStateFlow()
    private var generation = 0L
    private var sessions: GenerativeServerSessions? = null
    private var stream: Job? = null
    private var loadingConversation: Job? = null
    private var openingConnection: Job? = null
    private var openCode: OpenCodeWorkspace? = null
    private var requestId: String? = null
    private var serverProviders = emptyList<ModelProvider>()
    private var localProviders = emptyList<ModelProvider>()
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
    /** Local metadata is scoped to the active connection and never includes resolved credentials. */
    fun setLocalProviders(connectionId: String, providers: List<ModelProvider>) {
        if (mutable.value.connectionId != connectionId || mutable.value.openCode) return
        localProviders = providers
        mutable.update { state ->
            val (combined, localIds) = combinedProviders(serverProviders, localProviders, state.capabilities.mobileProviders, state.capabilities.providerOptions)
            val valid = combined.any { it.id == state.providerId && it.config.modelIds?.contains(state.modelId) == true }
            val selection = if (valid) state.providerId to state.modelId
                else state.snapshot?.let { selectionFor(it, combined) } ?: ("" to "")
            state.copy(providers = combined, localProviderIds = localIds,
                providerId = selection.first, modelId = selection.second,
                keyId = state.keyId.takeIf { valid })
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

    private fun resetConnection() {
        generation++
        loadingConversation?.cancel()
        loadingConversation = null
        openingConnection?.cancel()
        openingConnection = null
        openCode?.close()
        openCode = null
        stream?.cancel()
        stream = null
        sessions?.close()
        sessions = null
        requestId = null
        eventCursors.clear()
        activeCacheScope = null
        providerKeys = emptyMap()
        pendingSelection = null
        serverProviders = emptyList()
        localProviders = emptyList()
    }

    fun disable() {
        resetConnection()
        mutable.value = RemoteWorkspaceState()
        persistActivation(null, generation)
    }
    fun profileRemoved() {
        resetConnection()
        mutable.value = RemoteWorkspaceState(enabled = true, error = true)
    }
    fun enable(id: String) {
        resetConnection()
        val epoch = generation
        activationError = false
        mutable.value = RemoteWorkspaceState(enabled = true, connectionId = id, busy = true, connecting = true)
        val activation = persistActivation(id, epoch)
        openingConnection = scope.launch {
            var opening: GenerativeServerSessions? = null
            var verified = false
            try {
                activation.join()
                if (epoch != generation) return@launch
                if (activationError) { mutable.update { it.copy(busy = false, connecting = false, error = true) }; return@launch }
                val openCodeApi = openCodeFactory(id)
                if (epoch != generation) return@launch
                if (openCodeApi != null) {
                    val workspace = OpenCodeWorkspace(id, scope, mutable, loadLastSession, saveLastSession)
                    openCode = workspace
                    mutable.update { it.copy(openCode = true) }
                    workspace.connect(openCodeApi)
                    return@launch
                }
                // Private, encrypted cache provides an immediate read-only view while
                // the server is checked; the server list/revision is authoritative.
                val owner = cacheOrNull { cacheScope(id) }
                val remembered = if (lastSession.containsKey(id)) lastSession[id] else cacheOrNull { loadLastSession(id) }
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
                opening = api
                if (epoch != generation) return@launch
                api.withConnection {
                    val capabilities = api.verifyCapabilities()
                    verified = true
                    val catalog = api.catalog()
                    val providers = catalog.textProviders()
                    val list = api.list().filter { it.deletedAt == null }
                    val prior = remembered?.takeIf { previous ->
                        mutable.value.loadingConversationId == null && list.any { it.id == previous }
                    }
                    val hint = prior?.let { previous ->
                        (initial?.takeIf { it.sessionId == previous } ?: cached(previous, owner))
                            ?.takeIf { item -> list.any { it.id == previous && it.revision == item.revision } }
                    }
                    val opened = prior?.let {
                        try {
                            api.open(it, hint).also { snapshot -> snapshot.ui() }
                        } catch (failure: Exception) {
                            if (!isConversationLoadFailure(failure)) throw failure
                            null
                        }
                    }
                    if (epoch != generation) return@withConnection
                    // Complete the cache checkpoint before declaring activation ready,
                    // so an immediate app close/reconnect cannot lose the fetched chat.
                    storeList(list, owner)
                    if (opened != null) store(opened, owner)
                    if (epoch != generation) return@withConnection
                    sessions = api
                    providerKeys = catalog.providers.filter { it.keySelectionRequired }.associate { p -> p.id to p.keys.filter { it.availability == "available" } }
                    serverProviders = providers
                    val (combined, localIds) = combinedProviders(serverProviders, localProviders, capabilities.mobileProviders, capabilities.providerOptions)
                    val diagnostics = catalog.modelDiagnostics()
                    val defaultSelection = providers.asSequence().filter { it.id !in providerKeys }
                        .flatMap { provider -> provider.config.modelIds.orEmpty().asSequence().map { provider.id to it } }
                        .firstOrNull { it !in diagnostics }
                    // A tap on a cached drawer row takes precedence over automatic restoration.
                    val requested = mutable.value.loadingConversationId
                    val revisions = list.associate { it.id to it.revision }
                    val next = requested?.takeIf { it != prior && it in revisions }
                    val target = requested ?: prior
                    val visible = opened?.takeIf { it.sessionId == target } ?: mutable.value.snapshot?.takeIf {
                        next != null && it.sessionId == next && it.revision == revisions[next]
                    }
                    val selected = if (visible != null) selectionFor(visible, combined)
                        else defaultSelection ?: ("" to "")
                    val missing = target != null && visible == null && next == null
                    mutable.value = RemoteWorkspaceState(enabled = true, connectionId = id, items = list.map { it.ui() }, revisions = revisions, providers = combined, localProviderIds = localIds,
                        providerId = selected.first, modelId = selected.second, snapshot = visible,
                        conversation = visible?.ui(), busy = next != null, capabilities = capabilities, catalogDiagnostics = diagnostics,
                        actionFailure = RemoteActionFailure.CONVERSATION_LOAD_FAILED.takeIf { missing })
                    if (next != null) select(next)
                }
            } catch (e: CancellationException) { throw e }
            catch (failure: Exception) {
                val reason = remoteConnectionFailure(failure)
                if (epoch != generation) return@launch
                val requested = mutable.value.loadingConversationId
                mutable.update { it.copy(busy = false, connecting = false, loadingConversationId = null, error = true,
                    connectionFailure = if (verified && reason == RemoteConnectionFailure.UNAVAILABLE &&
                        failure !is IOException) {
                        RemoteConnectionFailure.CHAT_LOAD_FAILED
                    } else reason) }
                if (requested != null) select(requested)
            }
            finally { if (sessions !== opening) opening?.close() }
        }
    }
    fun selectModel(provider: String, model: String) {
        openCode?.let { return it.selectModel(provider, model) }
        val current = mutable.value
        if (current.busy || current.running || !current.providers.any { it.id == provider && it.config.modelIds?.contains(model) == true }) return
        // A rejected catalog model is selectable for inspection but never sent to the server.
        if (current.catalogDiagnostics.containsKey(provider to model)) {
            applyModelSelection(provider, model, null)
            return
        }
        val keys = providerKeys[provider]
        if (keys != null) {
            if (keys.isEmpty()) {
                mutable.update { it.copy(actionFailure = RemoteActionFailure.OPERATION_FAILED,
                    providerDiagnostic = "auth_required: provider.keyId") }
                return
            }
            pendingSelection = provider to model
            mutable.update { it.copy(pendingKeys = keys) }
        } else applyModelSelection(provider, model, null)
    }
    private fun applyModelSelection(provider: String, model: String, key: String?) {
        pendingSelection = null
        val current = mutable.value
        mutable.update { it.copy(providerId = provider, modelId = model, keyId = key, pendingKeys = emptyList(), actionFailure = null, providerDiagnostic = null) }
        if (current.snapshot != null && (current.providerId != provider || current.modelId != model)) {
            change(current.snapshot.sessionId) { snap -> JsonObject(snap.conversation +
                ("provider_id" to JsonPrimitive(provider)) + ("model_id" to JsonPrimitive(model))) }
        }
    }
    fun selectKey(id: String) {
        val selection = pendingSelection ?: return
        if (mutable.value.pendingKeys.none { it.id == id }) return
        applyModelSelection(selection.first, selection.second, id)
    }
    fun dismissKeySelection() { pendingSelection = null; mutable.update { it.copy(pendingKeys = emptyList()) } }
    fun select(id: String) {
        openCode?.let { return it.select(id) }
        val connecting = mutable.value.connecting
        val connection = mutable.value.connectionId ?: return
        val owner = activeCacheScope
        val api = sessions
        if (api == null && owner == null) return
        val expected = mutable.value.revisions[id] ?: return
        if (mutable.value.loadingConversationId == id) return
        loadingConversation?.cancel()
        // Browsing the cache during a handshake must keep the activation job alive.
        val epoch = if (connecting) generation else ++generation
        stream?.cancel(); requestId = null
        val current = mutable.value.snapshot?.takeIf { it.sessionId == id && it.revision == expected }
        mutable.update { it.copy(snapshot = current, conversation = it.conversation.takeIf { current != null },
            loadingConversationId = id, busy = true, running = false, error = !connecting && api == null) }
        loadingConversation = scope.launch { try {
            val hint = current ?: cached(id, owner)?.takeIf { it.revision == expected }
            currentCoroutineContext().ensureActive()
            if (epoch != generation || mutable.value.loadingConversationId != id) return@launch
            if (connecting) {
                // A malformed cache entry can still be recovered from the server.
                if (hint != null) cacheOrNull { mutable.update { setSnapshot(it, hint) } }
                return@launch
            }
            if (hint != null) mutable.update { setSnapshot(it, hint) }
            if (api == null) {
                // Offline browsing stays read-only, but remembers the last viewed chat.
                lastSession[connection] = id
                cacheOrNull { saveLastSession(connection, id) }
                if (epoch == generation) mutable.update { it.copy(loadingConversationId = null, busy = false, error = true) }
                return@launch
            }
            val snap = api.withConnection { api.open(id, hint) }
            if (epoch != generation) return@launch
            lastSession[connection] = id
            cacheOrNull { saveLastSession(connection, id) }
            if (epoch != generation) return@launch
            store(snap, owner)
            if (epoch == generation) mutable.update { setSnapshot(it, snap).copy(loadingConversationId = null, busy = false, error = false, actionFailure = null, providerDiagnostic = null, connectionFailure = null) }
        } catch (e: CancellationException) { throw e }
        catch (failure: Exception) {
            val conversationFailed = api != null && isConversationLoadFailure(failure)
            if (epoch == generation) mutable.update { it.copy(loadingConversationId = null, busy = false, error = !conversationFailed,
                connectionFailure = if (conversationFailed) null else remoteConnectionFailure(failure),
                actionFailure = if (conversationFailed) RemoteActionFailure.CONVERSATION_LOAD_FAILED else null) }
        } }
    }
    fun newChat() {
        openCode?.let { return it.newChat() }
        if (sessions == null && mutable.value.busy) return
        loadingConversation?.cancel()
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
        mutable.update { it.copy(snapshot = null, conversation = null, running = false, loadingConversationId = null, busy = false, error = sessions == null, actionFailure = null, providerDiagnostic = null) }
    }
    suspend fun send(text: String, files: List<String>): Boolean {
        openCode?.let { return it.send(text, files) }
        val current = mutable.value; val api = sessions ?: return false
        if (!current.canSubmit || text.isBlank() || files.isNotEmpty() ||
            current.providers.none { it.id == current.providerId && it.config.modelIds?.contains(current.modelId) == true }) return false
        if (providerKeys.containsKey(current.providerId) && current.keyId == null) {
            selectModel(current.providerId, current.modelId)
            return false
        }
        if (text.toByteArray(Charsets.UTF_8).size > current.capabilities.maxRunContentBytes) return false
        // The server currently runs only whole snapshots. Keep the draft if the
        // existing history and new turn cannot safely fit that bounded envelope.
        val size = current.snapshot?.conversation?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0
        if (size.toLong() + text.toByteArray(Charsets.UTF_8).size + 16 * 1024 > current.capabilities.maxConversationBytes) return false
        val epoch = generation
        val owner = activeCacheScope
        val runtime = if (current.providerId in current.localProviderIds) {
            val provider = localProviders.firstOrNull { "local:" + it.id == current.providerId } ?: return false
            fun selectionIsCurrent(): Boolean {
                val latest = mutable.value
                return epoch == generation && !latest.busy && !latest.running && !latest.error &&
                    latest.providerId == current.providerId && latest.modelId == current.modelId &&
                    current.providerId in latest.localProviderIds && localProviders.any { it == provider }
            }
            try {
                val resolved = resolveLocalProvider(provider)
                currentCoroutineContext().ensureActive()
                if (!selectionIsCurrent()) return false
                mobileProviderRuntime(resolved, current.providerId, current.modelId, current.capabilities.providerOptions)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (selectionIsCurrent()) {
                    mutable.update { it.copy(actionFailure = localProviderFailure(failure),
                        providerDiagnostic = (failure as? MobileProviderConfigurationException)?.diagnostic) }
                }
                return false
            }
        } else null
        val id = current.snapshot?.sessionId ?: UUID.randomUUID().toString()
        val request = UUID.randomUUID().toString()
        requestId = request
        mutable.update { it.copy(busy = true, error = false, actionFailure = null, providerDiagnostic = null) }
        stream = launchConnected(api) {
            try {
                // Reopen to obtain the server's *current* in-memory event cursor.
                // A retained cursor may refer to an expired/restarted session.
                val snap = api.open(id, current.snapshot)
                val startingCursor = snap.eventSeq ?: eventCursors[id] ?: 0L
                if (epoch != generation) return@launchConnected
                mutable.update { publishRunSnapshot(it, snap) }
                if (runtime != null) {
                    if (current.providerId !in mutable.value.localProviderIds) {
                        mutable.update { it.copy(busy = false, actionFailure = RemoteActionFailure.OPERATION_FAILED) }
                        return@launchConnected
                    }
                    api.configureRuntime(id, runtime)
                } else api.configure(id, current.providerId, current.modelId, current.keyId)
                if (epoch != generation) return@launchConnected
                lastSession[current.connectionId!!] = id
                cacheOrNull { saveLastSession(current.connectionId, id) }
                if (epoch != generation) return@launchConnected
                mutable.update { it.copy(running = true, busy = false) }
                // A failed run acknowledgement is ambiguous and is never replayed.
                api.run(id, request, text)
                api.events(id, startingCursor).collect { event ->
                    if (epoch != generation) return@collect
                    // Advance on every event, including those from another request. The
                    // server expires old events, so restarting at zero would create a gap.
                    eventCursors[id] = event.seq
                    if (event.requestId != request) return@collect
                    when (event.kind) {
                        "delta", "reasoning_delta", "tool_activity" -> {
                            val data = event.data as? JsonObject ?: return@collect
                            mutable.update { state ->
                                state.copy(conversation = state.conversation?.applyRunEvent(event.kind, data))
                            }
                        }
                        "conversation" -> {
                            val data = event.data?.jsonObject ?: return@collect
                            val revision = data.getValue("revision").jsonPrimitive.long
                            val conversation = data.getValue("conversation").jsonObject
                            if (revision <= 0 || conversation["id"]?.jsonPrimitive?.content != id) {
                                throw GenerativeServerProtocolException("Invalid conversation event")
                            }
                            if (revision > (mutable.value.snapshot?.revision ?: 0)) {
                                val update = ServerSnapshot(id, revision, conversation)
                                mutable.update { publishRunSnapshot(it, update) }
                            }
                        }
                        "completed", "error" -> {
                            val updated = api.get(id, mutable.value.snapshot?.takeIf { it.sessionId == id })
                            val list = cacheOrNull { api.list().filter { it.deletedAt == null } }
                            if (epoch == generation) {
                                mutable.update { current ->
                                    val refreshed = if (list == null) current else current.copy(
                                        items = list.map { row -> row.ui() },
                                        revisions = list.associate { row -> row.id to row.revision }
                                    )
                                    publishRunSnapshot(refreshed, updated).copy(
                                        running = false, error = false,
                                        actionFailure = RemoteActionFailure.GENERATION_FAILED.takeIf { event.kind == "error" },
                                        providerDiagnostic = if (event.kind == "error") (event.data as? JsonObject)?.let { data ->
                                            val code = (data["code"] as? JsonPrimitive)?.content ?: return@let null
                                            RpcError(code, "", field = (data["field"] as? JsonPrimitive)?.content).diagnostic
                                        } else null
                                    )
                                }
                                store(updated, owner)
                                if (list != null && list.any { it.id == id }) storeList(list, owner)
                            }
                            throw FinishedStream()
                        }
                    }
                }
            } catch (_: FinishedStream) { }
            catch (e: CancellationException) { throw e }
            catch (failure: Exception) {
                // Reload once for authoritative state, without resending the prompt.
                val recovered = cacheOrNull { api.get(id, mutable.value.snapshot?.takeIf { it.sessionId == id }) }
                if (epoch == generation) {
                    mutable.update {
                        val refreshed = if (recovered == null) it else publishRunSnapshot(it, recovered)
                        val providerFailure = remoteProviderFailure(failure)
                        val diagnostic = (failure as? GenerativeServerRpcException)?.rpcError?.diagnostic
                        refreshed.copy(busy = false, running = false, error = providerFailure == null,
                            actionFailure = providerFailure?.takeIf { runtime != null } ?: RemoteActionFailure.GENERATION_FAILED,
                            providerDiagnostic = diagnostic)
                    }
                    if (recovered != null) store(recovered, owner)
                }
            } finally { if (epoch == generation) requestId = null }
        }
        return true
    }
    fun stop() {
        openCode?.let { return it.stop() }
        val api = sessions ?: return; val id = mutable.value.snapshot?.sessionId ?: return; val request = requestId ?: return
        scope.launch { runCatching { api.cancel(id, request) } }
    }
    fun rename(id: String, title: String) {
        openCode?.let { return it.rename(id, title) }
        change(id) { snap -> JsonObject(snap.conversation + ("title" to JsonPrimitive(title))) }
    }
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
        val api = sessions ?: return
        if (mutable.value.busy || mutable.value.running) return
        val epoch = generation; val expected = expectedRevision(id) ?: return
        val owner = activeCacheScope
        mutable.update { it.copy(busy = true, actionFailure = null, providerDiagnostic = null) }
        launchConnected(api) {
            try {
                val snap = api.open(id, cached(id, owner))
                if (snap.revision != expected) throw GenerativeServerRpcException(RpcError("revision_conflict", "Conversation changed on the server"))
                val edited = transform(snap)
                val previous = snap.conversation["updated_at"]?.jsonPrimitive?.long ?: 0
                val changed = JsonObject(edited + ("updated_at" to JsonPrimitive(maxOf(previous, System.currentTimeMillis()))))
                val updated = api.save(id, expected, UUID.randomUUID().toString(), changed)
                val list = api.list().filter { it.deletedAt == null }
                if (epoch == generation) {
                    mutable.update { state ->
                        val next = state.copy(items = list.map { it.ui() }, revisions = list.associate { it.id to it.revision }, busy = false)
                        if (state.snapshot?.sessionId == id) setSnapshot(next, updated) else next
                    }
                    store(updated, owner); storeList(list, owner)
                }
            } catch (e: CancellationException) { throw e }
            catch (failure: Exception) {
                // A CAS conflict is recoverable; never replay an ambiguous write.
                val refreshed = runCatching { api.get(id) }.getOrNull()
                if (epoch == generation) {
                    mutable.update { state ->
                        val next = state.copy(busy = false, actionFailure = if (failure is GenerativeServerRpcException && failure.rpcError.code == "revision_conflict")
                            RemoteActionFailure.REVISION_CONFLICT else RemoteActionFailure.OPERATION_FAILED)
                        if (refreshed != null && state.snapshot?.sessionId == id) setSnapshot(next, refreshed) else next
                    }
                    if (refreshed != null) store(refreshed, owner)
                }
            }
        }
    }
    fun delete(id: String) {
        openCode?.let { return it.delete(id) }
        val api = sessions ?: return; val epoch = generation; val expected = expectedRevision(id) ?: return
        val owner = activeCacheScope
        launchConnected(api) {
            try {
                api.delete(id, expected, UUID.randomUUID().toString())
                val list = api.list().filter { it.deletedAt == null }
                if (epoch == generation) {
                    eventCursors.remove(id)
                    mutable.update { state ->
                        val updated = state.copy(
                            items = list.map { it.ui() },
                            revisions = list.associate { it.id to it.revision },
                        )
                        if (state.snapshot?.sessionId == id) updated.copy(snapshot = null, conversation = null) else updated
                    }
                    if (owner != null && cache != null) cacheOrNull { cache.remove(owner, id) }
                    storeList(list, owner)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (epoch == generation) mutable.update { it.copy(actionFailure = RemoteActionFailure.OPERATION_FAILED) }
            }
        }
    }
    private fun launchConnected(api: GenerativeServerSessions, block: suspend () -> Unit): Job =
        scope.launch { api.withConnection(block) }

    private class FinishedStream : Exception()
}
