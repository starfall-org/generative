package org.starfall.generative

import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.generative.integrations.remote.ActivePortForwards

class ActivePortForwardsTest {
    @Test fun closingTwiceStopsListenersExactlyOnceInReverseOrder() {
        val stopped = mutableListOf<Int>()
        val active = ActivePortForwards(listOf("L 8080→8080", "SOCKS 1080"), listOf(
            { stopped += 1 },
            { stopped += 2 }
        ))

        assertEquals(listOf("L 8080→8080", "SOCKS 1080"), active.labels)
        active.close()
        active.close()
        assertEquals(listOf(2, 1), stopped)
    }

    @Test fun failedStopDoesNotPreventOtherListenersBeingClosed() {
        var closed = false
        val active = ActivePortForwards(listOf("L 8080→8080"), listOf(
            { closed = true },
            { error("Listener no longer exists") }
        ))

        active.close()
        assertEquals(true, closed)
    }

    @Test fun concurrentStopsStillCloseEachListenerOnce() {
        val stopped = java.util.concurrent.atomic.AtomicInteger()
        val active = ActivePortForwards(listOf("SOCKS 1080"), listOf({ stopped.incrementAndGet() }))
        val workers = (1..16).map { Thread { active.close() }.also { it.start() } }
        workers.forEach { it.join() }
        assertEquals(1, stopped.get())
    }

    @Test fun newForwardHandleDoesNotDependOnAnOlderStoppedHandle() {
        var oldStops = 0
        var newStops = 0
        val old = ActivePortForwards(listOf("L 8080→8080"), listOf({ oldStops++ }))
        old.close()
        val replacement = ActivePortForwards(listOf("L 8081→8081"), listOf({ newStops++ }))

        assertEquals(1, oldStops)
        assertEquals(0, newStops)
        replacement.close()
        assertEquals(1, newStops)
    }
}
