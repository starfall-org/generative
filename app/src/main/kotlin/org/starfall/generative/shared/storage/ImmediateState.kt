package org.starfall.generative.shared.storage

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-lifetime writer. UI mutations are visible before persistence starts.
 * Pending transforms stay over the committed snapshot until read-back, including while
 * Room emits older invalidations. Failed writes remove only their own transform.
 */
internal class ImmediateState<T : Any>(
    private val source: Flow<T>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + LocalWriteErrors.handler),
) {
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
                var firstEmission = true
                source.collect { emitted ->
                    disk.withLock {
                        // First emission is already the committed snapshot (writes wait on `ready`), so skip
                        // the second full decrypt/decode. Later emissions re-read under the writer lock: they may be stale.
                        val latest = if (firstEmission) { firstEmission = false; emitted } else source.first()
                        synchronized(guard) { committed = latest; publish() }
                        ready.complete(Unit)
                    }
                }
            } catch (e: Throwable) {
                ready.completeExceptionally(e)
                throw e
            }
        }
        scope.launch {
            for (write in writes) {
                val initialized = runCatching { ready.await() }
                disk.withLock {
                    val result = runCatching {
                        initialized.getOrThrow()
                        write.persist()
                        source.first()
                    }
                    synchronized(guard) {
                        result.getOrNull()?.let { committed = it }
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
    }

    private fun publish() {
        committed?.let { base -> visible.value = pending.fold(base) { value, write -> write.transform(value) } }
    }

    /** Use from a drop callback: publish to RAM synchronously, never await Room on the UI path. */
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
