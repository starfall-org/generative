package org.starfall.generative.shared.subfeatures.terminal

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.generative.shared.model.remote.RemoteTerminalSession

internal class TerminalSlot(
    val terminal: RemoteTerminalSession,
    val bridge: RemoteTerminalBridge
) {
    var readerJob: Job? = null
}

/**
 * Process-wide registry of open remote terminals. The terminal screen only displays what is
 * registered here, so leaving the screen no longer closes the sessions: they keep running and
 * recording output until the user closes them, the remote shell exits, or the machine disconnects.
 */
internal object RemoteTerminalSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val slots = mutableStateMapOf<String, List<TerminalSlot>>()
    private val active = mutableStateMapOf<String, String?>()
    private val lost = mutableStateMapOf<String, Boolean>()

    fun slotsFor(machineId: String): List<TerminalSlot> = slots[machineId].orEmpty()
    fun activeIdFor(machineId: String): String? = active[machineId]
    fun setActive(machineId: String, terminalId: String?) { active[machineId] = terminalId }
    fun connectionLost(machineId: String): Boolean = lost[machineId] == true
    fun clearConnectionLost(machineId: String) { lost.remove(machineId) }

    /** Registers an opened terminal, makes it the active one and starts reading its output. */
    fun register(machineId: String, terminal: RemoteTerminalSession, bridge: RemoteTerminalBridge): TerminalSlot {
        val slot = TerminalSlot(terminal, bridge)
        slots[machineId] = slotsFor(machineId) + slot
        active[machineId] = terminal.id
        lost.remove(machineId)
        bridge.onRemoteLost = { discardDead(machineId, slot, markLost = true) }
        slot.readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = terminal.input.read(buffer)
                    if (count < 0) break
                    val chunk = buffer.copyOf(count)
                    // A renderer failure on one chunk must not end the loop: a dead reader leaves a
                    // frozen screen on a session that is still alive.
                    withContext(Dispatchers.Main) { runCatching { bridge.append(chunk) } }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                // Stream closed: handled below like an end of stream.
            } catch (_: RuntimeException) {
                // JSch can throw unchecked errors from a half-closed channel: same handling.
            }
            // The remote side ended the session (a local close cancels this job instead).
            if (isActive) {
                val cleanExit = terminal.exitStatus >= 0
                withContext(Dispatchers.Main) { discardDead(machineId, slot, markLost = !cleanExit) }
            }
        }
        return slot
    }

    /** Removes a session that is gone on the remote side and releases everything it held. */
    private fun discardDead(machineId: String, slot: TerminalSlot, markLost: Boolean) {
        if (slot !in slotsFor(machineId)) return
        slot.readerJob?.cancel()
        remove(machineId, slot)
        slot.bridge.close()
        slot.terminal.close()
        if (markLost && slotsFor(machineId).isEmpty()) lost[machineId] = true
    }

    /**
     * Drops sessions whose channel is no longer open but that nothing noticed yet (a reader stuck on a
     * dead socket, or output that stopped arriving), so the screen never shows a terminal that is gone.
     */
    fun pruneDead(machineId: String) {
        slotsFor(machineId).toList().forEach { slot ->
            if (!slot.terminal.isOpen) discardDead(machineId, slot, markLost = slot.terminal.exitStatus < 0)
        }
    }

    /**
     * Closes one session. Closing the last registered terminal disconnects the machine (see the
     * connection manager), so no separate disconnect call is needed.
     */
    fun close(machineId: String, slot: TerminalSlot) {
        slot.readerJob?.cancel()
        slot.bridge.close()
        slot.terminal.close()
        remove(machineId, slot)
    }

    fun closeAll(machineId: String) {
        slotsFor(machineId).toList().forEach { slot -> close(machineId, slot) }
    }

    private fun remove(machineId: String, slot: TerminalSlot) {
        val before = slotsFor(machineId)
        val index = before.indexOf(slot)
        val after = before.filterNot { it === slot }
        if (after.isEmpty()) {
            slots.remove(machineId)
            active.remove(machineId)
            return
        }
        slots[machineId] = after
        if (active[machineId] == slot.terminal.id) {
            active[machineId] = after[index.coerceIn(0, after.lastIndex)].terminal.id
        }
    }
}
