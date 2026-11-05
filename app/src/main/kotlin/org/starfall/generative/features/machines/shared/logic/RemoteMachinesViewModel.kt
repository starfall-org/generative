package org.starfall.generative.features.machines.shared.logic

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.shared.model.GCloudOAuthClient
import org.starfall.generative.shared.model.RemoteIdentityKey
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineState
import org.starfall.generative.shared.model.remote.RemoteSecretPrompt
import org.starfall.generative.shared.model.remote.RemoteTerminalSession
import org.starfall.generative.shared.state.LocalWriteErrors

class RemoteMachinesViewModel(
    private val service: RemoteMachineService
) : ViewModel() {
    val state: StateFlow<RemoteMachineState> = service.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, RemoteMachineState())

    /** All machines holding an SSH connection, whether or not forwarding is running. */
    val connectedMachineIds: StateFlow<Set<String>> = service.connectedMachineIds
    val forwardedPorts: StateFlow<Map<String, List<String>>> = service.forwardedPorts

    fun isConnected(machineId: String): Boolean = service.isConnected(machineId)

    fun disconnect(machineId: String) = service.disconnectMachine(machineId)

    /** Save only the forwarding settings, then explicitly activate them on the shared SSH session. */
    fun saveMachineAndStartPortForward(machine: RemoteMachine, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                service.savePortForwarding(machine)
                service.startPortForward(machine)
            })
        }
    }

    fun stopPortForward(machineId: String, onResult: (Result<Boolean>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { service.stopPortForward(machineId) })
        }
    }

    fun saveMachine(machine: RemoteMachine) {
        viewModelScope.launch(LocalWriteErrors.handler) { service.saveMachine(machine) }
    }

    fun saveMachineTools(machine: RemoteMachine) {
        viewModelScope.launch(LocalWriteErrors.handler) { service.saveMachineTools(machine) }
    }

    fun deleteMachine(id: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { service.deleteMachine(id) }
    }

    fun reorderMachines(ids: List<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { service.reorderMachines(ids) }
    }

    fun saveOAuthClient(client: GCloudOAuthClient) {
        viewModelScope.launch(LocalWriteErrors.handler) { service.saveOAuthClient(client) }
    }

    fun deleteOAuthClient(id: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { service.deleteOAuthClient(id) }
    }

    suspend fun importIdentityKey(uri: Uri): Result<RemoteIdentityKey> =
        runCatching { service.importIdentityKey(uri) }

    suspend fun importPublicKey(keyId: String, uri: Uri): Result<RemoteIdentityKey> =
        runCatching { service.importPublicKey(keyId, uri) }

    fun deleteIdentityKey(id: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { service.deleteIdentityKey(id) }
    }

    suspend fun importOAuthClientJson(uri: Uri): Result<GCloudOAuthClient> =
        runCatching { service.parseOAuthClientJson(uri) }

    suspend fun authorize(client: GCloudOAuthClient): Result<Unit> = service.authorizeGCloud(client)

    suspend fun storeAndroidOAuthToken(client: GCloudOAuthClient, accessToken: String): Result<Unit> =
        runCatching { service.storeAndroidOAuthToken(client, accessToken) }

    suspend fun refreshIp(machine: RemoteMachine): Result<RemoteMachine> = service.refreshGCloudIp(machine)

    suspend fun fetchGCloudUsername(machine: RemoteMachine): Result<String> = service.fetchGCloudUsername(machine)

    suspend fun startMachine(machine: RemoteMachine): Result<RemoteMachine> = service.startGCloudMachine(machine)

    suspend fun openTerminal(
        machine: RemoteMachine,
        passwordOverride: String? = null,
        askSecret: RemoteSecretPrompt? = null
    ): RemoteTerminalSession = service.openTerminal(machine, passwordOverride, askSecret)

    fun newMachine(): RemoteMachine = RemoteMachine(
        id = UUID.randomUUID().toString(),
        name = "",
        sortOrder = state.value.machines.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
    )

    fun newOAuthClient(): GCloudOAuthClient = GCloudOAuthClient(
        id = UUID.randomUUID().toString(),
        name = "",
        clientId = ""
    )
}
