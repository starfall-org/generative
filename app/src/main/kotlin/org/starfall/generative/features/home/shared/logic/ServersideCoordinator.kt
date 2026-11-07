package org.starfall.generative.features.home.shared.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.starfall.generative.database.preferences.ToolSettingsStore
import org.starfall.generative.integrations.remote.RemoteCommandResult
import org.starfall.generative.integrations.serverside.ServersideHandshakeException
import org.starfall.generative.integrations.serverside.parseServersideHandshake
import org.starfall.generative.integrations.serverside.isServersideMissing
import org.starfall.generative.shared.model.RemoteMachine

/** Carries diagnostics separately so the UI can localize the surrounding explanation. */
data class ServersideFailure(val machineName: String, val missing: Boolean, val detail: String)

class ServersideCoordinator(
    private val scope: CoroutineScope,
    private val store: ToolSettingsStore,
    private val handshake: suspend (RemoteMachine) -> RemoteCommandResult,
    private val probe: suspend (RemoteMachine) -> RemoteCommandResult,
) {
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    private val _failure = MutableStateFlow<ServersideFailure?>(null)
    val pending = _pending.asStateFlow()
    val failure = _failure.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val revisions = mutableMapOf<String, Long>()

    fun dismissError() { _failure.value = null }

    fun selectionChanged(machineId: String?) {
        dismissError()
        jobs.keys.filter { it != machineId }.toList().forEach { id ->
            revisions[id] = (revisions[id] ?: 0) + 1
            jobs.remove(id)?.cancel()
            _pending.value = _pending.value - id
        }
    }

    fun setEnabled(machine: RemoteMachine, enabled: Boolean) {
        val revision = (revisions[machine.id] ?: 0) + 1
        revisions[machine.id] = revision
        jobs.remove(machine.id)?.cancel()
        _pending.value = if (enabled) _pending.value + machine.id else _pending.value - machine.id
        _failure.value = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Opt-in is published only after a successful bootstrap. No invitation is persisted.
                store.update { it.copy(serversideMachines = it.serversideMachines + (machine.id to false)) }
                if (!enabled) return@launch
                val result = withTimeout(20000) { handshake(machine) }
                parseServersideHandshake(result, System.currentTimeMillis() / 1000)
                if (revisions[machine.id] != revision || store.snapshot().selectedMachineId != machine.id) return@launch
                store.update { it.copy(serversideMachines = it.serversideMachines + (machine.id to true)) }
            } catch (cancelled: CancellationException) {
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException && revisions[machine.id] == revision
                    && store.snapshot().selectedMachineId == machine.id) {
                    _failure.value = ServersideFailure(machine.name, false, "Handshake timed out.")
                } else throw cancelled
            } catch (error: Exception) {
                if (revisions[machine.id] == revision && store.snapshot().selectedMachineId == machine.id) {
                    _failure.value = ServersideFailure(machine.name,
                        (error as? ServersideHandshakeException)?.cliMissing == true,
                        error.message.orEmpty().take(4096))
                }
            } finally {
                if (revisions[machine.id] == revision) {
                    _pending.value = _pending.value - machine.id
                    jobs.remove(machine.id)
                }
            }
        }
        jobs[machine.id] = job
        job.start()
    }

    /** Called before provider execution, including queued turns. Failure stops this request. */
    suspend fun checkBeforeSend(machine: RemoteMachine) {
        val revision = revisions[machine.id]
        try {
            val result = withTimeout(20000) { probe(machine) }
            if (result.exitCode == 0 && result.output.trim().startsWith("serverside ")) return
            throw ServersideHandshakeException(result.isServersideMissing(),
                result.error.trim().take(4096).ifBlank { "CLI check failed (exit ${result.exitCode})." })
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            if (revisions[machine.id] != revision) return
            val settings = store.snapshot()
            if (settings.selectedMachineId != machine.id || settings.serversideMachines[machine.id] != true) return
            val missing = (error as? ServersideHandshakeException)?.cliMissing == true
            if (missing) store.update {
                it.copy(serversideMachines = it.serversideMachines + (machine.id to false))
            }
            _failure.value = ServersideFailure(machine.name, missing, error.message.orEmpty().take(4096))
            throw ServersideHandshakeException(missing, "Serverside CLI is unavailable.")
        }
    }
}
