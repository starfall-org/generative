package org.starfall.generative

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.generative.shared.storage.ImmediateState

/** Repository reorder callbacks must not suspend for storage or stale invalidations. */
class ImmediateDropQueueTest {
    @Test fun successiveDropsRenderImmediatelyEvenWithSlowPersistence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disk = MutableStateFlow(listOf("a", "b", "c"))
            val state = ImmediateState({ disk.value }, scope)
            state.flow.first()
            val allowWrite = CompletableDeferred<Unit>()
            state.enqueue({ listOf("b", "a", "c") }) {
                allowWrite.await()
                disk.value = listOf("b", "a", "c")
            }
            assertEquals(listOf("b", "a", "c"), state.value)
            state.enqueue({ listOf("c", "b", "a") }) {
                disk.value = listOf("c", "b", "a")
            }
            assertEquals(listOf("c", "b", "a"), state.value)
            assertEquals(listOf("a", "b", "c"), disk.value)

            allowWrite.complete(Unit)
            withTimeout(5_000) { disk.first { it == listOf("c", "b", "a") } }
            assertEquals(disk.value, state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test fun failedDropDoesNotRollBackANewerSuccessfulDrop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disk = MutableStateFlow(listOf("a", "b"))
            val state = ImmediateState({ disk.value }, scope)
            state.flow.first()
            val allowFailure = CompletableDeferred<Unit>()
            state.enqueue({ listOf("b", "a") }) {
                allowFailure.await()
                throw IOException("simulated disk failure")
            }
            state.enqueue({ listOf("a", "b", "c") }) {
                disk.value = listOf("a", "b", "c")
            }
            assertEquals(listOf("a", "b", "c"), state.value)

            allowFailure.complete(Unit)
            withTimeout(5_000) { disk.first { it == listOf("a", "b", "c") } }
            assertEquals(disk.value, state.value)
        } finally {
            scope.cancel()
        }
    }
}
