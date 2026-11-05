package org.starfall.generative.shared.state

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned RAM writer, with two distinct storage policies:
 * - Snapshot loader: Room is passive and never invalidates an active UI.
 * - Reactive source: DataStore preferences continue to observe external changes.
 * Every accepted write is visible immediately and persists in submission order.
 */
internal class ImmediateState<T : Any> private constructor(
    private val load: suspend () -> T,
    private val updates: Flow<T>?,
    scope: CoroutineScope,
) {
    constructor(
        load: suspend () -> T,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + LocalWriteErrors.handler)
    ) : this(load, null, scope)

    /** Retain DataStore's reactive preference behavior. Never use this overload for Room. */
    constructor(
        source: Flow<T>,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + LocalWriteErrors.handler)
    ) : this({ source.first() }, source, scope)

    private class Write<T>(val transform: (T) -> T, val persist: suspend () -> Unit, val reportFailure: Boolean) {
        val result = CompletableDeferred<Unit>()
    }
    private val guard = Any()
    private val disk = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val pending = mutableListOf<Write<T>>()
    private var committed: T? = null
    private val visible = MutableStateFlow<T?>(null)
    val flow: Flow<T> = visible.filterNotNull().distinctUntilChanged()
    val value: T? get() = visible.value
    private val writes = Channel<Write<T>>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                if (updates == null) {
                    disk.withLock {
                        val initial = load()
                        synchronized(guard) {
                            committed = initial
                            publish()
                        }
                        ready.complete(Unit)
                    }
                } else {
                    var initialEmission = true
                    updates.collect { emitted ->
                        disk.withLock {
                            // Only preferences observe storage. Re-read under the writer lock
                            // so a delayed invalidation cannot overwrite a newer preference.
                            val latest = if (initialEmission) {
                                initialEmission = false
                                emitted
                            } else updates.first()
                            synchronized(guard) {
                                committed = latest
                                publish()
                            }
                            ready.complete(Unit)
                        }
                    }
                }
            } catch (e: Throwable) {
                ready.completeExceptionally(e)
                throw e
            }
        }
        scope.launch {
            for (write in writes) {
                val result = runCatching {
                    ready.await()
                    disk.withLock {
                        write.persist()
                        // Room never re-reads on persistence completion. DataStore still
                        // reads the authoritative preference value after its edit.
                        updates?.first()
                    }
                }
                synchronized(guard) {
                    if (result.isSuccess) committed = result.getOrNull()
                        ?: write.transform(checkNotNull(committed))
                    pending.remove(write)
                    publish()
                }
                result.fold(
                    onSuccess = { write.result.complete(Unit) },
                    onFailure = {
                        write.result.completeExceptionally(it)
                        if (write.reportFailure) LocalWriteErrors.report()
                    }
                )
            }
        }
    }

    private fun publish() {
        committed?.let { base -> visible.value = pending.fold(base) { value, write -> write.transform(value) } }
    }

    /** Accept a UI action synchronously; the storage writer never blocks the caller. */
    fun enqueue(transform: (T) -> T, persist: suspend () -> Unit): Deferred<Unit> {
        val write = Write(transform, persist, reportFailure = true)
        submit(write)
        return write.result
    }

    suspend fun mutate(transform: (T) -> T, persist: suspend () -> Unit) {
        val write = Write(transform, persist, reportFailure = false)
        submit(write)
        // Cancellation of a caller never cancels a write already accepted by the repository.
        write.result.await()
    }

    private fun submit(write: Write<T>) {
        synchronized(guard) {
            pending.add(write)
            publish()
            check(writes.trySend(write).isSuccess)
        }
    }
}

object LocalWriteErrors {
    private val events = Channel<Unit>(Channel.CONFLATED)
    val errors = events.receiveAsFlow()
    fun report() { events.trySend(Unit) }
    val handler = CoroutineExceptionHandler { _, _ -> report() }
}

internal fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(item) }) map { if (id(it) == id(item)) item else it } else this + item
