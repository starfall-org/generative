package org.starfall.generative

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.storage.ImmediateState

class ImmediateStateTest {
    @Test fun reactivePreferencesStillObserveExternalChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val preferences = MutableStateFlow("light")
            val state = ImmediateState(preferences, scope)
            assertEquals("light", state.flow.first())
            preferences.value = "dark"
            assertEquals("dark", withTimeout(3_000) { state.flow.first { it == "dark" } })
        } finally { scope.cancel() }
    }

    @Test fun queuedEditsAreVisibleBeforeDiskAndNeverRebound() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disk = MutableStateFlow(listOf("a", "b", "c"))
            val state = ImmediateState({ disk.value }, scope)
            assertEquals(disk.value, state.flow.first())
            val gate = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                state.mutate({ listOf("c", "a", "b") }) {
                    gate.await()
                    disk.value = listOf("c", "a", "b")
                }
            }
            assertEquals(listOf("c", "a", "b"), state.value)
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                state.mutate({ it.filterNot { id -> id == "a" } }) {
                    disk.value = disk.value.filterNot { it == "a" }
                }
            }
            assertEquals(listOf("c", "b"), state.value)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            gate.complete(Unit)
            withTimeout(5000) { first.await(); second.await() }
            assertEquals(listOf("c", "b"), state.value)
            assertEquals(state.value, disk.value)
        } finally { scope.cancel() }
    }

    @Test fun failedWriteRollsBackOnlyItsChangeAndWriterContinues() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disk = MutableStateFlow(mapOf("theme" to "light", "layout" to "list"))
            val state = ImmediateState({ disk.value }, scope)
            state.flow.first()
            val gate = CompletableDeferred<Unit>()
            val failure = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { state.mutate({ it + ("theme" to "dark") }) {
                    gate.await()
                    throw IOException("disk full")
                } }
            }
            val success = async(start = CoroutineStart.UNDISPATCHED) {
                state.mutate({ it + ("layout" to "grid") }) { disk.value += "layout" to "grid" }
            }
            assertEquals(mapOf("theme" to "dark", "layout" to "grid"), state.value)
            gate.complete(Unit)
            withTimeout(5000) { assertTrue(failure.await().isFailure); success.await() }
            assertEquals(mapOf("theme" to "light", "layout" to "grid"), state.value)
        } finally { scope.cancel() }
    }

    @Test fun leavingScreenDoesNotCancelAcceptedWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disk = MutableStateFlow(0)
            val state = ImmediateState({ disk.value }, scope)
            state.flow.first()
            val gate = CompletableDeferred<Unit>()
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                state.mutate({ 1 }) { gate.await(); disk.value = 1 }
            }
            caller.cancelAndJoin()
            gate.complete(Unit)
            withTimeout(5000) { disk.first { it == 1 } }
            assertEquals(1, state.value)
        } finally { scope.cancel() }
    }

    @Test fun staleInvalidationCannotRestoreOldState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disk = MutableStateFlow(0)
            val invalidations = MutableSharedFlow<Int>(extraBufferCapacity = 4)
            val source = flow { emit(disk.value); emitAll(invalidations) }
            val state = ImmediateState({ source.first() }, scope)
            state.flow.first()
            state.mutate({ 1 }) { disk.value = 1 }
            invalidations.emit(0)
            // A following write crosses the same disk lock as the stale invalidation.
            state.mutate({ it + 1 }) { disk.value += 1 }
            assertEquals(2, state.value)
        } finally { scope.cancel() }
    }

    @Test fun changesToStorageNeverPublishToActiveConsumers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disk = MutableStateFlow(listOf("first", "second"))
            var snapshotReads = 0
            val state = ImmediateState({
                snapshotReads++
                disk.value
            }, scope)
            assertEquals(listOf("first", "second"), state.flow.first())
            disk.value = listOf("second", "first")
            delay(100)
            assertEquals("A passive storage change must not reorder an active view",
                listOf("first", "second"), state.value)
            state.mutate({ listOf("first", "second", "third") }) {
                disk.value = listOf("first", "second", "third")
            }
            assertEquals(listOf("first", "second", "third"), state.value)
            assertEquals("Only the bootstrap may read storage", 1, snapshotReads)
        } finally { scope.cancel() }
    }

    @Test fun enqueuedReordersPublishImmediatelyAndPersistInSubmissionOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val disk = MutableStateFlow(listOf("a", "b", "c"))
            val state = ImmediateState({ disk.value }, scope)
            state.flow.first()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            state.enqueue({ listOf("c", "a", "b") }) {
                entered.complete(Unit)
                release.await()
                disk.value = listOf("c", "a", "b")
            }
            withTimeout(3_000) { entered.await() }
            state.enqueue({ listOf("b", "c", "a") }) { disk.value = listOf("b", "c", "a") }
            assertEquals(listOf("b", "c", "a"), state.value)
            assertEquals(listOf("a", "b", "c"), disk.value)
            release.complete(Unit)
            withTimeout(3_000) { disk.first { it == listOf("b", "c", "a") } }
            assertEquals(disk.value, state.value)
        } finally { scope.cancel() }
    }

    @Test fun enqueuedWriteFailureRollsBackAndNextReorderCanSucceed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val disk = MutableStateFlow(listOf("a", "b"))
            val state = ImmediateState({ disk.value }, scope)
            state.flow.first()
            state.enqueue({ it.reversed() }) { throw IOException("no space") }
            withTimeout(3_000) { state.flow.first { it == listOf("a", "b") } }
            state.enqueue({ listOf("b", "a") }) { disk.value = listOf("b", "a") }
            withTimeout(3_000) { disk.first { it == listOf("b", "a") } }
            assertEquals(disk.value, state.value)
        } finally { scope.cancel() }
    }

    @Test fun editsBeforeInitialLoadApplyToLoadedData() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = CompletableDeferred<Unit>()
            val disk = MutableStateFlow(listOf("existing"))
            val source = flow { gate.await(); emitAll(disk) }
            val state = ImmediateState({ source.first() }, scope)
            val write = async(start = CoroutineStart.UNDISPATCHED) {
                state.mutate({ it + "new" }) { disk.value += "new" }
            }
            gate.complete(Unit)
            withTimeout(5000) { write.await() }
            assertEquals(listOf("existing", "new"), state.value)
        } finally { scope.cancel() }
    }
}
