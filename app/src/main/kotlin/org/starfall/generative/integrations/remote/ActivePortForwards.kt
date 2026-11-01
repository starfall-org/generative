package org.starfall.generative.integrations.remote

import java.util.concurrent.atomic.AtomicBoolean

/** Owns forwarding listeners, never a shared SSH session. Can be closed multiple times safely. */
internal class ActivePortForwards(
    val labels: List<String>,
    private val stopListeners: List<() -> Unit>
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopListeners.asReversed().forEach { stop -> runCatching { stop() } }
    }
}
